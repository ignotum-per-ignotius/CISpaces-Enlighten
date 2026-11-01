# Fact extraction for the FEWS vocabulary and tweet path.
# spaCy's small English model is downloaded at build time. It is not committed.
# The 2017 Southampton wheels and the Stanford parser zips are not used.
FROM python:3.11-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && pip install --no-cache-dir \
        'pika==1.3.2' \
        'psycopg2-binary==2.9.10' \
        'spacy==3.8.7' \
        'click>=8.1,<9' \
    && pip install --no-cache-dir \
        'https://github.com/explosion/spacy-models/releases/download/en_core_web_sm-3.8.0/en_core_web_sm-3.8.0-py3-none-any.whl' \
    && python -c 'import en_core_web_sm, spacy; spacy.load("en_core_web_sm")' \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --home-dir /opt/fact-extraction --shell /usr/sbin/nologin factextract

WORKDIR /opt/fact-extraction
COPY extractor.py store.py scenario.py inbox.py fact_extraction_bus.py test_extract.py /opt/fact-extraction/
COPY fact_extraction_app.ini.template /opt/fact-extraction/fact_extraction_app.ini.template
COPY entrypoint.sh /opt/fact-extraction/docker-entrypoint.sh
RUN python -m unittest test_extract.py \
    && mkdir -p /var/fact-inbox/done \
    && chmod 1777 /var/fact-inbox /var/fact-inbox/done \
    && chmod 755 /opt/fact-extraction/docker-entrypoint.sh \
    && chown -R factextract:factextract /opt/fact-extraction

USER factextract
ENTRYPOINT ["/opt/fact-extraction/docker-entrypoint.sh"]
