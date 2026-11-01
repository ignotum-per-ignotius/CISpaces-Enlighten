#!/bin/bash

# Create databse tables required for CISpaces
# J.Robinson@software.ac.uk

echo "### - Creating database tables for CISPaces "
source ~/.profile

cd ${CISPACES}/tools/derby
echo "# - Starting Apache Derby in the background..."
bin/startNetworkServer &
if [ $? -eq 0 ]; then echo "[OK]"; else echo "[Failed]"; exit; fi
sleep 5

echo "# - Importing tables from ${CISPACES}/../DATABASE/create-cispaces-tables.sql "
set +x
if [ -z "${CISPACES_DERBY_PASSWORD:-}" ]; then
    echo "# Set CISPACES_DERBY_PASSWORD before creating the database. See config/secrets.env.example. The value is not printed."
    exit 1
fi
case "${CISPACES_DERBY_PASSWORD}" in
    *\\*|*$'\n'*|*$'\r'*|*\'*|*\;*|*\`*)
        echo "# CISPACES_DERBY_PASSWORD contains a character this script cannot put in the Derby connect URL. Choose another value. The value is not printed."
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
    kill $! >/dev/null 2>&1 || true
    exit 1
fi

echo "The CISpaces database has been created at: ${CISPACES}/derby/gaiandb"
echo "If you need to delete the database, remove this directory"
echo

echo "# - Shutting down background Apache Derby..."
kill $!
if [ $? -eq 0 ]; then echo "[OK]"; else echo "[Failed]"; exit; fi
cd -
