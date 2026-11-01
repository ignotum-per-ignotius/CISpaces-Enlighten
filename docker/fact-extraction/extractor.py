"""Small English fact extractor for the CISpaces phase-1 tables.

This is a replacement for the 2017 Southampton pipeline, not a port of it.
Sentence breaks, part-of-speech tags, entity labels, and a subject-relation-object
triple come from spaCy's small English model. Vocabulary control messages use
the same JSON shape FEWS publishes on ``cispaces_exchange``.
"""

import hashlib
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime

import spacy

MODEL_NAME = "en_core_web_sm"
ENCODED_VERSION = 1
MAX_TEXT_CHARS = 20000
MAX_POSTS = 100

_NLP = None

_SUBJ = ("nsubj", "nsubjpass")
_OBJ = ("dobj", "obj", "attr", "acomp", "oprd")
_PREP = ("prep", "agent")
_SKIP_PRED = ("aux", "auxpass", "cop")
_LEFT_NOMINAL = ("PROPN", "NOUN")
_REL_W = {"NOUN", "PROPN", "ADJ", "ADV", "PRON", "DET", "NUM"}
_PLACE_PREP = {"in", "at", "near", "outside", "inside"}
_PLACE_LEMMA = {
    "city", "town", "village", "street", "road", "downtown", "hospital",
    "district", "neighborhood", "neighbourhood", "avenue", "building",
    "square", "bridge",
}
_PLACE_ENT = {"GPE", "LOC", "FAC"}
_TIME_ENT = {"DATE", "TIME"}

_FALSE_CUES = re.compile(r"\b(?:false|fake|hoax|unconfirmed|debunked)\b|\bnot true\b", re.I)
_TRUE_CUES = re.compile(r"\b(?:confirmed|genuine|verified)\b", re.I)
_NEGATION = {"no", "not", "n't", "never", "without"}
_LEADING_NEG = re.compile(r"^(?:no|not)\s+", re.I)
_SPACE = re.compile(r"\s+")
_NON_WORD = re.compile(r"[^a-z0-9\s]+")


@dataclass
class Triple:
    sentence: str
    subject: str
    relation: str
    object: str
    negated: bool
    genuine: bool | None
    tokens: list
    places: list
    times: list
    pattern: str


@dataclass
class Post:
    text: str
    source_uri: str
    created_at: datetime | None


def load_nlp():
    global _NLP
    if _NLP is None:
        _NLP = spacy.load(MODEL_NAME)
    return _NLP


def message_kind(payload):
    """Classify a JSON body from the fanout exchange.

    Vocabulary messages are the control documents FEWS sends. Anything with
    post text is a document to extract. Other shapes are ignored.
    """
    if isinstance(payload, list):
        return "text"
    if not isinstance(payload, dict):
        return None
    phrase_key = "phrase_mapping" in payload or "phrase mapping" in payload
    if "lexicon" in payload and phrase_key:
        return "vocabulary"
    if any(key in payload for key in ("text", "full_text", "posts", "tweet")):
        return "text"
    return None


class Lexicon:
    """Phrase to topic map. Longer phrases win over a phrase inside them."""

    def __init__(self, entries):
        self.entries = tuple(entries)

    @classmethod
    def empty(cls):
        return cls(())

    @classmethod
    def from_control(cls, payload):
        phrase_mapping = payload.get("phrase_mapping", payload.get("phrase mapping", {}))
        lexicon = payload.get("lexicon", {})
        return cls(_entries_from_maps(lexicon, phrase_mapping))

    @classmethod
    def from_rows(cls, rows):
        """rows are (topic, schema, keyword) from the vocab tables."""
        lexicon = {}
        phrase_mapping = {}
        for topic, schema, keyword in rows:
            if not topic or not keyword:
                continue
            lexicon.setdefault(topic, [])
            if schema and schema not in lexicon[topic]:
                lexicon[topic].append(schema)
            phrase_mapping.setdefault(keyword, [])
            if topic not in phrase_mapping[keyword]:
                phrase_mapping[keyword].append(topic)
        return cls(_entries_from_maps(lexicon, phrase_mapping))

    def match(self, text):
        if not text or not self.entries:
            return []
        tokens = _normalize(text).split()
        if not tokens:
            return []
        raw = []
        for phrase, topic, schema in self.entries:
            ptoks = phrase.split()
            n = len(ptoks)
            if n == 0 or n > len(tokens):
                continue
            for start in range(len(tokens) - n + 1):
                if _tokens_match(tokens[start:start + n], ptoks):
                    raw.append((start, start + n, topic, schema))
        kept = []
        seen = set()
        for start, end, topic, schema in raw:
            covered = False
            for other_start, other_end, _topic, _schema in raw:
                if other_start <= start and end <= other_end and (end - start) < (other_end - other_start):
                    covered = True
                    break
            if covered:
                continue
            key = (topic, schema)
            if key in seen:
                continue
            seen.add(key)
            kept.append(key)
        return kept


