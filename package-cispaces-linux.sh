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

# install-prereqs.sh writes the Derby password into this file. Keep that value
# out of the zip, then put the local file back so this machine is unchanged.
context_xml="cispaces/tools/tomcat/conf/context.xml"
context_backup=
context_mode=
restore_packaged_context() {
    set +x
    if [ -n "${context_backup:-}" ] && [ -f "$context_backup" ] && [ -n "${context_xml:-}" ]; then
        cp "$context_backup" "$context_xml"
        if [ -n "${context_mode:-}" ]; then
            chmod "$context_mode" "$context_xml"
        fi
        rm -f "$context_backup"
        context_backup=
    fi
}
redact_packaged_context() {
    set +x
    if [ ! -f "$context_xml" ]; then
        return 0
    fi
    context_mode=$(stat -c %a "$context_xml")
    context_backup=$(mktemp)
    chmod 600 "$context_backup"
    cp "$context_xml" "$context_backup"
    awk '{
        line=$0
        out=""
        while (match(line, /password="[^"]*"/)) {
            out=out substr(line, 1, RSTART-1) "password=\"__CISPACES_DERBY_PASSWORD__\""
            line=substr(line, RSTART+RLENGTH)
        }
        print out line
    }' "$context_backup" > "$context_xml"
}

redact_packaged_context
trap restore_packaged_context EXIT
zip --symlinks -qr ${packagename} cispaces/ -x "cispaces/config/key.properties"
zip_status=$?
restore_packaged_context
trap - EXIT
if [ "$zip_status" -eq 0 ]; then echo "[OK]"; else echo "[Failed]"; exit; fi
