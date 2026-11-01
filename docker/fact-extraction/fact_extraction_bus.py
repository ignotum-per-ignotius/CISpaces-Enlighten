#!/usr/bin/env python3
"""Fact-extraction service for the FEWS fanout exchange.

FEWS publishes vocabulary control messages to ``cispaces_exchange`` and reads
facts from the ``intelanalysis`` database. A text message on the same exchange,
or a text file dropped in the inbox, is split into verb-mediated
subject-relation-object facts and stored in the phase-1 tables. Place and
time groups go in ``factextract.phase1_situation``. The same facts are written
as one scenario graph.

The broker is told to auto-ack, which is the 2017 consumer's ``no_ack``
setting. FEWS publishes without publisher confirms and does not wait for a
consumer ack. A vocabulary update is applied only after its reindex commits.
Message bodies are not written to the log.
"""

import configparser
import json
import os
import signal
import sys
import threading
import time

import pika

from extractor import apply_control_lexicon, load_nlp, message_kind, posts_from_payload
from extractor import Lexicon
from inbox import INBOX_DIR, poll_forever, publish_message
from scenario import publish_scenario
from store import FactStore

INI_PATH = os.environ.get(
    "FACT_EXTRACTION_INI", "/opt/fact-extraction/fact_extraction_app.ini"
)
READY_PATH = "/tmp/fact-extraction.ready"
COUNT_PATH = "/tmp/fact-extraction.messages"


def log(message):
    print("# " + message, flush=True)


def read_config():
    parser = configparser.RawConfigParser()
    if not parser.read(INI_PATH):
        log("fact-extraction config is missing")
        sys.exit(1)
    return parser


def wait_for_database(store):
    last = "error"
    for _ in range(60):
        try:
            conn = store.connect()
            try:
                cur = conn.cursor()
                cur.execute("SELECT COUNT(*) FROM factextract.vocab_topic")
                topics = cur.fetchone()[0]
                cur.execute("SELECT COUNT(*) FROM factextract.phase1_ext_db_item")
                items = cur.fetchone()[0]
            finally:
                conn.close()
            log(
                "fact-extraction database ready vocab_topic=%s phase1_ext_db_item=%s"
                % (topics, items)
            )
            return
        except Exception as exc:
            last = type(exc).__name__
            time.sleep(2)
    log("fact-extraction could not reach PostgreSQL (%s)" % last)
    sys.exit(1)


def remember_count(count):
    tmp = COUNT_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as handle:
        handle.write("%s\n" % count)
    os.replace(tmp, COUNT_PATH)


def handle_payload(store, lexicon_box, nlp, payload):
    kind = message_kind(payload)
    if kind == "vocabulary":
        linked = apply_control_lexicon(store, lexicon_box, payload)
        log("accepted vocabulary links=%s" % linked)
        return
    if kind == "text":
        posts = posts_from_payload(payload)
        if not posts:
            log("rejected fact-extraction message: no text")
            return
        written, graph = store.ingest(posts, lexicon_box[0], nlp)
        workbench = "skipped"
        nodes = 0
        if graph is not None:
            nodes = len(graph["nodes"])
            workbench = publish_scenario(graph)
        log(
            "stored facts extracts=%s posts=%s scenario_nodes=%s workbench=%s"
            % (written, len(posts), nodes, workbench)
        )
        return
    log("rejected fact-extraction message: unknown shape")


def consume(cfg, store, lexicon_box, nlp):
    credentials = pika.PlainCredentials(
        cfg.get("rabbitmq", "rmq_username"),
        cfg.get("rabbitmq", "rmq_password"),
    )
    params = pika.ConnectionParameters(
        host=cfg.get("rabbitmq", "rmq_host"),
        port=cfg.getint("rabbitmq", "rmq_port"),
        credentials=credentials,
        connection_attempts=5,
        retry_delay=2,
        heartbeat=60,
    )
    connection = pika.BlockingConnection(params)
    channel = connection.channel()
    exchange = cfg.get("rabbitmq", "rmq_exchange_name")
    exchange_type = cfg.get("rabbitmq", "rmq_exchange_type")
    routing = cfg.get("rabbitmq", "rmq_routing")
    exclusive = cfg.getboolean("rabbitmq", "rmq_exclusive_queue")
    auto_delete = cfg.getboolean("rabbitmq", "rmq_auto_delete_queue")
    channel.exchange_declare(
        exchange=exchange,
        exchange_type=exchange_type,
        durable=False,
        auto_delete=False,
    )
    declared = channel.queue_declare(
        queue=cfg.get("rabbitmq", "rmq_queue_name"),
        exclusive=exclusive,
        auto_delete=auto_delete,
    )
    channel.queue_bind(
        queue=declared.method.queue,
        exchange=exchange,
        routing_key=routing,
    )
    count = 0

    def on_message(_channel, _method, properties, body):
        nonlocal count
        count += 1
        remember_count(count)
        content_type = getattr(properties, "content_type", None)
        if content_type not in (None, "", "application/json"):
            log("rejected fact-extraction message: unsupported content type count=%s" % count)
            return
        try:
            payload = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError, TypeError):
            log("rejected fact-extraction message: invalid json count=%s" % count)
            return
        try:
            handle_payload(store, lexicon_box, nlp, payload)
        except Exception as exc:
            log("fact-extraction message failed count=%s error=%s" % (count, type(exc).__name__))

    channel.basic_consume(
        queue=declared.method.queue,
        on_message_callback=on_message,
        auto_ack=True,
    )

    def stop(_signum, _frame):
        if connection.is_open:
            connection.add_callback_threadsafe(channel.stop_consuming)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    with open(READY_PATH, "w", encoding="utf-8") as handle:
        handle.write("ready\n")
    inbox = threading.Thread(
        target=poll_forever,
        args=(cfg, publish_message),
        name="fact-inbox",
        daemon=True,
    )
    inbox.start()
    log("fact-extraction consuming exchange %s inbox %s" % (exchange, INBOX_DIR))
    try:
        channel.start_consuming()
    finally:
        if connection.is_open:
            connection.close()


def main():
    log("fact-extraction loading %s" % "en_core_web_sm")
    cfg = read_config()
    for section, key in (
        ("situation-assessment", "db_pass"),
        ("rabbitmq", "rmq_password"),
    ):
        if cfg.get(section, key).startswith("__CISPACES_"):
            log("fact-extraction config still has a placeholder")
            sys.exit(1)
    store = FactStore(cfg)
    wait_for_database(store)
    lexicon_box = [store.load_lexicon()]
    nlp = load_nlp()
    last = "error"
    for _ in range(30):
        try:
            consume(cfg, store, lexicon_box, nlp)
            return
        except Exception as exc:
            last = type(exc).__name__
            time.sleep(2)
    log("fact-extraction could not reach RabbitMQ (%s)" % last)
    sys.exit(1)


if __name__ == "__main__":
    main()
