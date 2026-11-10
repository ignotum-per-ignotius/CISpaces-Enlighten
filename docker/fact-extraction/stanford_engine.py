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
below is a reconstruction of that filter. Each verb is its own fact. A
window matches ``setSeedTuples``. Extra trailing ``ARGUMENT``s stay on that
fact. Extra leading ``ARGUMENT``s are trimmed so a long subject does not
drop the verb. It is not the 0.1.0 function.

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

import extractor
from extractor import Triple

# Reconstruction marker. Do not report this module as Southampton 0.1.0.
IMPLEMENTATION = "soton_corenlppy-1.0.0-alias"
SEED_FILTER = "reconstructed-get_seed_tuples_from_extractions"
PATTERN = "soton-reverb-3.7.0"
_NEGATION = {"not", "n't", "never", "no"}

_SITUATION_NLP = None
_SITUATION_UNAVAILABLE = False


def situation_for(sentence):
    """Places and times mentioned in one sentence.

    The Stanford path extracts the subject-relation-object facts. It has no
    place or time tagger of its own: the 2017 ``sit_assesspy`` wheel that did
    that job was never published. The spaCy model already in the image tags
    them here, with the same rules the spaCy engine uses, so
    ``phase1_situation`` is filled whichever engine extracts the facts.
    Returns two empty lists when the model cannot be loaded.
    """
    global _SITUATION_NLP, _SITUATION_UNAVAILABLE
    if _SITUATION_UNAVAILABLE or not sentence:
        return [], []
    if _SITUATION_NLP is None:
        try:
            import spacy
            _SITUATION_NLP = spacy.load(extractor.MODEL_NAME)
        except Exception:
            logging.getLogger("cispaces.stanford_openie").warning(
                "spaCy model %s is not available; facts are stored without places or times",
                extractor.MODEL_NAME)
            _SITUATION_UNAVAILABLE = True
            return [], []
    places = []
    times = []
    for sent in _SITUATION_NLP(sentence).sents:
        found_places, found_times = extractor._situation(sent)
        for place in found_places:
            if place not in places:
                places.append(place)
        for time_label in found_times:
            if time_label not in times:
                times.append(time_label)
    return places, times


def _is_negation_token(token):
    lower = token.lower()
    return lower in _NEGATION or lower.endswith("n't")


def _relation_runs(sent):
    """Consecutive RELATION annotations are one verb phrase."""
    runs = []
    index = 0
    count = len(sent)
    while index < count:
        if sent[index][0] == "RELATION":
            start = index
            index += 1
            while index < count and sent[index][0] == "RELATION":
                index += 1
            runs.append((start, index))
        else:
            index += 1
    return runs


def _argument_items(sent, start, end):
    return [sent[index] for index in range(start, end) if sent[index][0] == "ARGUMENT"]


def _accepted_types(types, set_sequences):
    """A listed seed, or that seed plus extra trailing ARGUMENTs."""
    if "RELATION" not in types or "ARGUMENT" not in types:
        return False
    if types in set_sequences:
        return True
    end = len(types)
    while end > 1 and types[end - 1] == "ARGUMENT":
        end -= 1
        prefix = types[:end]
        if prefix in set_sequences and "RELATION" in prefix and "ARGUMENT" in prefix:
            return True
    return False


def _trim_leading_arguments(window, set_sequences):
    """Drop extra leading ARGUMENTs until the seed matches.

    Trailing ARGUMENTs stay, including ones past the longest listed pattern.
    Leading ARGUMENTs beyond ``setSeedTuples`` are removed from the front so
    the verb is kept. The arguments nearest the verb are the ones that remain.
    """
    start = 0
    while start < len(window):
        candidate = window[start:]
        if _accepted_types(tuple(item[0] for item in candidate), set_sequences):
            return candidate
        if window[start][0] != "ARGUMENT":
            return None
        start += 1
    return None


