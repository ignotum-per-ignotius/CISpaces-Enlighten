"""Extractor checks that do not need PostgreSQL or RabbitMQ."""

import json
import os
import tempfile
import threading
import time
import unittest

from extractor import (
    Lexicon,
    apply_control_lexicon,
    decode_triple,
    encode_triple,
    extract_phrase,
    extract_text,
    load_nlp,
    message_kind,
    posts_from_payload,
)


class ExtractorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.nlp = load_nlp()

    def extract(self, text):
        return extract_text(self.nlp, text)

    def test_sentence_split(self):
        triples = self.extract("The NYSE reported unrest. The hospital evacuated patients.")
        sentences = {triple.sentence for triple in triples}
        self.assertGreaterEqual(len(sentences), 2)
        self.assertTrue(any("NYSE" in sentence for sentence in sentences))
        self.assertTrue(any("hospital" in sentence for sentence in sentences))

    def test_subject_relation_object(self):
        triples = self.extract("The NYSE reported unrest.")
        self.assertTrue(triples)
        match = [triple for triple in triples if "unrest" in triple.object.lower()]
        self.assertTrue(match)
        triple = match[0]
        self.assertIn("NYSE", triple.subject)
        self.assertIn("report", triple.relation)
        self.assertTrue(any(token["pos"] == "VERB" for token in triple.tokens))
        self.assertTrue(any(token["ent"] == "ORG" and token["t"] == "NYSE" for token in triple.tokens))

    def test_negation_and_false_report(self):
        negated = self.extract("There is no unrest in the city.")
        self.assertTrue(any(triple.negated and "unrest" in triple.subject.lower() for triple in negated))
        false_report = self.extract("It was a false report on the virus.")
        self.assertTrue(any(triple.genuine is False for triple in false_report))

    def test_passive_and_confirmed(self):
        closed = self.extract("The trading floor is closed.")
        self.assertTrue(any("floor" in triple.subject.lower() and "closed" in triple.object.lower() for triple in closed))
        confirmed = self.extract("Confirmed rioting on NYSE.")
        self.assertTrue(any(triple.genuine is True for triple in confirmed))
        self.assertTrue(any("NYSE" in triple.object for triple in confirmed))

    def test_reverb_relation_and_second_verb(self):
        deal = self.extract("The NYSE made a deal with the mayor.")
        match = [triple for triple in deal if "mayor" in triple.object.lower()]
        self.assertTrue(match)
        self.assertIn("deal", match[0].relation)
        self.assertIn("with", match[0].relation)
        self.assertEqual(match[0].pattern, "reverb")
        self.assertIn("NYSE", match[0].subject)
        coordinated = self.extract("The NYSE reported unrest and closed the hospital.")
        relations = {triple.relation for triple in coordinated}
        self.assertTrue(any("report" in relation for relation in relations))
        self.assertTrue(any(relation == "closed" for relation in relations))
        self.assertGreaterEqual(len(coordinated), 2)

    def test_fact_level_negation_and_genuine(self):
        mixed = self.extract("The NYSE reported unrest, but there is no virus.")
        unrest = [triple for triple in mixed if "unrest" in " ".join((triple.subject, triple.object)).lower()]
        virus = [triple for triple in mixed if "virus" in " ".join((triple.subject, triple.object, triple.relation)).lower()]
        self.assertTrue(unrest)
        self.assertTrue(virus)
        self.assertFalse(any(triple.negated for triple in unrest))
        self.assertTrue(any(triple.negated for triple in virus))
        claims = self.extract("It was a false report on the virus, and the NYSE reported unrest.")
        false_claim = [triple for triple in claims if triple.genuine is False]
        nyse = [triple for triple in claims if "NYSE" in triple.subject or "NYSE" in triple.object]
        self.assertTrue(false_claim)
        self.assertTrue(nyse)
        self.assertTrue(all(triple.genuine is None for triple in nyse))

    def test_place_and_time(self):
        triples = self.extract("Police closed the NYSE in New York on Tuesday.")
        self.assertTrue(triples)
        self.assertTrue(any("New York" in triple.places for triple in triples))
        self.assertTrue(any("Tuesday" in triple.times for triple in triples))
        city = self.extract("There is no unrest in the city.")
        self.assertTrue(any("city" in " ".join(triple.places).lower() for triple in city))

    def test_lexicon_matches_phrase_and_stem(self):
        lexicon = Lexicon.from_control({
            "lexicon": {
                "nyse": ["building"],
                "unrest": ["topic"],
                "report": ["report"],
                "wall_street": ["route"],
            },
            "phrase_mapping": {
                "nyse": ["nyse"],
                "unrest": ["unrest"],
                "report": ["report"],
                "wall_street": ["wall_street"],
                "wall street": ["wall_street"],
            },
        })
        matches = dict(lexicon.match("The NYSE reported unrest on Wall Street."))
        self.assertEqual(matches["nyse"], "building")
        self.assertEqual(matches["unrest"], "topic")
        self.assertEqual(matches["report"], "report")
        self.assertEqual(matches["wall_street"], "route")

    def test_longer_phrase_wins(self):
        lexicon = Lexicon.from_control({
            "lexicon": {"exchange": ["topic"], "nyse": ["building"]},
            "phrase_mapping": {
                "stock exchange": ["exchange"],
                "exchange": ["exchange"],
                "new york stock exchange": ["nyse"],
            },
        })
        topics = [topic for topic, _schema in lexicon.match("the new york stock exchange is closed")]
        self.assertIn("nyse", topics)
        self.assertNotIn("exchange", topics)

    def test_message_kinds_and_twitter_post(self):
        self.assertEqual(message_kind({"lexicon": {}, "phrase_mapping": {}}), "vocabulary")
        self.assertEqual(message_kind({"phrase mapping": {}, "lexicon": {}}), "vocabulary")
        self.assertEqual(message_kind({"text": "The NYSE reported unrest."}), "text")
        self.assertIsNone(message_kind({"hello": "world"}))
        posts = posts_from_payload({
            "id_str": "9001",
            "text": "The NYSE reported unrest.",
            "created_at": "Tue Oct 30 19:19:30 +0000 2012",
            "user": {"screen_name": "cispaces"},
        })
        self.assertEqual(len(posts), 1)
        self.assertEqual(posts[0].source_uri, "https://twitter.com/cispaces/status/9001")
        self.assertEqual(posts[0].created_at.year, 2012)

    def test_control_lexicon_waits_for_reindex(self):
        kept = Lexicon.empty()
        box = [kept]

        class FailedStore:
            def reindex(self, _lexicon):
                raise RuntimeError("reindex failed")

        payload = {"lexicon": {"nyse": ["building"]}, "phrase_mapping": {"nyse": ["nyse"]}}
        with self.assertRaises(RuntimeError):
            apply_control_lexicon(FailedStore(), box, payload)
        self.assertIs(box[0], kept)

        class RecordingStore:
            def __init__(self):
                self.seen = None

            def reindex(self, lexicon):
                self.seen = lexicon
                return 3

        store = RecordingStore()
        linked = apply_control_lexicon(store, box, payload)
        self.assertEqual(linked, 3)
        self.assertIs(box[0], store.seen)
        self.assertEqual(dict(box[0].match("nyse"))["nyse"], "building")

    def test_scenario_graph_and_dropped_file(self):
        from inbox import message_from_file
        from scenario import GRAPH_ID, build_graph

        triple = self.extract("Police closed the NYSE in New York on Tuesday.")[0]
        graph = build_graph([
            ("cispaces:file:note.txt", "cispaces:file:note.txt#1", encode_triple(triple)),
        ])
        self.assertEqual(graph["graphID"], GRAPH_ID)
        texts = {node["text"] for node in graph["nodes"]}
        self.assertIn("New York", texts)
        self.assertIn("Tuesday", texts)
        self.assertTrue(graph["edges"])
        stored = json.loads(graph["stored"])
        self.assertNotIn("stored", stored)
        body = json.loads(message_from_file("note.txt", b"Police closed the NYSE in New York on Tuesday."))
        self.assertEqual(body["source_uri"], "cispaces:file:note.txt")
        self.assertIn("New York", body["text"])
        wrapped = json.loads(message_from_file("already.json", b'{"text":"The NYSE reported unrest."}'))
        self.assertEqual(wrapped["text"], "The NYSE reported unrest.")

    def test_inbox_poller_claims_once_and_overlapping_polls_publish_once(self):
        import inbox

        inbox._poller_started = False
        try:
            self.assertTrue(inbox.claim_poller())
            self.assertFalse(inbox.claim_poller())
        finally:
            inbox._poller_started = False

        directory = tempfile.mkdtemp()
        seen_path = os.path.join(directory, "seen")
        with open(os.path.join(directory, "note.txt"), "w", encoding="utf-8") as handle:
            handle.write("The NYSE reported unrest.")
        old_dir, old_seen = inbox.INBOX_DIR, inbox.SEEN_PATH
        inbox.INBOX_DIR = directory
        inbox.SEEN_PATH = seen_path
        calls = []
        started = threading.Event()
        release = threading.Event()

        def publish(_cfg, _body):
            calls.append(1)
            started.set()
            release.wait(3)

        first = threading.Thread(target=inbox.poll_once, args=(None, publish))
        second = threading.Thread(target=inbox.poll_once, args=(None, publish))
        try:
            first.start()
            self.assertTrue(started.wait(3))
            second.start()
            time.sleep(0.2)
            self.assertEqual(len(calls), 1)
            release.set()
            first.join(3)
            second.join(3)
        finally:
            release.set()
            first.join(3)
            second.join(3)
            inbox.INBOX_DIR = old_dir
            inbox.SEEN_PATH = old_seen
        self.assertEqual(len(calls), 1)

    def test_failed_publish_is_retried(self):
        import inbox

        directory = tempfile.mkdtemp()
        seen_path = os.path.join(directory, "seen")
        with open(os.path.join(directory, "note.txt"), "w", encoding="utf-8") as handle:
            handle.write("The NYSE reported unrest.")
        old_dir, old_seen = inbox.INBOX_DIR, inbox.SEEN_PATH
        inbox.INBOX_DIR = directory
        inbox.SEEN_PATH = seen_path
        calls = []

        def fail(_cfg, _body):
            raise RuntimeError("broker down")

        try:
            with self.assertRaises(RuntimeError):
                inbox.poll_once(None, fail)
            self.assertFalse(os.path.exists(seen_path))
            inbox.poll_once(None, lambda _cfg, body: calls.append(body))
        finally:
            inbox.INBOX_DIR = old_dir
            inbox.SEEN_PATH = old_seen
        self.assertEqual(len(calls), 1)

    def test_shorter_graph_drops_old_nodes_and_edges(self):
        from scenario import build_graph, sync_actions

        first = self.extract("The NYSE made a deal with the mayor.")[0]
        second = self.extract("The NYSE reported unrest.")[0]
        full = build_graph([
            ("cispaces:file:note.txt", "cispaces:file:note.txt#1", encode_triple(first)),
            ("cispaces:file:note.txt", "cispaces:file:note.txt#2", encode_triple(second)),
        ])
        short = build_graph([
            ("cispaces:file:note.txt", "cispaces:file:note.txt#1", encode_triple(second)),
        ])
        actions = sync_actions(json.loads(full["stored"]), short)
        self.assertTrue(actions["delete_nodes"])
        self.assertTrue(actions["delete_edges"])
        deleted = set(actions["delete_nodes"])
        kept = {node["nodeID"] for node in actions["put_nodes"]}
        self.assertTrue(deleted.isdisjoint(kept))
        removed_text = {
            node["text"] for node in json.loads(full["stored"])["nodes"] if node["nodeID"] in deleted
        }
        self.assertTrue(any("deal" in text for text in removed_text))
        self.assertNotIn(
            next(node["nodeID"] for node in short["nodes"] if "unrest" in node["text"] or node["text"] == "reported"),
            deleted,
        )

    def test_encoded_roundtrip(self):
        triple = self.extract("The NYSE reported unrest.")[0]
        restored = decode_triple(encode_triple(triple))
        self.assertEqual(restored.subject, triple.subject)
        self.assertEqual(restored.relation, triple.relation)
        self.assertEqual(restored.object, triple.object)
        self.assertIn("nyse", extract_phrase(triple))
        self.assertIsNone(decode_triple("{rel1 [head=is] is}"))


if __name__ == "__main__":
    unittest.main()
