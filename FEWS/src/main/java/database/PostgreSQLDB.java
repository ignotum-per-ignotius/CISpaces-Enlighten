package database;

import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class handles queries to the PostgreSQL database for FEWS (Fact Extraction Web Service)
 *
 * <p/>Queries return ArrayLists of Tweets and Topics.
 *
 * @see Tweet
 * @see Topic
 */
public class PostgreSQLDB {

    private Connection conn;
    private static Logger log;

    public PostgreSQLDB() {
        log = Logger.getLogger(getClass().getName());
    }

    /**
     * Connects to the Fact-Extraction PostgreSQL database.
     *
     * @return Was connection successful?
     */
    private boolean connect() {

        Properties prop = new Properties();
        InputStream input = null;

        try
        {

            ClassLoader classLoader = getClass().getClassLoader();
            input = classLoader.getResourceAsStream("postgresql.properties");
            prop.load(input);

            Class.forName(prop.getProperty("driver"));
            String url = "jdbc:" + prop.getProperty("engine") +
                    "://" + prop.getProperty("hostname") +
                    ":" + prop.getProperty("port") +
                    "/" + prop.getProperty("database");
            conn = DriverManager.getConnection(
                    url,
                    prop.getProperty("user"),
                    prop.getProperty("password")
            );

            log.info("#### Read Postgresql config from file");

            return true;

        } catch (SQLException | ClassNotFoundException exc) {

            log.log(Level.SEVERE, "Failed PostgreSQL DB connection", exc);
            exc.printStackTrace();
            return false;

        } catch (java.io.IOException exc) {

            log.log(Level.SEVERE, "Failed to read PostgreSQL configuration from file", exc);
            exc.printStackTrace();
            return false;

        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (java.io.IOException exc) {
                    log.log(Level.SEVERE, "Failed to close properties file", exc);
                    exc.printStackTrace();
                }
            }
        }
    }

    /**
     * Closes the connection to the Fact-Extraction PostgreSQL database.
     *
     * @param rs SQL ResultSet to close
     * @param s SQL Statement to close
     * @param c SQL Connection to close
     */
    private void finalise(ResultSet rs, Statement s, Connection c) {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException exc) { /* Ignored */ }
        }
        if (s != null) {
            try {
                s.close();
            } catch (SQLException exc) { /* Ignored */ }
        }
        if (c != null) {
            try {
                c.close();
            } catch (SQLException exc) { /* Ignored */ }
        }
    }



    // Stuart's single-topic query
    // This is replicated and extended in the following function