def posts_from_payload(payload):
    if isinstance(payload, list):
        items = payload
    elif isinstance(payload, dict) and isinstance(payload.get("posts"), list):
        items = payload["posts"]
    elif isinstance(payload, dict) and isinstance(payload.get("tweet"), dict):
        items = [payload["tweet"]]
    else:
        items = [payload]
    posts = []
    for item in items[:MAX_POSTS]:
        post = _post_from_item(item)
        if post is not None:
            posts.append(post)
    return posts


def extract_text(nlp, text):
    text = (text or "").strip()
    if not text:
        return []
    if len(text) > MAX_TEXT_CHARS:
        text = text[:MAX_TEXT_CHARS]
    doc = nlp(text)
    triples = []
    for sent in doc.sents:
        sent_text = sent.text.strip()
        if not sent_text:
            continue
        tokens = _token_cues(sent)
        places, times = _situation(sent)
        found = _reverb_triples(sent)
        pattern = "reverb"
        if not found:
            fallback = _fallback_triple(sent)
            found = [fallback] if fallback is not None else []
            pattern = "fallback"
        seen = set()
        for subject, relation, obj, span in found:
            key = (subject.lower(), relation.lower(), obj.lower())
            if key in seen or (not subject and not obj):
                continue
            seen.add(key)
            negated, genuine = _fact_flags(sent, span)
            triples.append(Triple(
                sentence=sent_text,
                subject=subject,
                relation=relation,
                object=obj,
                negated=negated,
                genuine=genuine,
                tokens=tokens,
                places=places,
                times=times,
                pattern=pattern,
            ))
    return triples


def encode_triple(triple):
    payload = {
        "v": ENCODED_VERSION,
        "sentence": triple.sentence,
        "subject": triple.subject,
        "relation": triple.relation,
        "object": triple.object,
        "negated": bool(triple.negated),
        "genuine": triple.genuine,
        "tokens": triple.tokens,
        "places": list(triple.places),
        "times": list(triple.times),
        "pattern": triple.pattern,
    }
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def decode_triple(encoded):
    if not encoded or not encoded.startswith('{"v":1,'):
        return None
    try:
        payload = json.loads(encoded)
    except json.JSONDecodeError:
        return None
    if payload.get("v") != ENCODED_VERSION:
        return None
    sentence = payload.get("sentence") or ""
    genuine = payload.get("genuine")
    if genuine is not None and not isinstance(genuine, bool):
        genuine = None
    return Triple(
        sentence=sentence,
        subject=payload.get("subject") or "",
        relation=payload.get("relation") or "",
        object=payload.get("object") or "",
        negated=bool(payload.get("negated")),
        genuine=genuine,
        tokens=payload.get("tokens") if isinstance(payload.get("tokens"), list) else [],
        places=_string_list(payload.get("places")),
        times=_string_list(payload.get("times")),
        pattern=payload.get("pattern") if isinstance(payload.get("pattern"), str) else "",
    )


def apply_control_lexicon(store, lexicon_box, payload):
    """Install a vocabulary snapshot only after its reindex commits.

    ``store.reindex`` is expected to raise if the write does not commit. The
    in-memory lexicon stays on the previous snapshot in that case, and the
    caller must not treat the control message as applied.
    """
    updated = Lexicon.from_control(payload)
    linked = store.reindex(updated)
    lexicon_box[0] = updated
    return linked


