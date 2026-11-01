"""Extractor checks that do not need PostgreSQL or RabbitMQ."""

import unittest

from extractor import Lexicon, decode_triple, encode_triple, extract_phrase, extract_text, load_nlp, message_kind, posts_from_payload


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
