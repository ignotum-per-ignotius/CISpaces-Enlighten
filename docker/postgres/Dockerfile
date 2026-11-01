# PostgreSQL 11 with PostGIS. FEWS ships JDBC 42.1.4, which authenticates
# with md5 and predates SCRAM. The scenario dump is the pinned fact-extraction
# release; vocab SQL is mounted from FEWS/extra at runtime.
# The PostGIS 11 image cannot apt-get (its Debian and PGDG indexes 404), so
# the dump is fetched in a current image and copied in.

FROM python:3.11-slim-bookworm AS scenario

ARG SCENARIO_URL=https://raw.githubusercontent.com/CISpaces/factextraction/186d23022b33308a27a3c6b97e1a24e941663ab5/corpus/database-phase1/scenario-18-dec-2017.sql
ARG SCENARIO_SHA256=a402f7e09a35f7053fadfef4a9cc8481df59c9a20be8e3662b5cca665fd3e6ea

RUN python -c "import urllib.request; urllib.request.urlretrieve('${SCENARIO_URL}', '/tmp/scenario.sql')" \
    && echo "${SCENARIO_SHA256}  /tmp/scenario.sql" | sha256sum -c - \
    && sed -i \
        -e 's/^CREATE SCHEMA factextract;$/CREATE SCHEMA IF NOT EXISTS factextract;/' \
        -e '/^SET default_with_oids /d' \
        /tmp/scenario.sql

FROM postgis/postgis:11-3.3

COPY --from=scenario /tmp/scenario.sql /opt/cispaces/sql/scenario-18-dec-2017.sql
COPY entrypoint.sh /opt/cispaces/entrypoint.sh
RUN chmod 755 /opt/cispaces/entrypoint.sh

ENV PGDATA=/var/lib/postgresql/data
ENTRYPOINT ["/opt/cispaces/entrypoint.sh"]
