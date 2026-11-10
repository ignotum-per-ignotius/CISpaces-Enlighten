"""Exercise the Stanford 3.7.0 tagger and the original ReVerb patterns.

Skipped unless STANFORD_TAGGER_DIR points at the 2016-10-31 tagger.
The spaCy stand-in tests stay in test_extract.py.
"""

import os
import unittest

import fact_extraction_patterns_regex as patterns
import openiepy
import sit_assesspy.sit_assess_lib
from stanford_engine import (
    IMPLEMENTATION,
    PATTERN,
    SEED_FILTER,
    StanfordOpenIE,
    seed_tuples_from_extractions,
    situation_for,
)


TAGGER = os.environ.get("STANFORD_TAGGER_DIR", "")


class AliasTest(unittest.TestCase):
    def test_aliases_are_not_the_missing_wheels(self):
        self.assertTrue(openiepy.NOT_THE_0_1_0_WHEEL)
        self.assertTrue(sit_assesspy.NOT_THE_0_1_0_WHEEL)
        self.assertEqual(IMPLEMENTATION, "soton_corenlppy-1.0.0-alias")
        self.assertIn("reconstructed", SEED_FILTER)
        self.assertTrue(callable(sit_assesspy.sit_assess_lib.SitAssessLib))

    def test_seed_filter_keeps_the_original_argument_relation_shape(self):
        sent = [
            ("ARGUMENT", "NYSE"),
            ("RELATION", "reported"),
            ("ARGUMENT", "unrest"),
        ]
        seeds = seed_tuples_from_extractions([sent], {("ARGUMENT", "RELATION", "ARGUMENT")})
        self.assertEqual(seeds, [tuple(sent)])

    def test_coordinated_verbs_stay_separate_facts(self):
        sent = [
            ("ARGUMENT", "NYSE"),
            ("RELATION", "reported"),
            ("ARGUMENT", "unrest"),
            ("RELATION", "closed"),
            ("ARGUMENT", "hospital"),
        ]
        seeds = seed_tuples_from_extractions([sent], patterns.setSeedTuples)
        self.assertEqual(len(seeds), 2)
        self.assertEqual(seeds[0], (
            ("ARGUMENT", "NYSE"),
            ("RELATION", "reported"),
            ("ARGUMENT", "unrest"),
        ))
        self.assertEqual(seeds[1], (
            ("ARGUMENT", "NYSE"),
            ("RELATION", "closed"),
            ("ARGUMENT", "hospital"),
        ))

    def test_trailing_argument_does_not_swallow_the_next_subject(self):
        sent = [
            ("ARGUMENT", "NYSE"),
            ("RELATION", "reported"),
            ("ARGUMENT", "unrest"),
            ("ARGUMENT", "hospital"),
            ("RELATION", "closed"),
            ("ARGUMENT", "road"),
        ]
        seeds = seed_tuples_from_extractions([sent], patterns.setSeedTuples)
        self.assertEqual(seeds[0][-1], ("ARGUMENT", "unrest"))
        self.assertNotIn(("ARGUMENT", "hospital"), seeds[0])
        self.assertEqual(seeds[1][0], ("ARGUMENT", "hospital"))
        self.assertEqual(seeds[1][1], ("RELATION", "closed"))
        self.assertEqual(seeds[1][-1], ("ARGUMENT", "road"))

    def test_extra_trailing_arguments_stay_on_one_fact(self):
        sent = [
            ("ARGUMENT", "NYSE"),
            ("RELATION", "reported"),
            ("ARGUMENT", "unrest"),
            ("ARGUMENT", "panic"),
            ("ARGUMENT", "rumours"),
            ("ARGUMENT", "closures"),
            ("ARGUMENT", "delays"),
            ("ARGUMENT", "queues"),
        ]
        seeds = seed_tuples_from_extractions([sent], patterns.setSeedTuples)
        self.assertEqual(len(seeds), 1)
        self.assertEqual(seeds[0][-1], ("ARGUMENT", "queues"))
        self.assertEqual(seeds[0][1], ("RELATION", "reported"))

    def test_long_subject_does_not_drop_the_verb(self):
        sent = [
            ("ARGUMENT", "city"),
            ("ARGUMENT", "block"),
            ("ARGUMENT", "exchange"),
            ("ARGUMENT", "NYSE"),
            ("RELATION", "reported"),
            ("ARGUMENT", "unrest"),
            ("ARGUMENT", "panic"),
            ("ARGUMENT", "rumours"),
            ("ARGUMENT", "closures"),
            ("ARGUMENT", "delays"),
            ("ARGUMENT", "queues"),
        ]
        seeds = seed_tuples_from_extractions([sent], patterns.setSeedTuples)
        self.assertEqual(len(seeds), 1)
        self.assertNotIn(("ARGUMENT", "city"), seeds[0])
        self.assertEqual(seeds[0][:4], (
            ("ARGUMENT", "block"),
            ("ARGUMENT", "exchange"),
            ("ARGUMENT", "NYSE"),
            ("RELATION", "reported"),
        ))
        self.assertEqual(seeds[0][-1], ("ARGUMENT", "queues"))


