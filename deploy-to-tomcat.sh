#!/usr/bin/env bash
# Copy built wars and the static UI into Tomcat.
# mvn install does not do this; the war in each module's target/ is the build artifact.
set -euo pipefail

if [ -z "${CATALINA_HOME:-}" ]; then
    echo "CATALINA_HOME is not set. Build with mvn install, then point this script at Tomcat."
    exit 1
fi

webapps="${CATALINA_HOME}/webapps"
mkdir -p "${webapps}"

shopt -s nullglob
wars=(VC/target/*.war FEWS/target/*.war ers/target/*.war nlg/target/*.war PROVSIMP/target/*.war provgraphs/target/*.war info/target/*.war)
if [ ${#wars[@]} -eq 0 ]; then
    echo "No wars found under module target/ directories. Run mvn install first."
    exit 1
fi

for war in "${wars[@]}"; do
    cp "${war}" "${webapps}/"
    echo "Copied ${war}"
done

mkdir -p "${webapps}/ROOT"
cp -a ROOT/WebContent/. "${webapps}/ROOT/"
echo "Copied ROOT/WebContent to ${webapps}/ROOT"
