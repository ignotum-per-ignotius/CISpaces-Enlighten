#!/bin/bash

# Create databse tables required for CISpaces
# J.Robinson@software.ac.uk

echo "### - Creating database tables for CISPaces "
source ~/.profile

cd ${CISPACES}/tools/derby
echo "# - Starting Apache Derby in the background..."
bin/startNetworkServer &
derby_pid=$!
if ! kill -0 "$derby_pid" 2>/dev/null; then echo "[Failed]"; exit; fi
echo "[OK]"
sleep 5

# Derby's launcher is a shell script whose JVM is a child. Kill the tree so
# port 1527 is released, including when this script exits before ij runs.
kill_tree() {
    local parent="$1"
    local child
    for child in $(ps -o pid= --ppid "$parent" 2>/dev/null); do
        kill_tree "$child"
    done
    kill "$parent" >/dev/null 2>&1 || true
}

stop_derby() {
    if [ -z "${derby_pid:-}" ]; then
        return 0
    fi
    local pid="$derby_pid"
    derby_pid=
    if ! kill -0 "$pid" 2>/dev/null; then
        return 0
    fi
    kill_tree "$pid"
    wait "$pid" 2>/dev/null || true
    if kill -0 "$pid" 2>/dev/null; then
        return 1
    fi
    return 0
}

echo "# - Importing tables from ${CISPACES}/../DATABASE/create-cispaces-tables.sql "
set +x
if [ -z "${CISPACES_DERBY_PASSWORD:-}" ]; then
    echo "# Set CISPACES_DERBY_PASSWORD before creating the database. See config/secrets.env.example. The value is not printed."
    stop_derby
    exit 1
fi
case "${CISPACES_DERBY_PASSWORD}" in
    *\\*|*$'\n'*|*$'\r'*|*\'*|*\;*|*\`*)
        echo "# CISPACES_DERBY_PASSWORD contains a character this script cannot put in the Derby connect URL. Choose another value. The value is not printed."
        stop_derby
        exit 1
        ;;
esac
sql_file="${CISPACES}/../DATABASE/create-cispaces-tables.sql"
ij_sql=$(mktemp)
ij_log=$(mktemp)
chmod 600 "$ij_sql" "$ij_log"
cleanup_ij() {
    rm -f "$ij_sql" "$ij_log"
}
trap cleanup_ij EXIT
{
    printf "connect 'jdbc:derby://localhost:1527/gaiandb;create=true;user=gaiandb;password=%s';\n" "$CISPACES_DERBY_PASSWORD"
    cat "$sql_file"
} > "$ij_sql"
if bin/ij "$ij_sql" >"$ij_log" 2>&1; then
    echo "[OK]"
else
    echo "[Failed]"
    awk -v secret="$CISPACES_DERBY_PASSWORD" '
        BEGIN { repl="[redacted]" }
        {
            out=$0
            while (secret != "" && (i = index(out, secret)) > 0) {
                out = substr(out, 1, i-1) repl substr(out, i+length(secret))
            }
            print out
        }
    ' "$ij_log"
    stop_derby
    exit 1
fi

echo "The CISpaces database has been created at: ${CISPACES}/derby/gaiandb"
echo "If you need to delete the database, remove this directory"
echo

echo "# - Shutting down background Apache Derby..."
if stop_derby; then echo "[OK]"; else echo "[Failed]"; exit; fi
cd -
