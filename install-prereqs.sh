#!/usr/bin/env bash
# Install CISpaces on Ubuntu 16.04
# J.Robinson@software.ac.uk
# J.Graham@software.ac.uk

TOMCAT_VERSION="8.0.48"
TOMCAT_URL="https://archive.apache.org/dist/tomcat/tomcat-8/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz"


DERBY_VERSION="10.12.1.1"
DERBY_URL="http://archive.apache.org/dist/db/derby/db-derby-${DERBY_VERSION}/db-derby-${DERBY_VERSION}-bin.tar.gz"

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

# A traced shell must not print secret values.
set +x

require_env_secret() {
    set +x
    local name="$1"
    local kind="$2"
    local value="${!name-}"
    if [ -z "$value" ]; then
        echo "# Set ${name} in the environment before continuing. See config/secrets.env.example. The value is not printed."
        exit 1
    fi
    case "$value" in
        *\\*|*$'\n'*|*$'\r'*)
            echo "# ${name} contains a backslash or newline, which this script cannot pass through safely. Choose another value. The value is not printed."
            exit 1
            ;;
    esac
    if [ "$kind" = "derby" ]; then
        if [[ "$value" == *'$'* || "$value" == *";"* || "$value" == *"'"* || "$value" == *'`'* ]]; then
            echo "# ${name} cannot contain a quote, backtick, semicolon, or dollar sign. Choose another value. The value is not printed."
            exit 1
        fi
    fi
}

xml_escape() {
    local s="$1"
    s=${s//&/&amp;}
    s=${s//</&lt;}
    s=${s//>/&gt;}
    s=${s//\"/&quot;}
    s=${s//\'/&apos;}
    printf '%s' "$s"
}

generate_shared_jwt_key() {
    set +x
    local key_file="${CISPACES}/config/key.properties"
    if [ -f "$key_file" ]; then
        echo "# Shared JWT key file already exists; leaving it in place"
    else
        mkdir -p "${CISPACES}/config"
        local jwt_key
        jwt_key=$(od -An -N64 -tx1 /dev/urandom | tr -d ' \n')
        if [ "${#jwt_key}" -lt 32 ]; then
            echo "# Failed to generate a JWT key"
            unset jwt_key
            exit 1
        fi
        local old_umask
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
    fi
    local jwt_export="export CISPACES_JWT_KEY_FILE=${CISPACES}/config/key.properties"
    if ! grep -qxF "$jwt_export" "${HOME}/.profile" 2>/dev/null; then
        echo "$jwt_export" >> "${HOME}/.profile"
    fi
}

require_env_secret CISPACES_DERBY_PASSWORD derby

echo
echo "### - Install CISPACES Prerequisites on Ubuntu 16.04...? (Y/n)" 
if [ "${yes}" != 'true' ]; then read stopgo; if [ "$stopgo" == "n" ]; then exit 0; fi; fi

echo "# - Updating environment..."
echo "export CISPACES=${PWD}/cispaces" >> ~/.profile
source ~/.profile
export CISPACES="${PWD}/cispaces"

echo "# - Install prerequisite OS packages [default-jdk-headless, wget, zip, screen, rabbitmq-server, maven]...? (Y/n)"
if [ "${yes}" != 'true' ]; then read stopgo; if [ "$stopgo" == "n" ]; then exit 0; fi; fi
sudo apt-get install --yes default-jdk-headless wget unzip screen rabbitmq-server maven
if [ $? -eq 0 ]; then echo "[OK]"; else echo "[Failed]"; exit; fi
echo

echo "# - Updating environment..."
echo "export JAVA_HOME=/usr/lib/jvm/default-java" >> ~/.profile
echo 

echo "# - Creating tools directory.."
mkdir -p ${CISPACES}/tools/
generate_shared_jwt_key
cd ${CISPACES}/tools/
echo

echo "# - Install Apache Derby...? (Y/n)" 
if [ "${yes}" != 'true' ]; then read stopgo; if [ "$stopgo" == "n" ]; then exit 0; fi; fi
if [ ! -e "db-derby-${DERBY_VERSION}-bin.tar.gz" ]; then
	wget -O db-derby-${DERBY_VERSION}-bin.tar.gz ${DERBY_URL} 
fi	
tar xf db-derby-${DERBY_VERSION}-bin.tar.gz \
	&& ln -s db-derby-${DERBY_VERSION}-bin derby
if [ $? -eq 0 ]; then echo "[OK]"; else echo "[Failed]"; exit; fi
echo

echo "# - Install Apache Tomcat...? (Y/n)" 
if [ "${yes}" != 'true' ]; then read stopgo; if [ "${stopgo}" == "n" ]; then exit 0; fi; fi

if [ ! -e "apache-tomcat-${TOMCAT_VERSION}.tar.gz" ]; then
	wget -O apache-tomcat-${TOMCAT_VERSION}.tar.gz ${TOMCAT_URL}
fi
tar xf apache-tomcat-${TOMCAT_VERSION}.tar.gz \
	&& ln -s apache-tomcat-${TOMCAT_VERSION} tomcat \
	&& rm -rf tomcat/webapps/*

if [ $? -eq 0 ]; then echo "[OK]"; else echo "[Failed]"; exit; fi
echo 

echo "# - Updating environment..."
echo "export CATALINA_HOME=${CISPACES}/tools/tomcat" >> ~/.profile
source ~/.profile
echo

echo "# - Copying Derby library..."
cp  derby/lib/derbyclient.jar tomcat/lib/ \
	&& cp derby/lib/derbyLocale* tomcat/lib/
if [ $? -eq 0 ]; then echo "[OK]"; else echo "[Failed]"; exit; fi
echo 

echo "# - Configure Tomcat...? (Y/n)"
if [ "${yes}" != 'true' ]; then read stopgo; if [ "$stopgo" == "n" ]; then exit 0; fi; fi
sed -i 's:</Context>::g' tomcat/conf/context.xml
derby_password_xml=$(xml_escape "$CISPACES_DERBY_PASSWORD")
cat << EOF >> ${CISPACES}/tools/tomcat/conf/context.xml
<Resource name="jdbc/myDB"
        auth="Container"
        type="javax.sql.DataSource"
        username="gaiandb"
        password="${derby_password_xml}"
        driverClassName="org.apache.derby.jdbc.ClientDriver"
        url="jdbc:derby://localhost:1527/gaiandb"
        maxTotal="10"
        removeAbandonedOnBorrow="true"
        removeAbandonedTimeout="60"
        logAbandoned="true"
    />
</Context>
EOF
unset derby_password_xml
chmod 600 "${CISPACES}/tools/tomcat/conf/context.xml"
echo
cd -

echo "# - Create database tables...? (Y/n)"
if [ "${yes}" != 'true' ]; then read stopgo; if [ "$stopgo" == "n" ]; then exit 0; fi; fi

./create-database.sh

if [ $? -eq 0 ]; then echo "[OK] - Your system is now ready to install CISpaces."; else echo "[Failed]"; exit; fi
echo

