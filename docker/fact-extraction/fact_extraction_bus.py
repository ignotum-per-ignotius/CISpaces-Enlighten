#!/usr/bin/env python3
"""Fact-extraction bus client.

FEWS publishes vocabulary control messages to the fanout exchange
cispaces_exchange and reads PostgreSQL database intelanalysis. The 2017
fact-extraction app loaded its dependency parser before opening this
consumer, and the wheels that import requires are not published. This
process keeps that consumer contract: same database, same exchange, an
exclusive auto-delete queue, and a process that stays up.
"""

import configparser
import os
import signal
import sys
import time

import pika
import psycopg2

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


def wait_for_database(cfg):
    last = "error"
    for _ in range(60):
        try:
            conn = psycopg2.connect(
                host=cfg.get("situation-assessment", "db_host"),
                port=cfg.getint("situation-assessment", "db_port"),
                dbname=cfg.get("situation-assessment", "db_name"),
                user=cfg.get("situation-assessment", "db_user"),
                password=cfg.get("situation-assessment", "db_pass"),
                connect_timeout=5,
            )
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


def consume(cfg):
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

    def on_message(_channel, _method, _properties, _body):
        nonlocal count
        count += 1
        tmp = COUNT_PATH + ".tmp"
        with open(tmp, "w", encoding="utf-8") as handle:
            handle.write("%s\n" % count)
        os.replace(tmp, COUNT_PATH)
        # The body is a vocabulary control message. Do not log it.
        log("received fact-extraction control message count=%s" % count)

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
    log("fact-extraction consuming exchange %s" % exchange)
    try:
        channel.start_consuming()
    finally:
        if connection.is_open:
            connection.close()


def main():
    log(
        "fact-extraction NLP libraries from the 2017 installer are not in this image; "
        "the consumer and database client are running"
    )
    cfg = read_config()
    for section, key in (
        ("situation-assessment", "db_pass"),
        ("rabbitmq", "rmq_password"),
    ):
        if cfg.get(section, key).startswith("__CISPACES_"):
            log("fact-extraction config still has a placeholder")
            sys.exit(1)
    wait_for_database(cfg)
    last = "error"
    for _ in range(30):
        try:
            consume(cfg)
            return
        except Exception as exc:
            last = type(exc).__name__
            time.sleep(2)
    log("fact-extraction could not reach RabbitMQ (%s)" % last)
    sys.exit(1)


if __name__ == "__main__":
    main()
