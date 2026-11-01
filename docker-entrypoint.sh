#!/bin/bash
# Start the CISpaces analysis workbench.
# Derby and Tomcat only. PostgreSQL, RabbitMQ, and fact-extraction are not started.
# Passwords come from the environment and are written to mode 600 files.
# They are not placed on process command lines.

if [ "$(id -u)" -eq 0 ]; then
    echo "This script must NOT be run as root"
    exit 1
fi

# A traced shell must not print secret values.
set +x
set -euo pipefail

export CISPACES="${CISPACES:-/opt/cispaces}"
export CATALINA_HOME="${CATALINA_HOME:-${CISPACES}/tools/tomcat}"
export CISPACES_JWT_KEY_FILE="${CISPACES_JWT_KEY_FILE:-${CISPACES}/config/key.properties}"
export DERBY_OPTS="-Dderby.system.home=${CISPACES}/data/derby"

derby_pid=""
tomcat_pid=""
stopping=0

require_derby_password() {
    set +x
    if [ -z "${CISPACES_DERBY_PASSWORD:-}" ]; then
        echo "# Set CISPACES_DERBY_PASSWORD in the environment before starting. See config/secrets.env.example. The value is not printed."
        exit 1
    fi
    # Quoted pattern characters, so $* is not expanded as the positional parameters.
    if [[ "$CISPACES_DERBY_PASSWORD" == *\\* \
        || "$CISPACES_DERBY_PASSWORD" == *$'\n'* \
        || "$CISPACES_DERBY_PASSWORD" == *$'\r'* \
        || "$CISPACES_DERBY_PASSWORD" == *"'"* \
        || "$CISPACES_DERBY_PASSWORD" == *';'* \
        || "$CISPACES_DERBY_PASSWORD" == *'`'* \
        || "$CISPACES_DERBY_PASSWORD" == *'$'* ]]; then
        echo "# CISPACES_DERBY_PASSWORD cannot contain a quote, backtick, semicolon, dollar sign, backslash, or newline. Choose another value. The value is not printed."
        exit 1
    fi
}

