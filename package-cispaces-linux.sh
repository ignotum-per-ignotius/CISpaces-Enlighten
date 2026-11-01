#!/usr/bin/env bash
# Package CISpaces WebServices for Deployment on RHEL
# Tested on RHEL 7.3
# J.Robinson@software.ac.uk

echo
echo "### - Package CISpaces Web Services for linux? (Y/n)"

usage="$(basename "$0") [-h] [-y]

where:
    -h  Show this help text
    -y  Automatically answer yes for all questions"

yes='false'
while getopts 'yh' flag; do
    case "${flag}" in
        y) yes='true' ;;
        h) echo "${usage}"
           exit ;;
        *) exit 1 ;;
    esac
done

if [ "${yes}" != 'true' ]; then read stopgo; if [ "$stopgo" == "n" ]; then exit 0; fi; fi

echo
echo "Looking for CISpaces Web Services..."
webapps="cispaces/tools/tomcat/webapps"
mkdir -p "${webapps}"
shopt -s nullglob
for war in VC/target/*.war FEWS/target/*.war ers/target/*.war nlg/target/*.war PROVSIMP/target/*.war provgraphs/target/*.war info/target/*.war; do
    cp "${war}" "${webapps}/"
done
if [ -d ROOT/WebContent ]; then
    mkdir -p "${webapps}/ROOT"
    cp -a ROOT/WebContent/. "${webapps}/ROOT/"
fi
find cispaces/tools/tomcat/ | grep war$
if [ $? -ne 0 ]; then
        echo "# Failed to find CISpaces Web Services to package - did you run 'mvn install'?"
        exit 1
fi  
echo "[OK]"

echo
echo "Removing downloaded dependency archives.."
rm -f cispaces/tools/*.tar.gz
echo "[OK]"

echo
echo "Copying example_cis_files/..."
cp -a example_cis_files/ cispaces
echo "[OK]"

echo
echo "Copying README.dist.md"
cp -a distribution_files/* cispaces/
echo "[OK]"

echo
echo "Copying Fact Extraction"
cp -a fact-extraction cispaces/
echo "[OK]"

echo
echo "Copying secret examples (not live credentials)..."
mkdir -p cispaces/config
cp -a config/key.properties.example config/secrets.env.example cispaces/config/
echo "[OK]"

echo
packagename="cispaces-linux-$(date '+%Y%m%d-%H%M%S').zip"
echo "Creating archive ${packagename}..."

# install-prereqs.sh writes the Derby password into context.xml, and
# install-fact-extraction.sh writes the PostgreSQL password into db_pass.
# Keep both out of the zip, then put the local files back.
packaged_secret_backup=
secret_index=0
last_backup_index=
last_backup_mode=
restore_packaged_secrets() {
    set +x
    if [ -z "${packaged_secret_backup:-}" ] || [ ! -d "$packaged_secret_backup" ]; then
        return 0
    fi
    local manifest="$packaged_secret_backup/manifest"
    if [ -f "$manifest" ]; then
        local index mode path
        while IFS=$'\t' read -r index mode path; do
            if [ -n "$path" ] && [ -f "$packaged_secret_backup/$index" ]; then
                cp "$packaged_secret_backup/$index" "$path"
                if [ -n "$mode" ]; then
                    chmod "$mode" "$path"
                fi
            fi
        done < "$manifest"
    fi
    rm -rf "$packaged_secret_backup"
    packaged_secret_backup=
}
backup_packaged_file() {
    set +x
    local path="$1"
    local index mode
    if [ ! -f "$path" ]; then
        return 0
    fi
    if [ -z "${packaged_secret_backup:-}" ]; then
        packaged_secret_backup=$(mktemp -d)
        chmod 700 "$packaged_secret_backup"
        : > "$packaged_secret_backup/manifest"
        chmod 600 "$packaged_secret_backup/manifest"
    fi
    secret_index=$((secret_index + 1))
    index=$secret_index
    mode=$(stat -c %a "$path")
    cp "$path" "$packaged_secret_backup/$index"
    chmod 600 "$packaged_secret_backup/$index"
    printf '%s\t%s\t%s\n' "$index" "$mode" "$path" >> "$packaged_secret_backup/manifest"
    last_backup_index=$index
    last_backup_mode=$mode
}
redact_packaged_context() {
    set +x
    local path="cispaces/tools/tomcat/conf/context.xml"
    if [ ! -f "$path" ]; then
        return 0
    fi
    backup_packaged_file "$path"
    awk '{
        line=$0
        out=""
        while (match(line, /password="[^"]*"/)) {
            out=out substr(line, 1, RSTART-1) "password=\"__CISPACES_DERBY_PASSWORD__\""
            line=substr(line, RSTART+RLENGTH)
        }
        print out line
    }' "$packaged_secret_backup/$last_backup_index" > "$path"
    chmod "$last_backup_mode" "$path"
}
redact_packaged_fact_ini() {
    set +x
    local path
    while IFS= read -r path; do
        if [ ! -f "$path" ]; then
            continue
        fi
        backup_packaged_file "$path"
        awk '
            /^db_pass=/ { print "db_pass=__CISPACES_POSTGRES_PASSWORD__"; next }
            { print }
        ' "$packaged_secret_backup/$last_backup_index" > "$path"
        chmod "$last_backup_mode" "$path"
    done < <(find cispaces -type f -name fact_extraction_app.ini 2>/dev/null)
}

redact_packaged_context
redact_packaged_fact_ini
trap restore_packaged_secrets EXIT
zip --symlinks -qr ${packagename} cispaces/ -x "cispaces/config/key.properties"
zip_status=$?
restore_packaged_secrets
trap - EXIT
if [ "$zip_status" -eq 0 ]; then echo "[OK]"; else echo "[Failed]"; exit; fi
