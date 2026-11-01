#!/bin/bash

# Start Services for CISpaces
# J.Robinson@software.ac.uk

if [[ $EUID -eq 0 ]]; then
   echo "This script must NOT be run as root" 
   exit 1
fi

start_dir=$(pwd)
export JAVA_HOME=${start_dir}/fact-extraction/third-party/jdk1.8.0_151
if [ -z "${CISPACES_JWT_KEY_FILE:-}" ]; then
    export CISPACES_JWT_KEY_FILE="${start_dir}/config/key.properties"
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
context_xml="${start_dir}/tools/tomcat/conf/context.xml"
if [ -f "$context_xml" ] && grep -q '__CISPACES_DERBY_PASSWORD__' "$context_xml"; then
    if [ -z "${CISPACES_DERBY_PASSWORD:-}" ]; then
        echo "# Export CISPACES_DERBY_PASSWORD before starting. Tomcat context.xml still has the Derby placeholder. The value is not printed."
    else
        set +x
        derby_password_xml=$CISPACES_DERBY_PASSWORD
        derby_password_xml=${derby_password_xml//&/&amp;}
        derby_password_xml=${derby_password_xml//</&lt;}
        derby_password_xml=${derby_password_xml//>/&gt;}
        derby_password_xml=${derby_password_xml//\"/&quot;}
        derby_password_xml=${derby_password_xml//\'/&apos;}
        password_file=$(mktemp)
        chmod 600 "$password_file"
        printf '%s' "$derby_password_xml" > "$password_file"
        unset derby_password_xml
        context_tmp=$(mktemp)
        chmod 600 "$context_tmp"
        # index/substr, not gsub, so characters in the password stay literal.
        awk -v sentinel="__CISPACES_DERBY_PASSWORD__" -v pwfile="$password_file" '
            BEGIN {
                getline password < pwfile
                close(pwfile)
            }
            {
                out = $0
                slen = length(sentinel)
                while ((i = index(out, sentinel)) > 0) {
                    out = substr(out, 1, i - 1) password substr(out, i + slen)
                }
                print out
            }
        ' "$context_xml" > "$context_tmp"
        mv "$context_tmp" "$context_xml"
        rm -f "$password_file"
        unset password_file
    fi
fi

echo "### - Starting CISPaces "

if [ -d fact-extraction ]; then
	echo
	echo "Starting Fact Extraction in the background..."
	cd fact-extraction/
	./startup-fe.sh &
	sleep 3
	cd ${start_dir}
fi

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
