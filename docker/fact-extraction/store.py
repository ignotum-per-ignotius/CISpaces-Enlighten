"""Write extractor output into the phase-1 fact tables FEWS already reads.

Rows this process creates are marked with an encoded JSON version. Vocabulary
refreshes re-link those rows. The 2017 scenario dump stays in place: its
``encoded`` values are not this JSON, so a lexicon update does not delete them.
"""

import psycopg2

from extractor import Lexicon, decode_triple, encode_triple, extract_phrase, extract_text


class FactStore:
    def __init__(self, cfg):
        self.cfg = cfg

    def connect(self):
        return psycopg2.connect(
            host=self.cfg.get("situation-assessment", "db_host"),
            port=self.cfg.getint("situation-assessment", "db_port"),
            dbname=self.cfg.get("situation-assessment", "db_name"),
            user=self.cfg.get("situation-assessment", "db_user"),
            password=self.cfg.get("situation-assessment", "db_pass"),
            connect_timeout=5,
        )

    def load_lexicon(self):
        conn = self.connect()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT topic.topic, topic.\"schema\", keyword.keyword "
                    "FROM factextract.vocab_topic AS topic "
                    "JOIN factextract.vocab_keyword AS keyword "
                    "ON topic.topic_id = keyword.topic_id"
                )
                rows = cur.fetchall()
        finally:
            conn.close()
        return Lexicon.from_rows(rows)

    def ingest(self, posts, lexicon, nlp):
        written = 0
        conn = self.connect()
        try:
            with conn.cursor() as cur:
                for post in posts:
                    triples = extract_text(nlp, post.text)
                    if not triples:
                        continue
                    _upsert_post(cur, post)
                    _delete_extracts(cur, post.source_uri)
                    for index, triple in enumerate(triples, start=1):
                        item_key = _insert_extract(cur, post.source_uri, index, triple)
                        _link_topics(cur, item_key, triple, lexicon)
                        written += 1
                _delete_orphan_topics(cur)
            conn.commit()
        finally:
            conn.close()
        return written

    def reindex(self, lexicon):
        linked = 0
        conn = self.connect()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT item_key, encoded FROM factextract.phase1_ext_db_item "
                    "WHERE encoded LIKE %s",
                    ('{"v":1,%',),
                )
                rows = cur.fetchall()
                item_keys = []
                parsed = []
                for item_key, encoded in rows:
                    triple = decode_triple(encoded)
                    if triple is None:
                        continue
                    item_keys.append(item_key)
                    parsed.append((item_key, triple))
                if item_keys:
                    cur.execute(
                        "DELETE FROM factextract.phase1_ext_db_topic_index WHERE item_key = ANY(%s)",
                        (item_keys,),
                    )
                for item_key, triple in parsed:
                    linked += _link_topics(cur, item_key, triple, lexicon)
                _delete_orphan_topics(cur)
            conn.commit()
        finally:
            conn.close()
        return linked


def _upsert_post(cur, post):
    cur.execute(
        "INSERT INTO factextract.phase1_post_db_item (source_uri, created_at, text) "
        "VALUES (%s, %s, %s) "
        "ON CONFLICT (source_uri) DO UPDATE SET "
        "created_at = EXCLUDED.created_at, text = EXCLUDED.text, updated_time = now()",
        (post.source_uri, post.created_at, post.text),
    )


def _delete_extracts(cur, source_uri):
    cur.execute(
        "DELETE FROM factextract.phase1_ext_db_topic_index AS topic_index "
        "USING factextract.phase1_ext_db_item AS item "
        "WHERE topic_index.item_key = item.item_key AND item.source_uri = %s",
        (source_uri,),
    )
    cur.execute(
        "DELETE FROM factextract.phase1_ext_db_item WHERE source_uri = %s",
        (source_uri,),
    )


def _insert_extract(cur, source_uri, index, triple):
    cur.execute(
        "INSERT INTO factextract.phase1_ext_db_item "
        "(extract_uri, source_uri, extract, encoded) "
        "VALUES (%s, %s, %s, %s) RETURNING item_key",
        (
            "%s#%s" % (source_uri, index),
            source_uri,
            extract_phrase(triple),
            encode_triple(triple),
        ),
    )
    return cur.fetchone()[0]


def _link_topics(cur, item_key, triple, lexicon):
    linked = 0
    seen = set()
    blob = " ".join((triple.sentence, triple.subject, triple.relation, triple.object))
    for topic, schema in lexicon.match(blob):
        negated = True if triple.negated else None
        key = (topic, schema, negated, triple.genuine)
        if key in seen:
            continue
        seen.add(key)
        topic_key = _topic_key(cur, topic, schema, negated, triple.genuine)
        cur.execute(
            "INSERT INTO factextract.phase1_ext_db_topic_index (item_key, topic_key) "
            "VALUES (%s, %s) ON CONFLICT (item_key, topic_key) DO NOTHING",
            (item_key, topic_key),
        )
        linked += 1
    return linked


def _topic_key(cur, topic, schema, negated, genuine):
    cur.execute(
        "SELECT topic_key FROM factextract.phase1_ext_db_topic "
        "WHERE topic = %s AND \"schema\" = %s "
        "AND negated IS NOT DISTINCT FROM %s "
        "AND genuine IS NOT DISTINCT FROM %s "
        "ORDER BY topic_key LIMIT 1",
        (topic, schema, negated, genuine),
    )
    row = cur.fetchone()
    if row is not None:
        return row[0]
    cur.execute(
        "INSERT INTO factextract.phase1_ext_db_topic (topic, \"schema\", negated, genuine) "
        "VALUES (%s, %s, %s, %s) RETURNING topic_key",
        (topic, schema, negated, genuine),
    )
    return cur.fetchone()[0]


def _delete_orphan_topics(cur):
    cur.execute(
        "DELETE FROM factextract.phase1_ext_db_topic AS topic "
        "WHERE NOT EXISTS ("
        "SELECT 1 FROM factextract.phase1_ext_db_topic_index AS topic_index "
        "WHERE topic_index.topic_key = topic.topic_key)"
    )