def extract_phrase(triple):
    parts = [triple.subject, triple.relation, triple.object]
    return " ".join(part for part in parts if part).lower().strip()


def _entries_from_maps(lexicon, phrase_mapping):
    if not isinstance(lexicon, dict):
        lexicon = {}
    if not isinstance(phrase_mapping, dict):
        phrase_mapping = {}
    schemas = {}
    for topic, labels in lexicon.items():
        name = _label(topic)
        if not name:
            continue
        values = _string_list(labels)
        schemas[name] = _label(values[0]) if values else "topic"
    entries = []
    seen = set()
    for phrase, topics in phrase_mapping.items():
        normalized = _normalize(phrase)
        if not normalized:
            continue
        for topic in _string_list(topics):
            name = _label(topic)
            if not name:
                continue
            schema = schemas.get(name) or "topic"
            key = (normalized, name, schema)
            if key in seen:
                continue
            seen.add(key)
            entries.append(key)
    entries.sort(key=lambda item: (len(item[0].split()), len(item[0])), reverse=True)
    return entries


def _string_list(value):
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        return [item for item in value if isinstance(item, str)]
    return []


def _label(value):
    if not isinstance(value, str):
        return ""
    return value.strip().lower()


def _normalize(text):
    text = text.lower().replace("_", " ").replace("-", " ")
    text = _NON_WORD.sub(" ", text)
    return _SPACE.sub(" ", text).strip()


def stem(word):
    if len(word) > 5 and word.endswith("ing"):
        base = word[:-3]
        if len(base) > 2 and base[-1] == base[-2]:
            base = base[:-1]
        return base
    if len(word) > 4 and word.endswith("ed"):
        return word[:-2]
    if len(word) > 4 and word.endswith("es"):
        return word[:-2]
    if len(word) > 3 and word.endswith("s") and not word.endswith("ss"):
        return word[:-1]
    return word


def _tokens_match(text_tokens, phrase_tokens):
    if len(text_tokens) != len(phrase_tokens):
        return False
    if len(phrase_tokens) == 1:
        return text_tokens[0] == phrase_tokens[0] or stem(text_tokens[0]) == stem(phrase_tokens[0])
    if text_tokens[:-1] != phrase_tokens[:-1]:
        return False
    return text_tokens[-1] == phrase_tokens[-1] or stem(text_tokens[-1]) == stem(phrase_tokens[-1])


def _post_from_item(item):
    if isinstance(item, str):
        text = item.strip()
        if not text:
            return None
        return Post(text=text[:MAX_TEXT_CHARS], source_uri=_digest_uri(text), created_at=None)
    if not isinstance(item, dict):
        return None
    raw = item.get("full_text")
    if not isinstance(raw, str) or not raw.strip():
        raw = item.get("text")
    if not isinstance(raw, str) or not raw.strip():
        return None
    text = raw.strip()[:MAX_TEXT_CHARS]
    source = item.get("source_uri") or item.get("uri")
    if isinstance(source, str) and source.strip():
        source_uri = source.strip()[:2000]
    else:
        source_uri = _twitter_uri(item) or _digest_uri(text)
    created = _created_at(item.get("created_at"))
    return Post(text=text, source_uri=source_uri, created_at=created)


def _twitter_uri(item):
    status_id = item.get("id_str")
    if status_id is None and item.get("id") is not None:
        status_id = item.get("id")
    if status_id is None:
        return None
    status_id = str(status_id).strip()
    if not status_id or not re.fullmatch(r"[A-Za-z0-9_]+", status_id):
        return None
    user = item.get("user") if isinstance(item.get("user"), dict) else {}
    screen = user.get("screen_name")
    if isinstance(screen, str) and re.fullmatch(r"[A-Za-z0-9_]{1,50}", screen):
        return "https://twitter.com/%s/status/%s" % (screen, status_id)
    return "https://twitter.com/i/status/%s" % status_id


def _digest_uri(text):
    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]
    return "cispaces:text:%s" % digest