class SituationTest(unittest.TestCase):
    def test_time_is_tagged_without_the_stanford_jars(self):
        _places, times = situation_for("The police arrested three smugglers in Kabul on Tuesday .")
        self.assertIn("tuesday", " ".join(times).lower())

    def test_empty_sentence_has_no_situation(self):
        self.assertEqual(situation_for(""), ([], []))


@unittest.skipUnless(TAGGER and os.path.isdir(TAGGER), "Stanford tagger directory is not set")
class StanfordExtractorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.engine = StanfordOpenIE(TAGGER)

    def test_nyse_reported_unrest(self):
        triples = self.engine.extract_triples("The NYSE reported unrest.")
        self.assertTrue(triples, "ReVerb patterns produced no triple")
        match = [triple for triple in triples if "unrest" in triple.object.lower()]
        self.assertTrue(match)
        triple = match[0]
        self.assertIn("NYSE", triple.subject)
        self.assertIn("reported", triple.relation.lower())
        self.assertEqual(triple.pattern, PATTERN)
        self.assertTrue(any(token["pos"].startswith("VB") for token in triple.tokens))
        self.assertTrue(any(token["pos"].startswith("NN") and token["t"] == "NYSE" for token in triple.tokens))

    def test_facts_carry_the_time_named_in_the_sentence(self):
        triples = self.engine.extract_triples(
            "The police arrested three smugglers in Kabul on Tuesday.")
        self.assertTrue(triples, "ReVerb patterns produced no triple")
        self.assertTrue(
            any("tuesday" in " ".join(triple.times).lower() for triple in triples),
            "no fact carries the time; /fewsservlet/situations would stay empty")

    def test_negation_from_the_tagged_token(self):
        triples = self.engine.extract_triples("The hospital did not evacuate patients.")
        self.assertTrue(triples)
        self.assertTrue(all(triple.negated for triple in triples))
        self.assertTrue(any(token["t"].lower() == "not" for triple in triples for token in triple.tokens))

    def test_later_negation_does_not_mark_an_earlier_claim(self):
        triples = self.engine.extract_triples("The NYSE reported unrest, but there is no virus.")
        reported = [triple for triple in triples if "reported" in triple.relation.lower()]
        self.assertTrue(reported)
        self.assertFalse(reported[0].negated)
        self.assertFalse(any(token["t"].lower() == "no" for token in reported[0].tokens))
        virus = [triple for triple in triples if "virus" in triple.object.lower()]
        self.assertTrue(virus)
        self.assertTrue(virus[0].negated)
        self.assertTrue(any(token["t"].lower() == "no" for token in virus[0].tokens))

    def test_negation_stays_on_the_clause_that_contains_it(self):
        triples = self.engine.extract_triples("The NYSE reported no unrest and closed the hospital.")
        reported = [triple for triple in triples if "reported" in triple.relation.lower()]
        closed = [triple for triple in triples if "closed" in triple.relation.lower()]
        self.assertTrue(reported)
        self.assertTrue(closed)
        self.assertTrue(reported[0].negated)
        self.assertFalse(closed[0].negated)
        self.assertIn("hospital", closed[0].object.lower())

    def test_coordinated_sentence_keeps_both_verbs(self):
        triples = self.engine.extract_triples("The NYSE reported unrest and closed the hospital.")
        reported = [triple for triple in triples if "reported" in triple.relation.lower() and "unrest" in triple.object.lower()]
        closed = [triple for triple in triples if "closed" in triple.relation.lower() and "hospital" in triple.object.lower()]
        self.assertTrue(reported)
        self.assertTrue(closed)
        self.assertIn("NYSE", reported[0].subject)
        self.assertIn("NYSE", closed[0].subject)
        self.assertGreaterEqual(len(triples), 2)


if __name__ == "__main__":
    unittest.main()