def seed_tuples_from_extractions(list_sent_extractions, set_sequences):
    """Keep each verb-mediated fact.

    This is the reconstructed stand-in for
    ``openiepy.comp_sem_lib.get_seed_tuples_from_extractions``.
    It is not the 0.1.0 function. A coordinated verb is not folded into the
    previous fact by a longer trailing-argument pattern. One argument between
    two verbs stays the object of the first verb. The later verb then reuses
    that subject so its tuple still matches ``setSeedTuples``. Two or more
    arguments between verbs leave the last one as the next subject. Extra
    leading ARGUMENTs are trimmed; extra trailing ARGUMENTs stay.
    """
    found = []
    for sent in list_sent_extractions:
        runs = _relation_runs(sent)
        previous_subject = ()
        consumed = 0
        for run_index, (rel_start, rel_end) in enumerate(runs):
            next_rel = runs[run_index + 1][0] if run_index + 1 < len(runs) else len(sent)
            subject_items = _argument_items(sent, consumed, rel_start)
            object_items = _argument_items(sent, rel_end, next_rel)
            if run_index + 1 < len(runs) and len(object_items) >= 2:
                object_items = object_items[:-1]
            reused = False
            if not subject_items and previous_subject:
                subject_items = list(previous_subject)
                reused = True
            window = tuple(subject_items) + tuple(sent[rel_start:rel_end]) + tuple(object_items)
            fitted = _trim_leading_arguments(window, set_sequences)
            if fitted is None:
                consumed = rel_end
                continue
            found.append(tuple(fitted))
            if not reused:
                rel_at = next(index for index, item in enumerate(fitted) if item[0] == "RELATION")
                kept_subject = tuple(fitted[:rel_at])
                if kept_subject:
                    previous_subject = kept_subject
            if object_items:
                last_object = sent.index(object_items[-1], rel_end)
                consumed = last_object + 1
            else:
                consumed = rel_end
    return found


def _phrase_words(items):
    words = []
    for item in items:
        words.extend(str(part) for part in item[1:] if part)
    return words


def _find_words(sent, words, start_at):
    positions = []
    cursor = start_at
    for word in words:
        found = None
        for index in range(cursor, len(sent)):
            if sent[index][0] == word:
                found = index
                break
        if found is None:
            return None
        positions.append(found)
        cursor = found + 1
    return positions


def _fact_tokens(sent, seed, start_at):
    """Tokens of this fact, including a negation that sits inside its span.

    The span runs from the local subject through the object. A subject reused
    from an earlier clause is not part of the span, so a later fact does not
    inherit an earlier negation.
    """
    rel_at = [index for index, item in enumerate(seed) if item[0] == "RELATION"]
    if not rel_at:
        rows = [{"t": token, "pos": pos} for token, pos in sent]
        return rows, any(_is_negation_token(token) for token, _pos in sent), start_at
    first_rel = rel_at[0]
    last_rel = rel_at[-1]
    rel_pos = _find_words(sent, _phrase_words(seed[first_rel:last_rel + 1]), start_at)
    if not rel_pos:
        rel_pos = _find_words(sent, _phrase_words(seed[first_rel:last_rel + 1]), 0)
    if not rel_pos:
        rows = [{"t": word, "pos": ""} for word in _phrase_words(seed)]
        return rows, any(_is_negation_token(word) for word in _phrase_words(seed)), start_at
    obj_pos = _find_words(sent, _phrase_words(seed[last_rel + 1:]), rel_pos[-1] + 1) or []
    subj_pos = _find_words(sent, _phrase_words(seed[:first_rel]), start_at) or []
    if subj_pos and subj_pos[-1] < rel_pos[0]:
        start = subj_pos[0]
    else:
        start = rel_pos[0]
    end = obj_pos[-1] if obj_pos else rel_pos[-1]
    rows = [{"t": token, "pos": pos} for token, pos in sent[start:end + 1]]
    negated = any(_is_negation_token(token) for token, _pos in sent[start:end + 1])
    return rows, negated, end + 1


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
            places, times = situation_for(sentence)
            seen = set()
            cursor = 0
            for seed in seeds:
                subject, relation, obj = _split_seed(seed)
                key = (subject.lower(), relation.lower(), obj.lower())
                if key in seen or (not subject and not obj):
                    continue
                seen.add(key)
                token_rows, negated, cursor = _fact_tokens(sent, seed, cursor)
                triples.append(Triple(
                    sentence=sentence,
                    subject=subject,
                    relation=relation,
                    object=obj,
                    negated=negated,
                    genuine=None,
                    tokens=token_rows,
                    places=list(places),
                    times=list(times),
                    pattern=PATTERN,
                ))
        return triples