def _created_at(value):
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip()
    try:
        parsed = parsedate_to_datetime(text)
    except (TypeError, ValueError, IndexError, OverflowError):
        parsed = None
    if parsed is not None:
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def _reverb_triples(sent):
    """Verb-mediated triples.

    The relation is the longest of V, V+preposition, and V+W*+preposition,
    which is the ReVerb relation shape: a verb, an optional particle or
    adverb, then words only when a preposition closes the relation. Each
    verb in the sentence can yield its own triple. Negation inside the
    word sequence blocks that longer pattern so "is no unrest in" stays
    "is" plus the negated argument.
    """
    content = [token for token in sent if not token.is_space and token.pos_ not in ("PUNCT", "CCONJ")]
    triples = []
    index = 0
    while index < len(content):
        verb = content[index]
        if not _is_relation_verb(verb):
            index += 1
            continue
        end = index + 1
        while end < len(content) and content[end].head == verb and content[end].dep_ in ("prt", "advmod"):
            end += 1
        short_end = end
        scan = end
        while scan < len(content) and _relation_word(content[scan]):
            scan += 1
        if (
            scan > end
            and scan < len(content)
            and content[scan].pos_ == "ADP"
            and content[scan].dep_ in _PREP
            and (content[scan].head == verb or content[scan].head in content[end:scan])
        ):
            end = scan + 1
        elif end < len(content) and content[end].pos_ == "ADP" and content[end].dep_ in _PREP and content[end].head == verb:
            end += 1
        spans = [content[index:end]]
        if end > short_end:
            spans.insert(0, content[index:short_end])
        for relation_tokens in spans:
            built = _triple_from_relation(verb, relation_tokens)
            if built is not None:
                triples.append(built)
        index = end if end > index else index + 1
    return triples


def _is_relation_verb(token):
    if token.pos_ not in ("VERB", "AUX"):
        return False
    if token.dep_ in _SKIP_PRED and token.head != token and token.head.pos_ in ("VERB", "AUX"):
        return False
    return True


def _relation_word(token):
    if token.pos_ not in _REL_W:
        return False
    if token.dep_ == "neg" or token.lower_ in _NEGATION:
        return False
    return True


def _triple_from_relation(verb, relation_tokens):
    auxpass = _child(verb, ("auxpass",))
    object_token = _child(verb, _OBJ)
    if object_token is not None and any(object_token.i == token.i for token in relation_tokens):
        object_token = None
    if auxpass is not None and object_token is None:
        relation_tokens = [auxpass]
        object_tokens = [verb]
    else:
        object_tokens = _argument_tokens(object_token)
        if not object_tokens:
            prep = relation_tokens[-1] if relation_tokens and relation_tokens[-1].pos_ == "ADP" else None
            if prep is None:
                prep = _prep_of(verb)
                if prep is not None and prep not in relation_tokens:
                    relation_tokens = list(relation_tokens) + [prep]
            pobj = _child(prep, ("pobj",)) if prep is not None else None
            object_tokens = _argument_tokens(pobj)
    subject_token = _subject_of(verb)
    expl = _child(verb, ("expl",))
    if subject_token is None and expl is not None and object_token is not None and object_token.dep_ in ("attr", "acomp"):
        subject_tokens = _argument_tokens(object_token)
        object_tokens = []
    else:
        subject_tokens = _argument_tokens(subject_token)
        if not subject_tokens:
            subject_tokens = _left_nominal_tokens(verb)
    subject = _clean(" ".join(token.text for token in subject_tokens))
    relation = _clean(" ".join(token.text for token in relation_tokens)).lower()
    obj = _clean(" ".join(token.text for token in object_tokens))
    if not subject and not obj:
        return None
    span = subject_tokens + relation_tokens + object_tokens
    return (subject, relation, obj, span)


def _subject_of(verb):
    if _child(verb, ("expl",)) is not None:
        return None
    subject = _child(verb, _SUBJ)
    current = verb
    while subject is None and current.dep_ == "conj" and current.head != current:
        current = current.head
        subject = _child(current, _SUBJ)
    return subject


