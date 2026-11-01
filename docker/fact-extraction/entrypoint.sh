#!/bin/bash
# Write fact_extraction_app.ini from the environment and start the consumer.
# Passwords are copied through mode 600 files. They are not process arguments.

set +x
set -euo pipefail

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

render_ini() {
    set +x
    local template="/opt/fact-extraction/fact_extraction_app.ini.template"
    local dest="/opt/fact-extraction/fact_extraction_app.ini"
    local pg_file rmq_file tmp
    if [ ! -f "$template" ] \
        || ! grep -q '__CISPACES_POSTGRES_PASSWORD__' "$template" \
        || ! grep -q '__CISPACES_RABBITMQ_PASSWORD__' "$template"; then
        echo "# fact-extraction config template is missing a password placeholder. The value is not printed."
        exit 1
    fi
    pg_file=$(mktemp)
    rmq_file=$(mktemp)
    tmp=$(mktemp)
    chmod 600 "$pg_file" "$rmq_file" "$tmp"
    printf '%s' "$CISPACES_POSTGRES_PASSWORD" > "$pg_file"
    printf '%s' "$CISPACES_RABBITMQ_PASSWORD" > "$rmq_file"
    awk -v pgfile="$pg_file" -v rmqfile="$rmq_file" '
        function swap(s, sentinel, value,    i, slen, prefix, rest) {
            rest = s
            prefix = ""
            slen = length(sentinel)
            while ((i = index(rest, sentinel)) > 0) {
                prefix = prefix substr(rest, 1, i - 1) value
                rest = substr(rest, i + slen)
            }
            return prefix rest
        }
        BEGIN {
            getline pg < pgfile
            close(pgfile)
            getline rmq < rmqfile
            close(rmqfile)
        }
        {
            line = swap($0, "__CISPACES_POSTGRES_PASSWORD__", pg)
            line = swap(line, "__CISPACES_RABBITMQ_PASSWORD__", rmq)
            print line
        }
    ' "$template" > "$tmp"
    rm -f "$pg_file" "$rmq_file"
    mv "$tmp" "$dest"
    chmod 600 "$dest"
    if grep -q '__CISPACES_' "$dest"; then
        rm -f "$dest"
        echo "# fact-extraction config still has a placeholder. The value is not printed."
        exit 1
    fi
}

reject_secret CISPACES_POSTGRES_PASSWORD
reject_secret CISPACES_RABBITMQ_PASSWORD
render_ini
export FACT_EXTRACTION_INI=/opt/fact-extraction/fact_extraction_app.ini
exec python3 /opt/fact-extraction/fact_extraction_bus.py
