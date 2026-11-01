#!/bin/bash
# Create the intelanalysis database and load fact-extraction SQL.
# The role password is applied from a mode 600 file on stdin, not argv.

set +x
set -euo pipefail

export PGDATA="${PGDATA:-/var/lib/postgresql/data}"
export LANG=C
export LC_ALL=C
if [ -d /usr/lib/postgresql/11/bin ]; then
    export PATH="/usr/lib/postgresql/11/bin:${PATH}"
fi

if [ "$(id -u)" -eq 0 ]; then
    mkdir -p "$PGDATA" /opt/cispaces/sql
    chown postgres:postgres "$PGDATA"
    chmod 700 "$PGDATA"
    exec gosu postgres "$0" "$@"
fi

reject_secret() {
    set +x
    local name="$1"
    local value="${!name-}"
    if [ -z "$value" ]; then
        echo "# Set ${name} in the environment before starting. See config/secrets.env.example. The value is not printed."
        exit 1
    fi
    case "$value" in
        *\\*|*$'\n'*|*$'\r'*|*'$'*)
            echo "# ${name} contains a backslash, dollar sign, or newline. Choose another value. The value is not printed."
            exit 1
            ;;
    esac
}

set_role_password() {
    set +x
    local password_file sql_file
    password_file=$(mktemp)
    sql_file=$(mktemp)
    chmod 600 "$password_file" "$sql_file"
    printf '%s' "$CISPACES_POSTGRES_PASSWORD" > "$password_file"
    awk -v pwfile="$password_file" '
        BEGIN {
            getline password < pwfile
            close(pwfile)
            escaped = ""
            for (i = 1; i <= length(password); i++) {
                c = substr(password, i, 1)
                if (c == "\047") {
                    escaped = escaped "\047\047"
                } else {
                    escaped = escaped c
                }
            }
            printf "ALTER USER intelanalysis WITH PASSWORD \047%s\047;\n", escaped
        }
    ' > "$sql_file"
    rm -f "$password_file"
    if ! psql -d intelanalysis -v ON_ERROR_STOP=1 -q < "$sql_file" >/dev/null 2>&1; then
        rm -f "$sql_file"
        echo "# Failed to set the PostgreSQL role password. The value is not printed."
        return 1
    fi
    rm -f "$sql_file"
}

load_sql() {
    local name="$1"
    local path="/opt/cispaces/sql/${name}"
    if [ ! -s "$path" ]; then
        echo "# Missing ${path}"
        return 1
    fi
    echo "# Loading ${name}"
    psql -d intelanalysis -v ON_ERROR_STOP=1 -q -f "$path" >/dev/null
}

seed_database() {
    if psql -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname = 'intelanalysis'" | grep -q 1; then
        psql -d postgres -v ON_ERROR_STOP=1 -q -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'intelanalysis' AND pid <> pg_backend_pid();" >/dev/null || true
    else
        psql -d postgres -v ON_ERROR_STOP=1 -q -c "CREATE ROLE intelanalysis LOGIN" >/dev/null
    fi
    psql -d postgres -v ON_ERROR_STOP=1 -q -c "DROP DATABASE IF EXISTS intelanalysis" >/dev/null
    psql -d postgres -v ON_ERROR_STOP=1 -q -c "CREATE DATABASE intelanalysis OWNER intelanalysis" >/dev/null
    load_sql create_vocab_tables.sql
    load_sql intelanalysis_factextract_vocab_topic.sql
    load_sql intelanalysis_factextract_vocab_keyword.sql
    load_sql scenario-18-dec-2017.sql
    psql -d intelanalysis -v ON_ERROR_STOP=1 -q >/dev/null <<'SQL'
CREATE TABLE IF NOT EXISTS factextract.phase1_extractor_topic (
    topic_key integer PRIMARY KEY
        REFERENCES factextract.phase1_ext_db_topic(topic_key) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS factextract.phase1_situation (
    situation_key serial PRIMARY KEY,
    extract_uri text NOT NULL,
    source_uri text NOT NULL,
    place text NOT NULL DEFAULT '',
    time_label text NOT NULL DEFAULT '',
    UNIQUE (extract_uri, place, time_label)
);
CREATE TABLE IF NOT EXISTS factextract.phase1_scenario (
    scenario_key text PRIMARY KEY,
    title text,
    graph text NOT NULL,
    updated_time timestamp with time zone DEFAULT now()
);
GRANT ALL ON SCHEMA factextract TO intelanalysis;
GRANT ALL ON ALL TABLES IN SCHEMA factextract TO intelanalysis;
GRANT ALL ON ALL SEQUENCES IN SCHEMA factextract TO intelanalysis;
SELECT setval(
    'factextract.vocab_topic_topic_id_seq',
    GREATEST(COALESCE((SELECT MAX(topic_id) FROM factextract.vocab_topic), 1), 1)
);
SELECT setval(
    'factextract.vocab_keyword_keyword_id_seq',
    GREATEST(COALESCE((SELECT MAX(keyword_id) FROM factextract.vocab_keyword), 1), 1)
);
SQL
}

stop_postgres() {
    if [ "${stopping:-0}" -eq 1 ]; then
        return 0
    fi
    stopping=1
    pg_ctl -D "$PGDATA" -m fast -w stop >/dev/null 2>&1 || true
}

reject_secret CISPACES_POSTGRES_PASSWORD

marker="${PGDATA}/cispaces.seeded"
stopping=0
trap stop_postgres EXIT
trap 'stop_postgres; exit 0' TERM INT

if [ ! -s "${PGDATA}/PG_VERSION" ]; then
    initdb -D "$PGDATA" --username=postgres --auth-local=trust --auth-host=md5 --encoding=UTF8 --locale=C >/dev/null
fi

if ! grep -q '^# cispaces-listen$' "${PGDATA}/postgresql.conf"; then
    printf '\n%s\n' \
        '# cispaces-listen' \
        "listen_addresses = '127.0.0.1,::1'" \
        'password_encryption = md5' \
        >> "${PGDATA}/postgresql.conf"
fi

cat > "${PGDATA}/pg_hba.conf" <<'EOF'
local   all             postgres                                trust
local   all             all                                     trust
host    all             all             127.0.0.1/32            md5
host    all             all             ::1/128                 md5
EOF
chmod 600 "${PGDATA}/pg_hba.conf"

pg_ctl -D "$PGDATA" -w start >/dev/null

if [ ! -f "$marker" ]; then
    seed_database
fi
set_role_password

if [ ! -f "$marker" ]; then
    printf '%s\n' seeded > "$marker"
    chmod 644 "$marker"
    echo "# Created the intelanalysis database"
else
    echo "# PostgreSQL is using the existing intelanalysis database"
fi

postmaster=$(sed -n '1p' "${PGDATA}/postmaster.pid")
while kill -0 "$postmaster" 2>/dev/null; do
    sleep 1
done
