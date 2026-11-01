Collaborative Intelligence Spaces
=================================

[CISpaces.org](https://cispaces.org)

Pre-alpha demonstrator - binary package
---------------------------------------

This archive contains pre-built binaries, along with dependencies, targetting Enterprise Linux 7.3. 
It is recommended that a dedicated virtual machine is used for this deployment.

### Installation

As root, run:

# ./install.sh

### Usage

As a non-root user run:

$ ./start-cispaces

Access to the web interface from client machines will require firewall access:

$ sudo firewall-cmd --zone=public --add-port=8080/tcp --permanent
$ sudo firewall-cmd --reload

3. To use CISpaces web interface, point your Chrome web browser to http://YOUR_IP:8080

You can login with sample users 'Ella', 'Joe' or 'Miles' with password 'password' after the database is seeded from the current SQL. An older database must be re-seeded or those passwords reset.

Set `CISPACES_POSTGRES_PASSWORD` and `CISPACES_RABBITMQ_PASSWORD` in the environment before starting fact extraction. Derby's password is the one written into Tomcat `context.xml` at install (`CISPACES_DERBY_PASSWORD`). One shared JWT key file is `${CISPACES_JWT_KEY_FILE}`, which defaults to `config/key.properties` next to this install. See `config/secrets.env.example`. Do not commit real values.

To stop:

4. To stop cispaces:

$ ./stop-cispaces.sh


