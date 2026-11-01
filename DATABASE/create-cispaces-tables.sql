-- SQL to generate CISpaces database tables
-- J.Robinson@software.ac.uk

-- create-database.sh supplies the connect statement from CISPACES_DERBY_PASSWORD.
-- This file has no database password.
--
-- Sample users Ella, Joe, and Miles use the documented sample password.
-- Hashes are PBKDF2-HMAC-SHA1 at 1400000 iterations.
-- A database created from the older 1000-iteration seed must be re-seeded
-- from this file or those passwords reset. A successful login also rewrites
-- an old hash.

CREATE TABLE GAIANDB.CISPACES_USERS
(
    USER_ID char(36),
    USERNAME varchar(255),
    PASSWORD varchar(255),
    AFFILIATION varchar(10),
    IS_ADMIN int
);
INSERT INTO GAIANDB.CISPACES_USERS (USER_ID, USERNAME, PASSWORD, AFFILIATION, IS_ADMIN) VALUES ('5a90c91f-1884-4f45-bc2e-49057745293c', 'Ella', '1400000:2c94087c267b7ff9195970621efb1325:79bea9443871107d0fac8f2bb9b96dfe4f136f9d8ece13f4e26e9411bf2cc74df1299afe0d0cb1b7f2572ab526a17c62553b246c5fa2b3eae54515eb0808f878', 'UK', 1);
INSERT INTO GAIANDB.CISPACES_USERS (USER_ID, USERNAME, PASSWORD, AFFILIATION, IS_ADMIN) VALUES ('00de81fa-4482-404a-bbb1-5bcb1ce359ee', 'Joe', '1400000:da633895c1b09a666d70d8bef80bb4f8:cf613849a1401c1abed003b088105386716843fea1f0a5dbf56ec515ba5c75da18ec347267b2d08e154c908545bce1d2a9d57890895ed354c74727b845b44c58', 'UK/US', 0);
INSERT INTO GAIANDB.CISPACES_USERS (USER_ID, USERNAME, PASSWORD, AFFILIATION, IS_ADMIN) VALUES ('af9ca932-8226-4c15-ba19-e4bb7e2fbaee', 'Miles', '1400000:7432009dde853c3ddb6c2d1186f82b69:a65faf054cc0ae5529a009faec0c1a9477a035a9405e17a59d9d4af408e0d57a9a26dbfaa9a802f1e4c5a0d24ff2700a55531de2fcf5629468a86002e6e1a72c', 'US', 0);

CREATE TABLE GAIANDB.CISPACES_INFOTMP
(
    ID int PRIMARY KEY NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1),
    USERNAME varchar(255),
    LASTTIME varchar(255),
    AFFILIATION varchar(255),
    LASTSET int
);

-- Tables from VC Service

CREATE TABLE GAIANDB.CISPACES_GRAPH
(
    GRAPHID varchar(255) PRIMARY KEY NOT NULL,
    USERID varchar(255),
    TIMEST timestamp,
    TITLE varchar(255),
    DESCRIPTION varchar(5000),
    ISSHARED boolean,
    PARENTGRAPHID varchar(255)
);

CREATE TABLE GAIANDB.CISPACES_GRAPH_HISTORY
(
    SNAPID varchar(255) PRIMARY KEY NOT NULL,
    GRAPHID varchar(255),
    TIMEST timestamp,
    ANALYSIS CLOB,
    TITLE varchar(255),
    USERID varchar(255)
);

CREATE TABLE GAIANDB.CISPACES_NODE
(
    NODEID varchar(255) PRIMARY KEY NOT NULL,
    SOURCE varchar(255),
    UNCERT varchar(20),
    EVAL long varchar,
    TXT long varchar,
    INP varchar(20),
    DTG timestamp,
    CMT varchar(50),
    TYPE varchar(10),
    ANNOT long varchar,
    GRAPHID varchar(255),
    ISLOCKED boolean
);

CREATE TABLE CISPACES_NODE_HISTORY 
( 
nodeid varchar(255), 
source varchar (255), 
uncert varchar(20), 
eval varchar(5000),
txt varchar (5000), 
inp varchar (20), 
dtg timestamp, 
cmt varchar(50), 
type varchar(10), 
annot varchar (5000), 
graphid varchar(255),
islocked boolean, 
revisionid varchar(255),
CONSTRAINT CISPACES_NODE_HISTORY_pk PRIMARY KEY (revisionid)
);

CREATE TABLE GAIANDB.CISPACES_EDGE
(
    EDGEID varchar(255) PRIMARY KEY NOT NULL,
    TARGET varchar(255),
    SOURCE varchar(255),
    FORMEDGEID varchar(255),
    GRAPHID varchar(255),
    ISLOCKED boolean
);

CREATE TABLE CISPACES_EDGE_HISTORY 
(
    edgeid varchar(255), 
    tonodeid varchar(255),
    fromnodeid varchar(255),
    formedgeid varchar(255),
    graphid varchar(255), 
    islocked boolean, 
    revisionid varchar(255), 
    CONSTRAINT CISPACES_EDGE_pk PRIMARY KEY (revisionid)
);

-- Tables from PROVSIMP ervice

CREATE TABLE GAIANDB.CISPACES_INFOPROV
( 
    nodeid VARCHAR (255),
    provst VARCHAR(4000),
    chaining VARCHAR (72), 
    CONSTRAINT CISPACES_INFOPROV_pk PRIMARY KEY (nodeid)
);