def _fallback_triple(sent):
    root = sent.root
    prep = _child(root, _PREP)
    if root.pos_ in ("NOUN", "PROPN", "ADJ") and prep is not None:
        pobj = _child(prep, ("pobj",))
        subject_tokens = _argument_tokens(root)
        object_tokens = _argument_tokens(pobj)
        subject = _clean(" ".join(token.text for token in subject_tokens))
        obj = _clean(" ".join(token.text for token in object_tokens))
        if subject and obj:
            return (subject, prep.text.lower(), obj, subject_tokens + [prep] + object_tokens)
    chunks = list(sent.noun_chunks)
    if len(chunks) >= 2:
        relation = root.text.lower() if root.pos_ in ("VERB", "AUX", "ADP") else "mentions"
        return (
            _clean(chunks[0].text),
            _clean(relation),
            _clean(chunks[-1].text),
            list(chunks[0]) + list(chunks[-1]),
        )
    if chunks:
        return (_clean(chunks[0].text), "mentions", "", list(chunks[0]))
    text = _clean(sent.text)
    if text:
        return (text, "states", "", [token for token in sent if not token.is_space])
    return None


def _child(token, deps):
    if token is None:
        return None
    for child in token.children:
        if child.dep_ in deps:
            return child
    return None


def _prep_of(token):
    return _child(token, _PREP)


def _argument_tokens(token):
    if token is None:
        return []
    if token.pos_ in ("NOUN", "PROPN", "PRON", "ADJ", "NUM", "DET"):
        for chunk in token.doc.noun_chunks:
            if chunk.start <= token.i < chunk.end:
                return [item for item in chunk if not item.is_space]
    parts = [left for left in token.lefts if left.dep_ in ("compound", "amod", "nummod", "flat", "nmod", "poss", "advmod", "det")]
    parts.append(token)
    parts.sort(key=lambda item: item.i)
    return parts


def _left_nominal_tokens(pred):
    tokens = [
        token for token in pred.sent
        if token.i < pred.i and token.pos_ in _LEFT_NOMINAL and token.dep_ not in ("pobj", "dobj", "obj")
    ]
    return tokens[:6]


def _fact_flags(sent, span):
    """Negation and genuine cues that attach to this fact, not the whole sentence."""
    indexes = {token.i for token in span}
    negated = False
    for token in sent:
        in_span = token.i in indexes or (token.head is not None and token.head.i in indexes)
        if not in_span:
            continue
        if token.dep_ == "neg" or token.lower_ in _NEGATION:
            negated = True
            break
    text = " ".join(token.text for token in span)
    genuine = None
    if _FALSE_CUES.search(text):
        genuine = False
    elif _TRUE_CUES.search(text):
        genuine = True
    return negated, genuine


def _situation(sent):
    places = []
    times = []
    seen_places = set()
    seen_times = set()

    def add_place(text):
        cleaned = _clean(text)
        key = cleaned.lower()
        if cleaned and key not in seen_places:
            seen_places.add(key)
            places.append(cleaned)

    def add_time(text):
        cleaned = _clean(text)
        key = cleaned.lower()
        if cleaned and key not in seen_times:
            seen_times.add(key)
            times.append(cleaned)

    for ent in sent.ents:
        if ent.label_ in _PLACE_ENT:
            add_place(ent.text)
        elif ent.label_ in _TIME_ENT:
            add_time(ent.text)
    for token in sent:
        if token.pos_ != "ADP":
            continue
        if token.lower_ not in _PLACE_PREP and token.lower_ != "on":
            continue
        pobj = _child(token, ("pobj",))
        if pobj is None:
            continue
        if pobj.ent_type_ in _TIME_ENT or any(item.ent_type_ in _TIME_ENT for item in pobj.subtree):
            add_time(_phrase_text(pobj))
            continue
        if token.lower_ not in _PLACE_PREP:
            continue
        if pobj.ent_type_ in _PLACE_ENT or pobj.lemma_ in _PLACE_LEMMA:
            add_place(_phrase_text(pobj))
    return places, times


def _phrase_text(token):
    return " ".join(item.text for item in _argument_tokens(token))


def _clean(text):
    if not text:
        return ""
    text = _SPACE.sub(" ", text).strip(" \t\r\n.,;:!?\"'`")
    return _LEADING_NEG.sub("", text).strip()


def _token_cues(sent):
    cues = []
    for token in sent:
        if token.is_space:
            continue
        cues.append({
            "t": token.text,
            "pos": token.pos_,
            "ent": token.ent_type_ or "",
        })
        if len(cues) >= 80:
            break
    return cues