//                    "SELECT " +
//                    "post.source_uri, post.text, extract.extract, extract.item_key " +
//                    "FROM " +
//                    "factextract.phase1_ext_db_item AS extract, " +
//                    "factextract.phase1_ext_db_topic_index AS index, " +
//                    "factextract.phase1_ext_db_topic AS topic, " +
//                    "factextract.phase1_post_db_item AS post " +
//                    "WHERE " +
//                    "extract.item_key = index.item_key AND " +
//                    "index.topic_key = topic.topic_key AND " +
//                    "topic.topic = ? AND " +
//                    "topic.negated IS NOT TRUE AND " +
//                    "topic.genuine IS NOT FALSE AND " +
//                    "post.source_uri = extract.source_uri"

    /**
     * Get a list of Tweets referring to the specified Topics.
     *
     * @param topicList List of Topics to which Tweets should refer
     * @return ArrayList of Tweets referring to the specified Topic
     * @see Topic
     * @see Tweet
     */
    public List<Tweet> tweetsForTopic(List<Topic> topicList) {
        Statement statement = null;
        ResultSet resultSet = null;

        List<Tweet> tweetList = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();

        try {
            if (!connect()) { return null; }

            // One prepared statement. Topic names and the three-state flags are bound, not concatenated.
            StringBuilder queryString = new StringBuilder(
                    // Get the tables we want data from
                    "SELECT DISTINCT ON (extract.item_key) " +
                    "extract.item_key, extract.extract, extract.source_uri, post.text, post.created_at " +
                    "FROM factextract.phase1_ext_db_item as extract " +
                    "JOIN factextract.phase1_ext_db_topic_index as topic_index " +
                    "ON (extract.item_key = topic_index.item_key) " +
                    "LEFT JOIN factextract.phase1_post_db_item as post " +
                    "ON (extract.source_uri = post.source_uri) "
            );

            for (int i=0; i < topicList.size(); i++) {
                Topic topic = topicList.get(i);

                if (i == 0) {
                    queryString.append("WHERE extract.item_key IN ");
                } else {
                    queryString.append("AND extract.item_key IN ");
                }
                queryString.append(
                        // Subquery to get a list of extract.item_key for each Topic
                        "( " +
                            "SELECT DISTINCT topic_index.item_key " +
                            "FROM factextract.phase1_ext_db_topic as topic " +
                            "JOIN factextract.phase1_ext_db_topic_index as topic_index " +
                            "ON (topic.topic_key = topic_index.topic_key) " +
                            "WHERE topic.topic = ? " +
                            // TRUE, FALSE, and NULL stay distinct. IS NOT DISTINCT FROM matches all three.
                            "AND topic.negated IS NOT DISTINCT FROM ? " +
                            "AND topic.genuine IS NOT DISTINCT FROM ? " +
                        ") "
                );
                parameters.add(topic.getName());
                parameters.add(bool3ToBoolean(topic.isNegated()));
                parameters.add(bool3ToBoolean(topic.isGenuine()));
            }
            queryString.append("ORDER BY extract.item_key");

            PreparedStatement prepared = conn.prepareStatement(queryString.toString());
            statement = prepared;
            for (int p = 0; p < parameters.size(); p++) {
                Object value = parameters.get(p);
                if (value == null) {
                    prepared.setNull(p + 1, Types.BOOLEAN);
                } else if (value instanceof Boolean) {
                    prepared.setBoolean(p + 1, ((Boolean) value).booleanValue());
                } else {
                    prepared.setString(p + 1, value.toString());
                }
            }
            resultSet = prepared.executeQuery();

            while (resultSet.next()) {
                int id = resultSet.getInt("item_key");
                String extract = resultSet.getString("extract");
                String uri = resultSet.getString("source_uri");
                String text = resultSet.getString("text");
                java.sql.Timestamp created = resultSet.getTimestamp("created_at");

                tweetList.add(new Tweet(id, extract, uri, text, created));
            }

        } catch (SQLException exc) {
            log.log(Level.SEVERE, "Failed PostgreSQL DB query", exc);
            exc.printStackTrace();
            return null;
        } finally {
            finalise(resultSet, statement, conn);
        }

        return tweetList;
    }

    /**
     * Get and convert a boolean value that may be NULL (termed here a Bool3).
     * Value is converted to an int using:
     *  TRUE  ->  1
     *  FALSE ->  0
     *  NULL  -> -1
     *
     * @param resultSet SQL query ResultSet from which to get data
     * @param column Boolean column to get
     * @return Boolean value as an integer
     * @throws SQLException If column is missing
     */
    private static int getIntFromBool3(ResultSet resultSet, String column)
            throws SQLException {
        boolean bValue = resultSet.getBoolean(column);
        return resultSet.wasNull() ? -1 : (bValue ? 1 : 0);
    }

    /**
     * Map the integer Bool3 onto a Boolean parameter.
     * 1 is TRUE, 0 is FALSE, and any other value is NULL.
     */
    private static Boolean bool3ToBoolean(int bool3) {
        if (bool3 == 0) {
            return Boolean.FALSE;
        } else if (bool3 == 1) {
            return Boolean.TRUE;
        } else {
            return null;
        }
    }

    private static boolean isResultSetEmpty(ResultSet rs)
            throws SQLException {
        return (!rs.isBeforeFirst() && rs.getRow() == 0);
    }

    /**
     * Get the list of all known Topics.
     *
     * @return List of all Topics present in database
     */
    public List<Topic> listTopics() {
        Statement statement = null;
        ResultSet resultSet = null;

        List<Topic> topicList = new ArrayList<>();

        try {
            if (!connect()) { return null; }

            String queryString =
                    "SELECT * " +
                    "FROM factextract.phase1_ext_db_topic " +
                    "ORDER BY topic_key;";

            statement = conn.createStatement();
            resultSet = statement.executeQuery(queryString);

            while (resultSet.next()) {
                String topicName = resultSet.getString("topic");
                int negated = getIntFromBool3(resultSet, "negated");
                int genuine = getIntFromBool3(resultSet, "genuine");

                topicList.add(new Topic(topicName, negated, genuine));
            }

        } catch (SQLException exc) {
            log.log(Level.SEVERE, "Failed PostgreSQL DB query", exc);
            exc.printStackTrace();
            return null;
        } finally {
            finalise(resultSet, statement, conn);
        }

        return topicList;
    }

    /**
     * Get the list of all known VocabularyTopics.
     *
     * @return List of all VocabularyTopics present in database
     *
     * @see VocabularyTopic
     * @see Topic
     */
    public List<VocabularyTopic> getVocab() {
        return getVocab(null);
    }

    public List<VocabularyTopic> getVocab(String vocabTopic) {
        PreparedStatement statement = null;
        ResultSet resultSet = null;

        Map<String, VocabularyTopic> keywordMap = new LinkedHashMap<>();

        try {
            if (!connect()) { return null; }

            String queryString =
                    "SELECT topic.topic, topic.schema, keyword.keyword " +
                    "FROM factextract.vocab_topic as topic " +
                    "JOIN factextract.vocab_keyword as keyword " +
                    "ON (topic.topic_id = keyword.topic_id) ";

            if (vocabTopic != null) {
                queryString += "WHERE topic.topic = ?;";
                statement = conn.prepareStatement(queryString);
                statement.setString(1, vocabTopic);
            } else {
                queryString += ";";
                statement = conn.prepareStatement(queryString);
            }

            resultSet = statement.executeQuery();

            while (resultSet.next()) {
                String topicName = resultSet.getString("topic");
                String topicSchema = resultSet.getString("schema");
                String keyword = resultSet.getString("keyword");

                VocabularyTopic topic = keywordMap.get(topicName);
                if (topic == null) {
                    keywordMap.put(topicName, new VocabularyTopic(topicName, topicSchema));
                    topic = keywordMap.get(topicName);
                }
                topic.addKeyword(keyword);
            }

        } catch (SQLException exc) {
            log.log(Level.SEVERE, "Failed PostgreSQL DB query", exc);
            exc.printStackTrace();
            return null;
        } finally {
            finalise(resultSet, statement, conn);
        }

        return new ArrayList<>(keywordMap.values());
    }

    public boolean existsVocabTopic(String vocabTopic)
            throws SQLException {
        PreparedStatement statement = null;
        ResultSet resultSet = null;

        try {
            if (!connect()) throw new SQLException();

            String queryString =
                    "SELECT EXISTS (" +
                        "SELECT 1 FROM factextract.vocab_topic " +
                        " WHERE topic = ?" +
                    ") AS \"exists\";";

            statement = conn.prepareStatement(queryString);
            statement.setString(1, vocabTopic);
            resultSet = statement.executeQuery();

            if (resultSet.next()) {
                return resultSet.getBoolean("exists");
            } else {
                throw new SQLException();
            }

        } catch (SQLException exc) {
            log.log(Level.SEVERE, "Failed PostgreSQL DB insert", exc);
            exc.printStackTrace();
            throw exc;
        } finally {
            finalise(resultSet, statement, conn);
        }
    }

    public boolean existsVocabKeyword(String vocabTopic, String vocabKeyword)
            throws SQLException {
        PreparedStatement statement = null;
        ResultSet resultSet = null;

        try {
            if (!connect()) throw new SQLException();

            String queryString =
                    "SELECT EXISTS ( " +
                        "SELECT 1 FROM factextract.vocab_topic " +
                        "JOIN factextract.vocab_keyword " +
                        "ON (vocab_topic.topic_id = vocab_keyword.topic_id) " +
                        "WHERE topic = ? AND keyword = ?" +
                    ") AS \"exists\";";

            statement = conn.prepareStatement(queryString);
            statement.setString(1, vocabTopic);
            statement.setString(2, vocabKeyword);
            resultSet = statement.executeQuery();

            if (resultSet.next()) {
                return resultSet.getBoolean("exists");
            } else {
                throw new SQLException();
            }

        } catch (SQLException exc) {
            log.log(Level.SEVERE, "Failed PostgreSQL DB insert", exc);
            exc.printStackTrace();
            throw exc;
        } finally {
            finalise(resultSet, statement, conn);
        }
    }

    public void createVocabTopic(String vocabTopic, String vocabSchema)
            throws SQLException {
        PreparedStatement statement = null;

        try {
            if (!connect()) throw new SQLException();

            String queryString =
                    "INSERT INTO factextract.vocab_topic (topic, schema) " +
                    "VALUES (?, ?);";

            statement = conn.prepareStatement(queryString);
            statement.setString(1, vocabTopic);
            statement.setString(2, vocabSchema);
            statement.execute();

        } catch (SQLException exc) {
            log.log(Level.SEVERE, "Failed PostgreSQL DB insert", exc);
            exc.printStackTrace();
            throw exc;
        } finally {
            finalise(null, statement, conn);
        }
    }

    public void createVocabKeyword(String vocabTopic, String vocabKeyword)
            throws SQLException {
        PreparedStatement statement = null;

        try {
            if (!connect()) throw new SQLException();

            String queryString =
                    "INSERT INTO factextract.vocab_keyword (topic_id, keyword) " +
                    "VALUES ((SELECT topic_id FROM factextract.vocab_topic WHERE topic = ?), ?);";

            statement = conn.prepareStatement(queryString);
            statement.setString(1, vocabTopic);
            statement.setString(2, vocabKeyword);
            statement.execute();

        } catch (SQLException exc) {
            log.log(Level.SEVERE, "Failed PostgreSQL DB insert", exc);
            exc.printStackTrace();
            throw exc;
        } finally {
            finalise(null, statement, conn);
        }
    }

    public void deleteVocabTopic(String vocabTopic)
            throws SQLException {
        PreparedStatement statement = null;

        try {
            if (!connect()) throw new SQLException();

            // Then delete topic - database is setup to cascade
            String queryString = "DELETE FROM factextract.vocab_topic WHERE topic = ?;";
            statement = conn.prepareStatement(queryString);
            statement.setString(1, vocabTopic);
            statement.execute();

        } catch (SQLException exc) {
            log.log(Level.SEVERE, "Failed PostgreSQL DB delete", exc);
            exc.printStackTrace();
            throw exc;
        } finally {
            finalise(null, statement, conn);
        }
    }

    public void deleteVocabKeyword(String vocabTopic, String vocabKeyword)
            throws SQLException {
        PreparedStatement statement = null;

        try {
            if (!connect()) throw new SQLException();

            // Delete keyword
            String queryString =
                    "DELETE FROM factextract.vocab_keyword " +
                    "WHERE topic_id IN (SELECT topic_id FROM factextract.vocab_topic WHERE topic = ?)" +
                    "AND keyword = ?;";
            statement = conn.prepareStatement(queryString);
            statement.setString(1, vocabTopic);
            statement.setString(2, vocabKeyword);
            statement.execute();

        } catch (SQLException exc) {
            log.log(Level.SEVERE, "Failed PostgreSQL DB delete", exc);
            exc.printStackTrace();
            throw exc;
        } finally {
            finalise(null, statement, conn);
        }
    }
}
