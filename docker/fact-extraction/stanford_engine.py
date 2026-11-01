"""Stanford CoreNLP 3.7.0 fact extraction for the phase-1 tables.

The tagger and parser are the 2016-10-31 distributions. Their jars report
Implementation-Version 3.7.0. CoreNLP 3.8.0 is the 2017-06-09 release and is
not used.

OpenIE calls go through the 2017 import names (``openiepy.comp_sem_lib``).
Those names re-export ``soton_corenlppy`` 1.0.0 from PyPI. They are not the
unpublished wheels named in ``CISpaces/factextraction`` ``build.xml``:

- soton_corenlppy-0.1.0-py27-none-any
- openiepy-0.1.0-py27-none-any
- lexicopy-0.1.0-py27-none-any
- sit_assesspy-0.1.0-py27-none-any

``get_seed_tuples_from_extractions`` is called by ``fact_extraction_app.py``
and is not defined on the 1.0.0 library. ``seed_tuples_from_extractions``
below is a reconstruction of that filter: keep an annotation window whose
type sequence is one of the original ``setSeedTuples``. It is not the
0.1.0 function.

The dependency-parser template learner is not run on the request path. The
2017 app used it to build open-extraction templates, and
``get_dependency_parser`` asks Java for a 4g heap. Request extraction uses
the original ReVerb POS patterns and the Stanford tagger.
"""

import logging
import os

import nltk

import fact_extraction_patterns_regex as patterns
import openiepy.comp_sem_lib
import openiepy.openie_lib
import soton_corenlppy.common_parse_lib

from extractor import Triple

# Reconstruction marker. Do not report this module as Southampton 0.1.0.
IMPLEMENTATION = "soton_corenlppy-1.0.0-alias"
SEED_FILTER = "reconstructed-get_seed_tuples_from_extractions"
PATTERN = "soton-reverb-3.7.0"
_NEGATION = {"not", "n't", "never", "no"}


def seed_tuples_from_extractions(list_sent_extractions, set_sequences):
    """Keep the longest seed window in each sentence.

    This is the reconstructed stand-in for
    ``openiepy.comp_sem_lib.get_seed_tuples_from_extractions``.
    """
    found = []
    for sent in list_sent_extractions:
        best = None
        count = len(sent)
        for start in range(count):
            for end in range(start + 1, count + 1):
                window = sent[start:end]
                types = tuple(item[0] for item in window)
                if types not in set_sequences:
                    continue
                if "RELATION" not in types or "ARGUMENT" not in types:
                    continue
                if best is None or len(window) > len(best):
                    best = window
        if best is not None:
            found.append(tuple(best))
    return found


def _phrase(items):
    words = []
    for item in items:
        words.extend(str(part) for part in item[1:] if part)
    return " ".join(words).strip()


def _split_seed(seed):
    relation_at = [index for index, item in enumerate(seed) if item[0] == "RELATION"]
    if not relation_at:
        return _phrase(seed), "", ""
    first = relation_at[0]
    last = relation_at[-1]
    return _phrase(seed[:first]), _phrase(seed[first:last + 1]), _phrase(seed[last + 1:])


class StanfordOpenIE:
    def __init__(self, tagger_dir, parser_dir=None, model_jar=None):
        if not tagger_dir or not os.path.isdir(tagger_dir):
            raise RuntimeError("Stanford tagger directory is missing")
        nltk.download("stopwords", quiet=True)
        nltk.download("names", quiet=True)
        logger = logging.getLogger("cispaces.stanford_openie")
        if not logger.handlers:
            logger.addHandler(logging.NullHandler())
        self.config = openiepy.openie_lib.get_openie_config(
            lang_codes=["en"],
            logger=logger,
            stanford_tagger_dir=tagger_dir,
            stanford_parser_dir=parser_dir or "",
            dep_model_path="edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz",
            dep_model_jar=model_jar or "",
            dep_options="",
            whitespace='"\u201a\u201b\u201c\u201d',
            punctuation='",;\\/:+~&*=!?-',
            sent_token_seps=["\n", "\r", "\f", "\u2026"],
            # fact_extraction_app.py passes False. Release 1.0.0 accepts
            # preserve, strip, or none. none is the same switch: leave apostrophes.
            apostrophe_handling="none",
        )
        self.tagger_dir = tagger_dir

    @classmethod
    def from_env(cls):
        return cls(
            os.environ.get("STANFORD_TAGGER_DIR", ""),
            os.environ.get("STANFORD_PARSER_DIR", ""),
            os.environ.get("STANFORD_MODEL_JAR", ""),
        )

    def extract_triples(self, text):
        text = (text or "").strip()
        if not text:
            return []
        if len(text) > 20000:
            text = text[:20000]
        sentences = soton_corenlppy.common_parse_lib.unigram_tokenize_text_with_sent_breakdown(
            text=text,
            dict_common_config=self.config,
        )
        tagged = soton_corenlppy.common_parse_lib.pos_tag_tokenset(
            token_set=sentences,
            lang="en",
            dict_common_config=self.config,
        )
        triples = []
        for sent in tagged:
            if not sent:
                continue
            trees = soton_corenlppy.common_parse_lib.create_sent_trees(
                list_pos=sent,
                dict_common_config=self.config,
            )
            annotated = openiepy.comp_sem_lib.annotate_using_pos_patterns(
                list_sent_trees=trees,
                list_phrase_sequence_patterns_exec_order=patterns.listReVerbExecutionOrder,
                dict_phrase_sequence_patterns=patterns.dictReVerbPatterns,
                dict_openie_config=self.config,
            )
            extractions = openiepy.comp_sem_lib.extract_annotations_from_sents(
                list_sent_trees=annotated,
                set_annotations=set(patterns.listReVerbExecutionOrder),
                dict_openie_config=self.config,
            )
            seeds = seed_tuples_from_extractions(extractions, patterns.setSeedTuples)
            sentence = " ".join(token for token, _pos in sent)
            token_rows = [{"t": token, "pos": pos} for token, pos in sent]
            negated = any(token.lower() in _NEGATION or token.lower().endswith("n't") for token, _pos in sent)
            seen = set()
            for seed in seeds:
                subject, relation, obj = _split_seed(seed)
                key = (subject.lower(), relation.lower(), obj.lower())
                if key in seen or (not subject and not obj):
                    continue
                seen.add(key)
                triples.append(Triple(
                    sentence=sentence,
                    subject=subject,
                    relation=relation,
                    object=obj,
                    negated=negated,
                    genuine=None,
                    tokens=token_rows,
                    places=[],
                    times=[],
                    pattern=PATTERN,
                ))
        return triples
