# CISpaces analysis workbench.
# Java 8, Tomcat 8.0.48, Derby 10.12.1.1, and the wars the UI calls:
# VC, fewsservlet, ers, nlg, PROVSIMP, provgraphs, info, plus ROOT.
# CISPACES_DERBY_PASSWORD is applied when the container starts. The image
# has no JWT key and no database password.

FROM eclipse-temurin:8-jdk-jammy AS build

ARG MAVEN_VERSION=3.8.8
ARG MAVEN_SHA1=cf0265a8f373b707972a92d71e3ead94e5cee27e

RUN curl -fsSL -o /tmp/maven.tgz "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    && echo "${MAVEN_SHA1}  /tmp/maven.tgz" | sha1sum -c - \
    && tar -xzf /tmp/maven.tgz -C /opt \
    && ln -s "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn \
    && rm -f /tmp/maven.tgz

WORKDIR /src
COPY . .
# ers installs these jars in its initialize phase. Maven resolves dependencies
# before that phase, so install them first, the same jars mvn initialize would.
RUN mvn -B org.apache.maven.plugins:maven-install-plugin:2.5.2:install-file \
        -Dfile=ers/lib/jprol.jar -DgroupId=com.igormaznitsa -DartifactId=prol \
        -Dversion=1.0 -Dpackaging=jar -DgeneratePom=true \
    && mvn -B org.apache.maven.plugins:maven-install-plugin:2.5.2:install-file \
        -Dfile=ers/lib/jArgSemSAT-0.1.2-1.jar -DgroupId=net.sf.jargsemsat \
        -DartifactId=jArgSemSAT -Dversion=0.1.2 -Dpackaging=jar -DgeneratePom=true \
    && mvn -B -DskipTests package \
    && test -f VC/target/VC.war \
    && test -f FEWS/target/fewsservlet.war \
    && test -f ers/target/ers.war \
    && test -f nlg/target/nlg.war \
    && test -f PROVSIMP/target/PROVSIMP.war \
    && test -f provgraphs/target/provgraphs.war \
    && test -f info/target/info.war \
    && for war in VC/target/VC.war FEWS/target/fewsservlet.war ers/target/ers.war nlg/target/nlg.war PROVSIMP/target/PROVSIMP.war provgraphs/target/provgraphs.war info/target/info.war; do \
         if jar tf "$war" | grep -q 'key.properties'; then \
           echo "refusing to package ${war}: it contains key.properties" >&2; \
           exit 1; \
         fi; \
       done \
    && rm -rf /tmp/warcheck && mkdir -p /tmp/warcheck \
    && (cd /tmp/warcheck && jar xf /src/FEWS/target/fewsservlet.war WEB-INF/classes/postgresql.properties WEB-INF/classes/rabbitmq.properties) \
    && if grep -qE '^[Pp]assword=.+' /tmp/warcheck/WEB-INF/classes/postgresql.properties; then \
         echo "refusing to package FEWS: postgresql.properties contains a password" >&2; \
         exit 1; \
       fi \
    && if grep -qE '^[Pp]assword=.+' /tmp/warcheck/WEB-INF/classes/rabbitmq.properties; then \
         echo "refusing to package FEWS: rabbitmq.properties contains a password" >&2; \
         exit 1; \
       fi \
    && rm -rf /tmp/warcheck

FROM eclipse-temurin:8-jdk-jammy

ARG TOMCAT_VERSION=8.0.48
ARG TOMCAT_SHA512=dd8dff03bb2f80a4071f8b7068eecf80a81ebef5c85f89f3f87f3bdbbfef73d38a03d04bdd1c9febf0d59db267b33907840263f797eb44de4a4bb4d1745e36ca
ARG DERBY_VERSION=10.12.1.1
ARG DERBY_MD5=7432324eb689a83db4b06edc6c13eca1

ENV CISPACES=/opt/cispaces \
    CATALINA_HOME=/opt/cispaces/tools/tomcat \
    CISPACES_JWT_KEY_FILE=/opt/cispaces/config/key.properties

