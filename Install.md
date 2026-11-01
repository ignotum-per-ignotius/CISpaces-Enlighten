# Installing the CISpaces web UI and services

## Prerequisites
The CISpaces web services are Java based and have tested with OpenJDK 8 and Oracle JDK 8
http://www.oracle.com/technetwork/java/javase/downloads/index.html

The build process employs Apache Maven, and has been tested with version 3.3.9
https://maven.apache.org/download.cgi

The services run under a Java web application server, and have been tested with Apache Tomcat 8.0.45 to 8.0.47
http://tomcat.apache.org/download-80.cgi

## Secrets

CISpaces does not ship a JWT signing key or database passwords. Placeholders live only in `config/secrets.env.example` and `config/key.properties.example`. Those files are not loaded at runtime.

Export these before `./install-prereqs.sh`, `./create-database.sh`, and `FEWS/extra/install-fact-extraction.sh`, and export the PostgreSQL and RabbitMQ values again in the shell that runs `./start-cispaces.sh` so Tomcat inherits them:

- `CISPACES_DERBY_PASSWORD` — Derby user `gaiandb`. `install-prereqs.sh` writes it into Tomcat's `context.xml` and sets that file to mode 600. `create-database.sh` uses it for the ij connection.
- `CISPACES_POSTGRES_PASSWORD` — PostgreSQL role `intelanalysis`. FEWS reads this when it connects. `install-fact-extraction.sh` sets the role password from it and writes `db_pass` in `fact_extraction_app.ini`. The linux package replaces that line with `db_pass=__CISPACES_POSTGRES_PASSWORD__`, and the packaged `start-cispaces.sh` fills it from this variable, sets the file to mode 600, and does not print it.
- `CISPACES_RABBITMQ_PASSWORD` — RabbitMQ user `intelanalysis`. FEWS reads this when it connects. `install-fact-extraction.sh` sets the broker user from it.

`install-prereqs.sh` generates one JWT key at `${CISPACES}/config/key.properties` (mode 600) and appends `CISPACES_JWT_KEY_FILE` to `~/.profile`. Every service reads that same file. `start-cispaces.sh` exports `CISPACES_JWT_KEY_FILE` when it is unset, defaulting to `${CISPACES}/config/key.properties`. Replace that file and restart to rotate the key. Do not commit it.

Password hashes are PBKDF2-HMAC-SHA1 at 1400000 iterations, the current OWASP Password Storage Cheat Sheet floor for that algorithm. The algorithm is unchanged so an older hash still verifies. On a successful login, a hash stored with fewer iterations is rewritten at 1400000.

Sample users Ella, Joe, and Miles (password `password` in `DATABASE/create-cispaces-tables.sql`) in a database created before this change still have 1000-iteration hashes. Re-seed from that SQL file or reset those passwords. A successful login rewrites an old hash; accounts that never log in keep the weaker hash until they are re-seeded or reset.

## Installation

### Ubuntu 16.04
- Install git

```$ sudo apt-get install git```

- Clone CISpaces repository

```$ git clone https://github.com/CISpaces/webservices.git```

- Run the pre-requisites installation script from the webservices directory

```
$ cd webservices
$ ./install-prereqs.sh
```

- Run the fact-extraction installation script - usually takes ~15 minutes

```$ FEWS/extra/install-fact-extraction.sh```

- Update the environment

```$ source ~/.profile```

- Add local dependencies to Maven

```$ mvn initialize```

- Build the project using Maven. This produces war files under each module's `target/` directory and does not copy them into Tomcat.

```$ mvn install```

- Deploy the wars and the static UI into Tomcat (requires `CATALINA_HOME`):

```$ ./deploy-to-tomcat.sh```

## Running

### Ubuntu 16.04

- To start:

```$ ./start-cispaces.sh```

- Start the fact-extraction engine running in a Screen session

```
$ screen
$ cd ${CISPACES}/tools/fact-extraction
$ source env/bin/activate
$ ant run.fact-extraction-app
$ <CTRL+A>,<D>
```

- To use CISpaces web interface, point your web browser to http://YOUR_IP:8080
- You can login with sample users 'Ella', 'Joe' or 'Miles' with password 'password' on a database seeded from the current `DATABASE/create-cispaces-tables.sql`. An older database must be re-seeded or those passwords reset; logging in once also rewrites an old hash.

- To stop:

```$ ./stop-cispaces.sh```

## Third party software
Cispaces has dependencies on a number of third party software libraries, none of
these dependencies are distributed here, but are fetched during the build 
process.  Details of the dependencies and their licenses can be aggregated by running:

```$  mvn license:add-third-party license:aggregate-download-licenses```

## Packaging for redistribution
To build a distributable zip file containing CISpaces, Tomcat and Derby and all dependencies, 
following the Installation instructions above with an additional step:

```$ ./package-cispaces-linux.sh```
