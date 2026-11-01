# Fact-extraction consumer FEWS publishes to.
# The pinned upstream tree is the engine install-fact-extraction.sh clones.
# Its 2017 NLP wheels are not published, so the running process is the bus
# client: PostgreSQL intelanalysis and fanout exchange cispaces_exchange.
FROM python:3.11-slim-bookworm

ARG FACTEXTRACTION_COMMIT=186d23022b33308a27a3c6b97e1a24e941663ab5

RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && pip install --no-cache-dir 'pika==1.3.2' 'psycopg2-binary==2.9.10' \
    && git init /opt/fact-extraction/upstream \
    && git -C /opt/fact-extraction/upstream remote add origin https://github.com/CISpaces/factextraction.git \
    && git -C /opt/fact-extraction/upstream fetch --depth 1 origin "${FACTEXTRACTION_COMMIT}" \
    && git -C /opt/fact-extraction/upstream checkout --detach FETCH_HEAD \
    && rm -f /opt/fact-extraction/upstream/config/fact_extraction_app/fact_extraction_app.ini \
    && rm -rf /opt/fact-extraction/upstream/.git \
    && if grep -R -n -E '^(db_pass|rmq_password)=' /opt/fact-extraction/upstream; then \
         echo "upstream tree still has a password assignment" >&2; \
         exit 1; \
       fi \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --home-dir /opt/fact-extraction --shell /usr/sbin/nologin factextract

COPY fact_extraction_app.ini.template /opt/fact-extraction/fact_extraction_app.ini.template
COPY fact_extraction_bus.py /opt/fact-extraction/fact_extraction_bus.py
COPY entrypoint.sh /opt/fact-extraction/docker-entrypoint.sh
RUN chmod 755 /opt/fact-extraction/docker-entrypoint.sh \
    && chown -R factextract:factextract /opt/fact-extraction

USER factextract
WORKDIR /opt/fact-extraction
ENTRYPOINT ["/opt/fact-extraction/docker-entrypoint.sh"]