RUN curl -fsSL -o /tmp/tomcat.tgz "https://archive.apache.org/dist/tomcat/tomcat-8/v${TOMCAT_VERSION}/bin/apache-tomcat-${TOMCAT_VERSION}.tar.gz" \
    && echo "${TOMCAT_SHA512}  /tmp/tomcat.tgz" | sha512sum -c - \
    && curl -fsSL -o /tmp/derby.tgz "https://archive.apache.org/dist/db/derby/db-derby-${DERBY_VERSION}/db-derby-${DERBY_VERSION}-bin.tar.gz" \
    && echo "${DERBY_MD5}  /tmp/derby.tgz" | md5sum -c - \
    && mkdir -p /opt/cispaces/tools /opt/cispaces/config /opt/cispaces/data/derby \
    && tar -xzf /tmp/tomcat.tgz -C /opt/cispaces/tools \
    && tar -xzf /tmp/derby.tgz -C /opt/cispaces/tools \
    && ln -s "apache-tomcat-${TOMCAT_VERSION}" /opt/cispaces/tools/tomcat \
    && ln -s "db-derby-${DERBY_VERSION}-bin" /opt/cispaces/tools/derby \
    && rm -rf /opt/cispaces/tools/tomcat/webapps/* /tmp/tomcat.tgz /tmp/derby.tgz \
    && cp /opt/cispaces/tools/derby/lib/derbyclient.jar /opt/cispaces/tools/tomcat/lib/ \
    && cp /opt/cispaces/tools/derby/lib/derbyLocale* /opt/cispaces/tools/tomcat/lib/ \
    && groupadd --system cispaces \
    && useradd --system --gid cispaces --home-dir /opt/cispaces --shell /usr/sbin/nologin cispaces

RUN sed -i 's:</Context>::g' /opt/cispaces/tools/tomcat/conf/context.xml \
    && printf '%s\n' \
        '<Resource name="jdbc/myDB"' \
        '        auth="Container"' \
        '        type="javax.sql.DataSource"' \
        '        username="gaiandb"' \
        '        password="__CISPACES_DERBY_PASSWORD__"' \
        '        driverClassName="org.apache.derby.jdbc.ClientDriver"' \
        '        url="jdbc:derby://127.0.0.1:1527/gaiandb"' \
        '        maxTotal="10"' \
        '        removeAbandonedOnBorrow="true"' \
        '        removeAbandonedTimeout="60"' \
        '        logAbandoned="true"' \
        '    />' \
        '</Context>' >> /opt/cispaces/tools/tomcat/conf/context.xml \
    && cp /opt/cispaces/tools/tomcat/conf/context.xml /opt/cispaces/tools/tomcat/conf/context.xml.template \
    && grep -q '__CISPACES_DERBY_PASSWORD__' /opt/cispaces/tools/tomcat/conf/context.xml.template

# Tomcat 8.0.48 enables an AJP/1.3 Connector on 8009 with no bind address and
# no secret. Comment it out so nothing listens there. HTTP 8080 stays.
RUN awk ' \
    { \
        if ($0 ~ /^[[:space:]]*<Connector port="8009" protocol="AJP\/1.3"/) { \
            print "    <!-- AJP/1.3 disabled: Tomcat 8.0.48 would listen on 8009 with no secret. -->"; \
            print "    <!--" $0 " -->"; \
            found = 1; \
            next; \
        } \
        print; \
    } \
    END { if (!found) exit 1 } \
' /opt/cispaces/tools/tomcat/conf/server.xml > /tmp/server.xml \
    && mv /tmp/server.xml /opt/cispaces/tools/tomcat/conf/server.xml \
    && if grep -E '^[[:space:]]*<Connector[^>]*port="8009"' /opt/cispaces/tools/tomcat/conf/server.xml; then \
         echo "AJP connector on 8009 is still enabled" >&2; \
         exit 1; \
       fi

COPY docker-entrypoint.sh /opt/cispaces/docker-entrypoint.sh
COPY DATABASE/create-cispaces-tables.sql /opt/cispaces/config/create-cispaces-tables.sql
COPY --from=build /src/VC/target/VC.war /opt/cispaces/tools/tomcat/webapps/VC.war
COPY --from=build /src/FEWS/target/fewsservlet.war /opt/cispaces/tools/tomcat/webapps/fewsservlet.war
COPY --from=build /src/ers/target/ers.war /opt/cispaces/tools/tomcat/webapps/ers.war
COPY --from=build /src/nlg/target/nlg.war /opt/cispaces/tools/tomcat/webapps/nlg.war
COPY --from=build /src/PROVSIMP/target/PROVSIMP.war /opt/cispaces/tools/tomcat/webapps/PROVSIMP.war
COPY --from=build /src/provgraphs/target/provgraphs.war /opt/cispaces/tools/tomcat/webapps/provgraphs.war
COPY --from=build /src/info/target/info.war /opt/cispaces/tools/tomcat/webapps/info.war
COPY --from=build /src/ROOT/WebContent/ /opt/cispaces/tools/tomcat/webapps/ROOT/

RUN chmod 755 /opt/cispaces/docker-entrypoint.sh \
    && chmod 644 /opt/cispaces/config/create-cispaces-tables.sql \
    && chown -R cispaces:cispaces \
        "/opt/cispaces/tools/apache-tomcat-${TOMCAT_VERSION}" \
        "/opt/cispaces/tools/db-derby-${DERBY_VERSION}-bin" \
        /opt/cispaces/config \
        /opt/cispaces/data \
        /opt/cispaces/docker-entrypoint.sh \
    && chown -h cispaces:cispaces /opt/cispaces/tools/tomcat /opt/cispaces/tools/derby

# Derby's launcher checks $SHELL. Unset, /bin/sh prints an expr error and still starts.
ENV SHELL=/bin/bash

USER cispaces
WORKDIR /opt/cispaces
EXPOSE 8080
ENTRYPOINT ["/opt/cispaces/docker-entrypoint.sh"]