write_context() {
    set +x
    local template="${CATALINA_HOME}/conf/context.xml.template"
    local dest="${CATALINA_HOME}/conf/context.xml"
    local password_file tmp escaped
    if [ ! -f "$template" ] || ! grep -q '__CISPACES_DERBY_PASSWORD__' "$template"; then
        echo "# Tomcat context template is missing the Derby placeholder. The value is not printed."
        exit 1
    fi
    escaped=$CISPACES_DERBY_PASSWORD
    escaped=${escaped//&/&amp;}
    escaped=${escaped//</&lt;}
    escaped=${escaped//>/&gt;}
    escaped=${escaped//\"/&quot;}
    escaped=${escaped//\'/&apos;}
    password_file=$(mktemp)
    chmod 600 "$password_file"
    printf '%s' "$escaped" > "$password_file"
    unset escaped
    tmp=$(mktemp)
    chmod 600 "$tmp"
    # index/substr, not gsub, so characters in the password stay literal.
    awk -v sentinel="__CISPACES_DERBY_PASSWORD__" -v pwfile="$password_file" '
        BEGIN {
            getline password < pwfile
            close(pwfile)
        }
        {
            rest = $0
            prefix = ""
            slen = length(sentinel)
            while ((i = index(rest, sentinel)) > 0) {
                prefix = prefix substr(rest, 1, i - 1) password
                rest = substr(rest, i + slen)
            }
            print prefix rest
        }
    ' "$template" > "$tmp"
    rm -f "$password_file"
    unset password_file
    mv "$tmp" "$dest"
    chmod 600 "$dest"
}

generate_jwt_key() {
    set +x
    local key_file="${CISPACES_JWT_KEY_FILE}"
    local dir jwt_key old_umask
    dir=$(dirname "$key_file")
    mkdir -p "$dir"
    chmod 700 "$dir"
    if [ -f "$key_file" ]; then
        chmod 600 "$key_file"
        echo "# Shared JWT key file already exists; leaving it in place"
        return 0
    fi
    jwt_key=$(od -An -N64 -tx1 /dev/urandom | tr -d ' \n')
    if [ "${#jwt_key}" -lt 32 ]; then
        echo "# Failed to generate a JWT key"
        unset jwt_key
        exit 1
    fi
    old_umask=$(umask)
    umask 077
    {
        echo "# Shared by every CISpaces service. Generated at install. Do not commit."
        printf 'keyString=%s\n' "$jwt_key"
    } > "$key_file"
    umask "$old_umask"
    unset jwt_key
    chmod 600 "$key_file"
    echo "# Wrote the shared JWT key file"
}

wait_for_derby() {
    local i
    for i in $(seq 1 60); do
        if "${CISPACES}/tools/derby/bin/NetworkServerControl" ping -h 127.0.0.1 >/dev/null 2>&1; then
            return 0
        fi
        if [ -n "$derby_pid" ] && ! kill -0 "$derby_pid" 2>/dev/null; then
            echo "# Derby failed to start"
            return 1
        fi
        sleep 1
    done
    echo "# Derby did not accept connections"
    return 1
}

# create=true makes the gaiandb directory as soon as ij connects, before the
# SQL import runs. The directory is not a finished seed. The marker is.
seed_marker() {
    printf '%s\n' "${CISPACES}/data/derby/gaiandb.seeded"
}

# Stop Derby before deleting. A still-running network server can rewrite the
# database files after rm and leave a partial directory behind.
discard_open_database() {
    local dbdir="$1"
    local i
    if [ -n "${derby_pid:-}" ] && kill -0 "$derby_pid" 2>/dev/null; then
        "${CISPACES}/tools/derby/bin/stopNetworkServer" -h 127.0.0.1 >/dev/null 2>&1 || true
        for i in 1 2 3 4 5 6 7 8 9 10; do
            if ! kill -0 "$derby_pid" 2>/dev/null; then
                break
            fi
            sleep 1
        done
        if kill -0 "$derby_pid" 2>/dev/null; then
            kill "$derby_pid" 2>/dev/null || true
        fi
        wait "$derby_pid" 2>/dev/null || true
        derby_pid=""
    fi
    rm -rf "$dbdir"
}

seed_derby() {
    set +x
    local dbdir="${CISPACES}/data/derby/gaiandb"
    local marker ij_sql ij_log sql_file marker_tmp pwfile
    marker=$(seed_marker)
    if [ -f "$marker" ] && [ -d "$dbdir" ]; then
        echo "# Derby database already present"
        return 0
    fi
    # An interrupted start, or a cleanup that raced the network server, leaves
    # this directory without the marker. Remove it so this start imports again.
    if [ -d "$dbdir" ] || [ -f "$marker" ]; then
        echo "# Removing an unfinished Derby database so the seed can be retried"
        rm -f "$marker"
        rm -rf "$dbdir"
    fi
    sql_file="${CISPACES}/config/create-cispaces-tables.sql"
    if [ ! -f "$sql_file" ]; then
        echo "# Missing ${sql_file}"
        exit 1
    fi
    ij_sql=$(mktemp)
    ij_log=$(mktemp)
    chmod 600 "$ij_sql" "$ij_log"
    # printf is a shell builtin. The password is not a command argument.
    {
        printf "connect 'jdbc:derby://127.0.0.1:1527/gaiandb;create=true;user=gaiandb;password=%s';\n" "$CISPACES_DERBY_PASSWORD"
        cat "$sql_file"
    } > "$ij_sql"
    if "${CISPACES}/tools/derby/bin/ij" "$ij_sql" >"$ij_log" 2>&1; then
        marker_tmp=$(mktemp "${CISPACES}/data/derby/gaiandb.seeded.XXXXXX")
        printf '%s\n' 'seeded' > "$marker_tmp"
        mv "$marker_tmp" "$marker"
        rm -f "$ij_sql" "$ij_log"
        echo "# Created the Derby database"
        return 0
    fi
    echo "# Derby database creation failed. The value is not printed."
    # The secret is read from a mode 600 file, not passed as an awk argument.
    pwfile=$(mktemp)
    chmod 600 "$pwfile"
    printf '%s' "$CISPACES_DERBY_PASSWORD" > "$pwfile"
    awk -v pwfile="$pwfile" '
        BEGIN {
            getline secret < pwfile
            close(pwfile)
            repl="[redacted]"
        }
        {
            out=$0
            while (secret != "" && (i = index(out, secret)) > 0) {
                out = substr(out, 1, i-1) repl substr(out, i+length(secret))
            }
            print out
        }
    ' "$ij_log"
    rm -f "$pwfile"
    rm -f "$ij_sql" "$ij_log"
    discard_open_database "$dbdir"
    exit 1
}

stop_services() {
    if [ "$stopping" -eq 1 ]; then
        return 0
    fi
    stopping=1
    if [ -n "$tomcat_pid" ] && kill -0 "$tomcat_pid" 2>/dev/null; then
        "${CATALINA_HOME}/bin/catalina.sh" stop >/dev/null 2>&1 || true
        wait "$tomcat_pid" 2>/dev/null || true
    fi
    if [ -n "$derby_pid" ] && kill -0 "$derby_pid" 2>/dev/null; then
        "${CISPACES}/tools/derby/bin/stopNetworkServer" -h 127.0.0.1 >/dev/null 2>&1 || true
        wait "$derby_pid" 2>/dev/null || true
    fi
}

trap stop_services TERM INT

require_derby_password
write_context
generate_jwt_key

if [ -z "${CISPACES_POSTGRES_PASSWORD:-}" ] || [ -z "${CISPACES_RABBITMQ_PASSWORD:-}" ]; then
    echo "# Export CISPACES_POSTGRES_PASSWORD and CISPACES_RABBITMQ_PASSWORD before using fact extraction. See config/secrets.env.example."
fi
echo "# This image starts Tomcat and Derby. PostgreSQL, RabbitMQ, and the fact-extraction engine are not part of it."

mkdir -p "${CISPACES}/data/derby"
chmod 700 "${CISPACES}/data/derby"

echo "# Starting Apache Derby"
"${CISPACES}/tools/derby/bin/startNetworkServer" -h 127.0.0.1 &
derby_pid=$!
wait_for_derby
seed_derby

echo "# Starting Apache Tomcat"
"${CATALINA_HOME}/bin/catalina.sh" run &
tomcat_pid=$!
set +e
wait "$tomcat_pid"
status=$?
set -e
tomcat_pid=""
stop_services
exit "$status"
