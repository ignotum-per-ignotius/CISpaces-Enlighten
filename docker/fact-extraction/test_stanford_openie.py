"""Exercise the Stanford 3.7.0 tagger and the original ReVerb patterns.

Skipped unless STANFORD_TAGGER_DIR points at the 2016-10-31 tagger.
The spaCy stand-in tests stay in test_extract.py.
"""

import os
import unittest

import openiepy
import sit_assesspy.sit_assess_lib
from stanford_engine import (
    IMPLEMENTATION,
    PATTERN,
    SEED_FILTER,
    StanfordOpenIE,
    seed_tuples_from_extractions,
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

    def test_negation_from_the_tagged_token(self):
        triples = self.engine.extract_triples("The hospital did not evacuate patients.")
        self.assertTrue(triples)
        self.assertTrue(any(triple.negated for triple in triples))


if __name__ == "__main__":
    unittest.main()
