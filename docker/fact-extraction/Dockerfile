# Fact extraction for the FEWS vocabulary and tweet path.
# Stanford CoreNLP 3.7.0 (release 2016-10-31) is downloaded at build.
# CoreNLP 3.8.0, 3.9.1, and 4.6.0 are not installed.
# spaCy remains available for the stand-in unit tests. The running process
# uses CISPACES_FACT_ENGINE=stanford.
FROM python:3.11-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    STANFORD_TAGGER_DIR=/opt/stanford/stanford-postagger-full-2016-10-31 \
    STANFORD_PARSER_DIR=/opt/stanford/stanford-parser-full-2016-10-31 \
    STANFORD_MODEL_JAR=/opt/stanford/stanford-parser-full-2016-10-31/stanford-english-corenlp-2016-10-31-models.jar \
    CISPACES_FACT_ENGINE=stanford \
    NLTK_DATA=/opt/stanford/nltk_data

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl unzip openjdk-17-jre-headless \
    && pip install --no-cache-dir \
        'pika==1.3.2' \
        'psycopg2-binary==2.9.10' \
        'nltk==3.8.1' \
        'setuptools==70.3.0' \
        'beautifulsoup4==4.12.3' \
        'scipy==1.13.1' \
        'scikit-learn==1.5.2' \
        'sklearn-crfsuite==0.5.0' \
        'spacy==3.8.7' \
        'click>=8.1,<9' \
    && pip install --no-cache-dir --no-deps 'soton_corenlppy==1.0.0' \
    && pip install --no-cache-dir \
        'https://github.com/explosion/spacy-models/releases/download/en_core_web_sm-3.8.0/en_core_web_sm-3.8.0-py3-none-any.whl' \
    && python -c 'import en_core_web_sm, spacy; spacy.load("en_core_web_sm")' \
    && python -c 'import nltk; nltk.download("stopwords", download_dir="/opt/stanford/nltk_data"); nltk.download("names", download_dir="/opt/stanford/nltk_data")' \
    && rm -rf /var/lib/apt/lists/*

# The Stanford downloads are their own layer so a failed download or a changed
# archive layout is reported on its own line and does not redo the pip install.
RUN mkdir -p /tmp/stanford \
    && curl -fsSL -o /tmp/stanford/postagger.zip \
        'https://web.archive.org/web/20170514005357id_/https://nlp.stanford.edu/software/stanford-postagger-full-2016-10-31.zip' \
    && curl -fsSL -o /tmp/stanford/parser.zip \
        'https://web.archive.org/web/20170516192721id_/https://nlp.stanford.edu/software/stanford-parser-full-2016-10-31.zip' \
    && curl -fsSL -o /tmp/stanford/stanford-english-corenlp-2016-10-31-models.jar \
        'https://repo1.maven.org/maven2/edu/stanford/nlp/stanford-corenlp/3.7.0/stanford-corenlp-3.7.0-models-english.jar' \
    && ls -l /tmp/stanford \
    && unzip -q /tmp/stanford/postagger.zip -d /opt/stanford \
    && unzip -q /tmp/stanford/parser.zip -d /opt/stanford \
    && mv /tmp/stanford/stanford-english-corenlp-2016-10-31-models.jar \
        /opt/stanford/stanford-parser-full-2016-10-31/stanford-english-corenlp-2016-10-31-models.jar \
    && rm -rf /tmp/stanford \
    && ls /opt/stanford/stanford-postagger-full-2016-10-31 /opt/stanford/stanford-parser-full-2016-10-31

# The 2016-10-31 parser archive ships stanford-parser.jar. Only its models,
# sources, and javadoc jars carry 3.7.0 in the file name.
RUN for jar in \
        /opt/stanford/stanford-postagger-full-2016-10-31/stanford-postagger-3.7.0.jar \
        /opt/stanford/stanford-parser-full-2016-10-31/stanford-parser.jar \
        /opt/stanford/stanford-parser-full-2016-10-31/stanford-parser-3.7.0-models.jar; do \
        if [ ! -f "$jar" ]; then echo "missing expected Stanford jar: $jar" >&2; exit 1; fi; \
    done \
    && useradd --system --home-dir /opt/fact-extraction --shell /usr/sbin/nologin factextract

WORKDIR /opt/fact-extraction
COPY extractor.py store.py scenario.py inbox.py fact_extraction_bus.py \
    stanford_engine.py fact_extraction_patterns_regex.py \
    test_extract.py test_stanford_openie.py /opt/fact-extraction/
COPY openiepy /opt/fact-extraction/openiepy
COPY lexicopy /opt/fact-extraction/lexicopy
COPY sit_assesspy /opt/fact-extraction/sit_assesspy
COPY fact_extraction_app.ini.template /opt/fact-extraction/fact_extraction_app.ini.template
COPY entrypoint.sh /opt/fact-extraction/docker-entrypoint.sh
RUN CISPACES_FACT_ENGINE=spacy python -m unittest test_extract.py \
    && python -m unittest test_stanford_openie.py \
    && mkdir -p /var/fact-inbox/done \
    && chmod 1777 /var/fact-inbox /var/fact-inbox/done \
    && chmod 755 /opt/fact-extraction/docker-entrypoint.sh \
    && chown -R factextract:factextract /opt/fact-extraction /opt/stanford

USER factextract
ENTRYPOINT ["/opt/fact-extraction/docker-entrypoint.sh"]
