#!/bin/bash

# Start Services for CISpaces
# J.Robinson@software.ac.uk

if [[ $EUID -eq 0 ]]; then
   echo "This script must NOT be run as root" 
   exit 1
fi

start_dir=$(pwd)
script_dir=$(cd "$(dirname "$0")" && pwd)
if [ -z "${CISPACES:-}" ]; then
    export CISPACES="${script_dir}/cispaces"
fi
if [ -z "${CISPACES_JWT_KEY_FILE:-}" ]; then
    export CISPACES_JWT_KEY_FILE="${CISPACES}/config/key.properties"
fi
if [ ! -f "${CISPACES_JWT_KEY_FILE}" ]; then
    set +x
    mkdir -p "$(dirname "${CISPACES_JWT_KEY_FILE}")"
    jwt_key=$(od -An -N64 -tx1 /dev/urandom | tr -d ' \n')
    old_umask=$(umask)
    umask 077
    {
        echo "# Shared by every CISpaces service. Generated at install. Do not commit."
        printf 'keyString=%s\n' "$jwt_key"
    } > "${CISPACES_JWT_KEY_FILE}"
    umask "$old_umask"
    unset jwt_key
    chmod 600 "${CISPACES_JWT_KEY_FILE}"
    echo "# Wrote the shared JWT key file"
fi
if [ -z "${CISPACES_POSTGRES_PASSWORD:-}" ] || [ -z "${CISPACES_RABBITMQ_PASSWORD:-}" ]; then
    echo "# Export CISPACES_POSTGRES_PASSWORD and CISPACES_RABBITMQ_PASSWORD before starting fact extraction. See config/secrets.env.example."
fi

echo "### - Starting CISPaces "

if [ -d fact-extraction ]; then
	echo
	echo "Starting Fact Extraction in the background..."
	cd fact-extraction/
	./startup-fe.sh &
	sleep 3
fi
cd ${start_dir}/cispaces

echo
echo "# - Starting Apache Derby in the background..."
cd tools/derby
bin/startNetworkServer > cispaces-fact-extraction.log 2>&1 &
if [ $? -eq 0 ]; then echo "[OK]"; else echo "[Failed]"; exit; fi
sleep 2
cd -

echo
echo "# - Starting Apache Tomcat in the background..."
tools/tomcat/bin/startup.sh
if [ $? -eq 0 ]; then
	echo "[OK]"
	echo
	echo "# Waiting 20s for application startup..."
	for ((i=20; i>=1; i--))
	do
		sleep 1
    		echo -n "${i}..."
	done
	echo
	echo
	echo "# CISpaces started listening at the following URLs:"
	echo
	for host_ip in $(hostname -I)
	do
		echo "http://${host_ip}:8080"
	done
	echo
	echo "# Please ensure port 8080 is accessible."
	echo
	echo "# RHEL / CentOS:"
	echo "    $ sudo firewall-cmd --zone=public --add-port=8080/tcp --permanent"
	echo "    $ sudo firewall-cmd --reload"
	echo 
	echo "# Ubuntu:"
	echo "    $ sudo ufw allow 8080"
	echo
	echo "# Do not close this terminal"
else echo "[Failed]"; exit; fi
