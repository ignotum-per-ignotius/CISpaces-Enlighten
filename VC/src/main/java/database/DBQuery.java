/**
 *  This class consists of the SQL queries for creating the tables which enable the support of Version Control within the application.
 *  Note that these are only put here for reference! A script should be written to create ALL tables.
 *  It also includes queries which will be executed on the back-end to accommodate front-end interaction with the program.
 *  The database used is Derby, so all queries are written to support the Derby SQL dialect.
 *
 *  @author Yordanka Ivanova
 *  @since July 2017
 */

package database;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import utils.TimeHelper;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;



public class DBQuery {

    private static final Logger log = Logger.getLogger(DBQuery.class.getName());

    private static final String[] ANALYSIS_TABLES = {
            "CISPACES_GRAPH", "CISPACES_GRAPH_HISTORY", "CISPACES_EDGE",
            "CISPACES_EDGE_HISTORY", "CISPACES_NODE", "CISPACES_NODE_HISTORY"
    };

    /*This object manages the connection to the database and the execution of queries
    * @see DBConnect in the database package
    */
    protected DBConnect dbcn;

    public DBQuery() {
        dbcn = new DBConnect();
    }

    /*
     These methods show the statements used to create the database tables. They are for reference only! A script should be written to create the database tables.
     */
    public void createTableGraph(){
        String query = "CREATE TABLE CISPACES_GRAPH ( graphid varchar (255), userid varchar(255), timest timestamp, title varchar(255), description varchar (5000), isshared boolean, parentgraphid varchar(255), ";
        query+="CONSTRAINT CISPACES_GRAPH_pk PRIMARY KEY (graphid))";
        dbcn.updateSQL(query);
    }

    public void createTableGraphHistory(){
        String query = "CREATE TABLE CISPACES_GRAPH_HISTORY (revisionid varchar(255), graphid varchar(255), userid varchar(255), " +
                "timest timestamp, isshared boolean, parentgraphid varchar(255), ";
        query+="CONSTRAINT GRAPH_SESSION_HISTORY_pk PRIMARY KEY (revisionid))";
        dbcn.updateSQL(query);
    }

    public void createTableGraphHistoryNew(){
        String query = "CREATE TABLE CISPACES_GRAPH_HISTORY (snapid varchar(255), graphid varchar(255), userid varchar(255), " +
                "timest timestamp, analysis clob(2M), title varchar(255), ";
        query+="CONSTRAINT CISPACES_GRAPH_HISTORY_pk PRIMARY KEY (snapid))";
        dbcn.updateSQL(query);
    }

    public void createTableNode(){
        String query="CREATE TABLE CISPACES_NODE ( nodeid varchar(255), source varchar (255), uncert varchar(20), eval varchar(5000), "
                + "txt varchar (5000), inp varchar (20), dtg timestamp, cmt varchar(50), type varchar(10), annot varchar (5000), graphid varchar(255), "
                + "islocked boolean, ";
        query+="CONSTRAINT CISPACES_NODE_pk PRIMARY KEY (nodeid))";
        dbcn.updateSQL(query);
    }

    public void createTableNodeHistory(){
        String query="CREATE TABLE CISPACES_NODE_HISTORY ( nodeid varchar(255), source varchar (255), uncert varchar(20), eval varchar(5000), "
                + "txt varchar (5000), inp varchar (20), dtg timestamp, cmt varchar(50), type varchar(10), annot varchar (5000), graphid varchar(255), "
                + "islocked boolean, revisionid varchar(255), ";
        query+="CONSTRAINT CISPACES_NODE_HISTORY_pk PRIMARY KEY (revisionid))";
        dbcn.updateSQL(query);
    }

    public void createTableEdge(){
        String query="create table cispaces_edge ( edgeID varchar(255), target varchar(255), source varchar(255), formEdgeID varchar(255), graphID varchar(255), islocked boolean, CONSTRAINT CISPACES_EDGE_pk PRIMARY KEY (edgeID))";
        dbcn.updateSQL(query);
    }

    public void createTableEdgeHistory(){
        String query="CREATE TABLE CISPACES_EDGE_HISTORY ( edgeid varchar(255), tonodeid varchar(255), fromnodeid varchar(255), formedgeid varchar(255)," +
                "graphid varchar(255), islocked boolean, revisionid varchar(255), ";
        query+="CONSTRAINT CISPACES_EDGE_pk PRIMARY KEY (revisionid))";
        dbcn.updateSQL(query);
    }

    //shut down the communication between the web service and the database
    public void closeDatabase() {
        dbcn.forceClose();
    }

    //the query to insert a new graph into the database
    public boolean insertGraph(String graphid, String userid, Timestamp timest, String title, String description, boolean isshared, String parentgraphid){
        String sql = "INSERT INTO CISPACES_GRAPH(graphid, userid, timest, title, description, isshared, parentgraphid) VALUES (?, ?, ?, ?, ?, ?, ?)";
        return dbcn.executeUpdate(sql, sqlString(graphid), sqlString(userid), sqlTimestamp(timest),
                sqlString(title), sqlString(description), Boolean.valueOf(isshared), sqlString(parentgraphid));
    }

    //queries to insert a node or update an existing node information in the database
    public void insertNode(String nodeID, String source, String uncert, String eval, String txt, String input,
                           Timestamp timestamp, String commit, String type, String annot, String graphID, Boolean isLocked)
            throws SQLException
    {
        ArrayList<HashMap<String,Object>> rs = dbcn.executeQuery(
                "SELECT nodeid FROM CISPACES_NODE WHERE nodeid = ?", nodeID);
        if(rs.isEmpty()){
            System.out.println("NODE DOESNT EXIST");
            boolean locked = isLocked != null && isLocked.booleanValue();
            dbcn.executeUpdate(
                    "INSERT INTO CISPACES_NODE (nodeid, source, uncert, eval, txt, inp, dtg, cmt, type, annot, graphid, islocked) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    sqlString(nodeID), sqlString(source), sqlString(uncert), sqlString(eval), sqlString(txt),
                    sqlString(input), sqlTimestamp(timestamp), sqlString(commit), sqlString(type), sqlString(annot),
                    sqlString(graphID), Boolean.valueOf(locked));
        }else{
            System.out.println("NODE EXISTS");
            // A missing flag must not clear a lock the client never sent.
            if (isLocked != null) {
                dbcn.executeUpdate(
                        "UPDATE CISPACES_NODE SET uncert = ?, eval = ?, txt = ?, inp = ?, cmt = ?, annot = ?, islocked = ? WHERE nodeid = ?",
                        sqlString(uncert), sqlString(eval), sqlString(txt), sqlString(input), sqlString(commit),
                        sqlString(annot), Boolean.valueOf(isLocked.booleanValue()), sqlString(nodeID));
            } else {
                dbcn.executeUpdate(
                        "UPDATE CISPACES_NODE SET uncert = ?, eval = ?, txt = ?, inp = ?, cmt = ?, annot = ? WHERE nodeid = ?",
                        sqlString(uncert), sqlString(eval), sqlString(txt), sqlString(input), sqlString(commit),
                        sqlString(annot), sqlString(nodeID));
            }
        }
    }

    //insert a node in the history table or update the information if it exists
    public void insertNodeHistory(String nodeID, String source, String uncert, String eval, String txt, String input,
                           Timestamp timestamp, String commit, String type, String annot, String graphID, boolean isLocked, String revisionID)
            throws SQLException
    {
        ArrayList<HashMap<String,Object>> rs = dbcn.executeQuery(
                "SELECT nodeid FROM CISPACES_NODE_HISTORY WHERE nodeid = ?", nodeID);
        if(rs.isEmpty()){
            System.out.println("NODE DOESNT EXIST IN HISTORY TABLE");
            dbcn.executeUpdate(
                    "INSERT INTO CISPACES_NODE_HISTORY (nodeid, source, uncert, eval, txt, inp, dtg, cmt, type, annot, graphid, islocked, revisionid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    sqlString(nodeID), sqlString(source), sqlString(uncert), sqlString(eval), sqlString(txt),
                    sqlString(input), sqlTimestamp(timestamp), sqlString(commit), sqlString(type), sqlString(annot),
                    sqlString(graphID), Boolean.valueOf(isLocked), sqlString(revisionID));
        }else{
            System.out.println("NODE EXISTS IN HISTORY TABLE");
            dbcn.executeUpdate(
                    "UPDATE CISPACES_NODE_HISTORY SET uncert = ?, eval = ?, txt = ?, inp = ?, cmt = ?, annot = ?, islocked = ? WHERE nodeid = ?",
                    sqlString(uncert), sqlString(eval), sqlString(txt), sqlString(input), sqlString(commit),
                    sqlString(annot), Boolean.valueOf(isLocked), sqlString(nodeID));
        }
    }

    //insert an edge into the database - edges are never updated!
    public void insertEdge(String toID, String fromID, String formEdgeID, String edgeID, String graphID, boolean isLocked) throws SQLException {
        ArrayList<HashMap<String,Object>> rs = dbcn.executeQuery(
                "SELECT edgeid FROM CISPACES_EDGE WHERE edgeid = ?", edgeID);
        if(rs.isEmpty()) {
            System.out.println("EDGE DOESNT EXIST");
            dbcn.executeUpdate(
                    "INSERT INTO CISPACES_EDGE (edgeID, target, source, formEdgeID, graphID, islocked) VALUES (?, ?, ?, ?, ?, ?)",
                    sqlString(edgeID), sqlString(toID), sqlString(fromID), sqlString(formEdgeID),
                    sqlString(graphID), Boolean.valueOf(isLocked));
        }
    }

    //populate the edge history table
    public void insertEdgeHistory(String toID, String fromID, String formEdgeID, String edgeID, String graphID, boolean isLocked, String revisionID) throws SQLException {
        ArrayList<HashMap<String,Object>> rs = dbcn.executeQuery(
                "SELECT edgeid FROM CISPACES_EDGE_HISTORY WHERE edgeid = ?", edgeID);
        if(rs.isEmpty()) {
            System.out.println("EDGE DOESNT EXIST IN HISTORY TABLE");
            dbcn.executeUpdate(
                    "INSERT INTO CISPACES_EDGE_HISTORY (edgeid, tonodeid, fromnodeid, formedgeid, graphid, islocked, revisionid) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    sqlString(edgeID), sqlString(toID), sqlString(fromID), sqlString(formEdgeID),
                    sqlString(graphID), Boolean.valueOf(isLocked), sqlString(revisionID));
        }
    }

    /**
     * Delete the analysis, all nodes, edges and provenance
     * @param graphID the analysis to delete
     * @return True if the operation succeeded, false if it did not
     */
    public boolean deleteAnalysis(String graphID) {
        //ToDo - get nodeIds and delete from CISPACES_INFOPROV
        for(String table : ANALYSIS_TABLES) {
            // table is a fixed whitelist, not request data
            String sql = "DELETE FROM " + table + " WHERE graphid = ?";
            if(!dbcn.executeUpdate(sql, graphID)) {
            System.out.println("Failed to delete Graph from "+table);
            return false;
            }
        }
       return true;       
    }
        
    //delete an edge from the database
    public boolean deleteEdge(String edgeid) {
        return dbcn.executeUpdate("DELETE FROM CISPACES_EDGE WHERE edgeid = ?", edgeid);
    }

    //delete a node from the database
    public boolean deleteNode(String nodeid) {
        return dbcn.executeUpdate("DELETE FROM CISPACES_NODE WHERE nodeid = ?", nodeid);
    }

    /**
     * @return the owning user id, or null when the graph does not exist
     */
    public String getGraphOwner(String graphID) throws SQLException {
        if (graphID == null) {
            return null;
        }
        ArrayList<HashMap<String,Object>> rs = dbcn.executeQuery(
                "SELECT USERID FROM CISPACES_GRAPH WHERE GRAPHID = ?", graphID);
        if (rs.isEmpty() || rs.get(0).get("userid") == null) {
            return null;
        }
        return rs.get(0).get("userid").toString();
    }

    /**
     * The owner may read. A graph with {@code isshared} set may be read by any authenticated caller.
     */
    public boolean callerMayRead(String graphID, String userId) throws SQLException {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }
        String owner = getGraphOwner(graphID);
        if (owner != null && owner.trim().equals(userId.trim())) {
            return true;
        }
        return isGraphShared(graphID);
    }

    private boolean isGraphShared(String graphID) throws SQLException {
        if (graphID == null) {
            return false;
        }
        ArrayList<HashMap<String,Object>> rs = dbcn.executeQuery(
                "SELECT ISSHARED FROM CISPACES_GRAPH WHERE GRAPHID = ?", graphID);
        if (rs.isEmpty() || rs.get(0).get("isshared") == null) {
            return false;
        }
        Object flag = rs.get(0).get("isshared");
        if (flag instanceof Boolean) {
            return ((Boolean) flag).booleanValue();
        }
        return Boolean.parseBoolean(flag.toString().trim());
    }

    public String graphIdOfNode(String nodeID) throws SQLException {
        return lookupGraphId("SELECT GRAPHID FROM CISPACES_NODE WHERE NODEID = ?", nodeID);
    }

    public String graphIdOfEdge(String edgeID) throws SQLException {
        return lookupGraphId("SELECT GRAPHID FROM CISPACES_EDGE WHERE EDGEID = ?", edgeID);
    }

    private String lookupGraphId(String sql, String id) throws SQLException {
        if (id == null) {
            return null;
        }
        ArrayList<HashMap<String,Object>> rs = dbcn.executeQuery(sql, id);
        if (rs.isEmpty() || rs.get(0).get("graphid") == null) {
            return null;
        }
        return rs.get(0).get("graphid").toString();
    }

    /**
     * Fetch everything from CISPACES_USERS for the supplied username
     * @param username The username of interest
     * @return a JSON representation of the record, null if none
     */
    public JSONObject getUserByUsername(String username) throws SQLException {
        ArrayList<HashMap<String,Object>> rs = dbcn.executeQuery(
                "SELECT * FROM CISPACES_USERS WHERE USERNAME = ?", username);
        if(rs.isEmpty()) return null;
        else return (JSONObject) getResultListJSON(rs).get(0);
    }
    
    /**
     * Add a user with the supplied username, password hash and affiliation
     * @param username The chosen username
     * @param passwordHash The password hash
     * @param affiliation The affiliation
     * @return The newly generated user_id, or null if the user could not be added
     */
    public String insertUser(String username, String generatedSecuredPasswordHash, String affiliation) throws SQLException {
        ArrayList<HashMap<String,Object>> rs = dbcn.executeQuery(
                "SELECT USER_ID FROM CISPACES_USERS WHERE USERNAME = ?", username);
        if(rs.isEmpty()){
            //Username doesn't exist, try to add it        
            String userID = UUID.randomUUID().toString();
            String sql = "INSERT INTO CISPACES_USERS (USER_ID, USERNAME, PASSWORD, AFFILIATION, IS_ADMIN) VALUES (?, ?, ?, ?, ?)";
            if(dbcn.executeUpdate(sql, userID, sqlString(username), sqlString(generatedSecuredPasswordHash), sqlString(affiliation), Integer.valueOf(0))){
                return userID; // User added successfully
            }
            else{
                System.out.println("Failed to add user.");
                return null;
            } // Failed to add - we should probably throw an exception here
        }
        else throw new IllegalArgumentException("The selected username is already in use");        
    }//insertUser

    /**
     * Return metadata for analyses belonging to the supplied user
     * @param userID The user
     * @return The analyses as a JSON formatted string
     */
    public String getAnalysesMetaByUser(String userID) throws SQLException {
        ArrayList<HashMap<String,Object>> rs = dbcn.executeQuery(
                "SELECT * FROM CISPACES_GRAPH WHERE USERID = ?", userID);
        JSONArray jsonAnalysesMetaArray = new JSONArray();
        for(HashMap graphIdMap : rs) { 
            JSONObject jsonGraph = new JSONObject();
            jsonGraph.put("graphID", (String) graphIdMap.get("graphid"));
            jsonGraph.put("userID", (String) graphIdMap.get("userid"));
            jsonGraph.put("title", (String) graphIdMap.get("title"));
            jsonGraph.put("description", (String) graphIdMap.get("description"));
            jsonGraph.put("timest", (String) graphIdMap.get("timest"));
            jsonGraph.put("isshared", (String) graphIdMap.get("isshared"));
            jsonGraph.put("parentgraphid", (String) graphIdMap.get("parentgraphid"));
            jsonAnalysesMetaArray.add(jsonGraph);
           
            String graphId = (String) graphIdMap.get("graphid");
            System.out.println(graphId);                      
        }
        return jsonAnalysesMetaArray.toJSONString();
    }
    
    /**
     * Return metadata for an analysis as JSON, based on a graph id.
     * @param graphID The graph id
     * @return The analysis metadata as a JSON formatted string
     */
    public String getAnalysisMeta(String graphID) throws SQLException {
        System.out.println("Fetching Analysis metadata for graphid: "+graphID);
        ArrayList<HashMap<String, Object>> graphMeta = dbcn.executeQuery(
                "SELECT * FROM CISPACES_GRAPH WHERE GRAPHID = ?", graphID);
        
        if(!graphMeta.isEmpty()) {
            JSONObject jsonGraph = new JSONObject();
            jsonGraph.put("graphID", (String) graphMeta.get(0).get("graphid"));
            jsonGraph.put("userID", (String) graphMeta.get(0).get("userid"));
            jsonGraph.put("title", (String) graphMeta.get(0).get("title"));
            jsonGraph.put("description", (String) graphMeta.get(0).get("description"));
            jsonGraph.put("timest", (String) graphMeta.get(0).get("timest"));
            jsonGraph.put("isshared", (String) graphMeta.get(0).get("isshared"));
            jsonGraph.put("parentgraphid", (String) graphMeta.get(0).get("parentgraphid"));  
            return jsonGraph.toString();
        }
        else {
            return null; //Not found
        }
    }//getAnalysis

    /**
     * Metadata for a caller who owns the graph or is reading a shared graph.
     * @return null when the graph is missing or the caller may not read it
     */
    public String getAnalysisMeta(String graphID, String caller) throws SQLException {
        if (!callerMayRead(graphID, caller)) {
            return null;
        }
        return getAnalysisMeta(graphID);
    }
    
    /**
     * Return an analysis as JSON, based on a graph id.
     * @param graphID The graph id
     * @return The analysis as a JSON formatted string
     */
    public String getAnalysis(String graphID) throws SQLException {
        System.out.println("Fetching Analysis for graphid: "+graphID);
        ArrayList<HashMap<String, Object>> graphMeta = dbcn.executeQuery(
                "SELECT * FROM CISPACES_GRAPH WHERE GRAPHID = ?", graphID);
        
        if(!graphMeta.isEmpty()) {
            JSONObject jsonGraph = new JSONObject();
            jsonGraph.put("graphID", (String) graphMeta.get(0).get("graphid"));
            jsonGraph.put("userID", (String) graphMeta.get(0).get("userid"));
            jsonGraph.put("title", (String) graphMeta.get(0).get("title"));
            jsonGraph.put("description", (String) graphMeta.get(0).get("description"));
            jsonGraph.put("timest", (String) graphMeta.get(0).get("timest"));
            jsonGraph.put("isshared", (String) graphMeta.get(0).get("isshared"));
            jsonGraph.put("parentgraphid", (String) graphMeta.get(0).get("parentgraphid"));            
            
            ArrayList<HashMap<String, Object>> resultNodes = dbcn.executeQuery(
                    "SELECT * FROM CISPACES_NODE WHERE GRAPHID = ?", graphID);
            JSONArray jsonNodesArray = getResultListJSON(resultNodes);       
            jsonGraph.put("nodes", jsonNodesArray);           
            
            ArrayList<HashMap<String, Object>> resultEdges = dbcn.executeQuery(
                    "SELECT * FROM CISPACES_EDGE WHERE GRAPHID = ?", graphID);
            JSONArray jsonEdgesArray = getResultListJSON(resultEdges);
            jsonGraph.put("edges", jsonEdgesArray);

            return jsonGraph.toString();
        }
        else {
            return null; //Not found
        }
    }//getAnalysis

    /**
     * Full analysis for a caller who owns the graph or is reading a shared graph.
     * @return null when the graph is missing or the caller may not read it
     */
    public String getAnalysis(String graphID, String caller) throws SQLException {
        if (!callerMayRead(graphID, caller)) {
            return null;
        }
        return getAnalysis(graphID);
    }
   
    //DEPRECATED REMOVE SOON
//    /* Retrieves the latest analysis associated with a user upon loading the index page.
//     * Checks if a user has worked on an analysis before.
//     * If the user is new, then a new graph (analysis) is created and linked to them.
//     * If the user has worked on an analysis before, a JSON object is constructed with
//     * the nodes and edges linked to that analysis. The JSON object is turned into a JSON string
//     * and passed to the client to visualize it in the work box.
//     */
//    public String getLatestAnalysis(String userID) {
//        System.out.println("THE USER ID IS " + userID);
//        String sql = "SELECT GRAPHID FROM CISPACES_GRAPH WHERE USERID = " +  "'"  + userID +  "'";
//        System.out.println(sql);
//        JSONObject jsonGraph = new JSONObject();
//        ArrayList<HashMap<String,Object>> rs = dbcn.execSQL(sql);
//        if(rs.isEmpty()){
//            TimeHelper timeHelper = new TimeHelper();
//            boolean isShared = false;
//            String parentID = null;
//            Date now = new Date();
//            Timestamp timestamp = timeHelper.formatDateObjectCIS(now);
//            String graphID = UUID.randomUUID().toString();
//
//            //create new analysis and pass graphID to the client
//            String newGraphQuery = "INSERT INTO CISPACES_GRAPH(graphid, userid, timest, isshared, parentgraphid) VALUES "
//                    + "( '" + graphID + "' ,"
//                    + " '" + userID + "' ,"
//                    + " '" + timestamp + "' ,"
//                    + " '" + isShared + "' ,"
//                    + " '" + parentID + "'"
//                    + " )";
//            dbcn.updateSQL(newGraphQuery);
//            jsonGraph.put("graphID",graphID);
//            jsonGraph.put("nodes", new JSONArray());
//            jsonGraph.put("edges", new JSONArray());
//            return jsonGraph.toString();
//        }else {
//            String idQuery = "SELECT GRAPHID FROM CISPACES_GRAPH WHERE TIMEST = (SELECT MAX(TIMEST) FROM CISPACES_GRAPH WHERE USERID = " + "'" + userID + "'" + ")";
//            String resultIDJSON = dbcn.execSQL(idQuery).get(0).toString();
//            String resultID = resultIDJSON.substring(9, resultIDJSON.length() - 1);
//            String getNodesSql = "SELECT * FROM CISPACES_NODE WHERE GRAPHID = " + "'" + resultID + "'";
//            String getEdgesSql = "SELECT * FROM CISPACES_EDGE WHERE GRAPHID = " + "'" + resultID + "'";
//            System.out.println("THE GRAPH ID FROM THE QUERY IS " + resultID);
//            ArrayList<HashMap<String, Object>> resultNodes = dbcn.execSQL(getNodesSql);
//            ArrayList<HashMap<String, Object>> resultEdges = dbcn.execSQL(getEdgesSql);
//
//            JSONArray jsonNodesArray = getResultListJSON(resultNodes);
//            jsonGraph.put("nodes", jsonNodesArray);
//
//            JSONArray jsonEdgesArray = getResultListJSON(resultEdges);
//            jsonGraph.put("edges", jsonEdgesArray);
//
//            return jsonGraph.toString();
//        }
//
//    }
    /*A number of queries used to store an analysis in the database.
    * Retrieves all nodes and edges connected to a graph by its graph id.
    * Constructs a json object and populates it with the JSON arrays of the nodes and edges.
    * Timestamps the event and inserts the data into the database.
    */
     public String saveLatestAnalysis(String graphID, String userID, String title) throws SQLException {
        JSONObject jsonGraph = new JSONObject();
        jsonGraph.put("graphID",graphID);
        ArrayList<HashMap<String,Object>> resultNodes = dbcn.executeQuery(
                "SELECT * FROM CISPACES_NODE WHERE GRAPHID = ?", graphID);
        ArrayList<HashMap<String,Object>> resultEdges = dbcn.executeQuery(
                "SELECT * FROM CISPACES_EDGE WHERE GRAPHID = ?", graphID);

        JSONArray jsonNodesArray =  getResultListJSON(resultNodes);
        jsonGraph.put("nodes",jsonNodesArray);


        JSONArray jsonEdgesArray = getResultListJSON(resultEdges);
        jsonGraph.put("edges",jsonEdgesArray);

        Date now = new Date();
        TimeHelper timeHelper = new TimeHelper();
        String json =  jsonGraph.toString().replace("nodeid", "nodeID");
        json = json.replace("N\\/A", "N/A");
        String snapID = UUID.randomUUID().toString();
        Timestamp timestamp = timeHelper.formatDateObjectCIS(now);

        String saveGraphQuery = "INSERT INTO CISPACES_GRAPH_HISTORY(snapid, graphid, userid, timest, analysis, title) VALUES (?, ?, ?, ?, ?, ?)";
        // analysis is a CLOB; insertClob binds it with setString rather than embedding the JSON in quotes
        boolean isExecuted = dbcn.insertClob(saveGraphQuery, snapID, graphID, userID, timestamp, json, sqlString(title));
        JSONObject result = new JSONObject();
        result.put("status",isExecuted);

        return result.toString();
    }

    /*helper method to iterate through the result set obtained after executing a query
    *the result sets are a list of hashmaps which have table column titles as keys and database entries as values
    */
    public JSONArray getResultListJSON(ArrayList<HashMap<String,Object>> resultEntities){
        JSONArray jsonArray = new JSONArray();
        for(HashMap<String,Object> edgesEntry : resultEntities){
            Iterator entryIterator = edgesEntry.entrySet().iterator();
            JSONObject obj = new JSONObject(); // the node or edge
            while (entryIterator.hasNext()) {
                //Each property of the node or edge
                Map.Entry pair = (Map.Entry)entryIterator.next();
                if (pair.getValue() == null) {
                    obj.put(pair.getKey(), null);
                    continue;
                }
                //these are reserved words in derby!!!                    
                if(pair.getKey().toString().equals("annot")){                        
                    // Expand the annot and set as a JSONArray                        
                    String annotString = pair.getValue().toString();
                    if(annotString.equals("{}")) continue; //don't care
                    annotString = annotString.replaceAll("\\{", "");
                    annotString = annotString.replaceAll("\\}", "");
                    String[] annotations = annotString.split(",");
                    //JSONArray jaAnnots = new JSONArray();
                    JSONObject joAnnots = new JSONObject();
                    for(String annotation : annotations) {
                        //Split into k,v
                        String[] annotParts = annotation.split("=");
                        String annotKey = annotParts[0];
                        String annotValue = annotParts[1];
                        //JSONObject joAnnot = new JSONObject();
                        joAnnots.put(annotKey, annotValue);
                        //jaAnnots.add(joAnnot);                           
                    }
                    //joAnnots.put("annot", jaAnnots);
                    //jsonArray.add(joAnnots);
                    obj.put("annot", joAnnots);
                } else {                    
                    //not an annot
                    if(pair.getKey().toString().equals("dtg")){
                        // Abuse of the Derby Timestamp field elsewhere can result in dates being stored as strings which are
                        // unparsable by getTimestamp().  So, getString() is used in in DBConnect.convertResultSetToList()
                        // The output can have a trailing .0 which ERS is not expecting and complains. Loudly.
                        // Here we remove the trailing badness.  This is a nasty cludge and does not make me proud.
                        if(pair.getValue().toString().endsWith(".0")) {
                            String badDate = pair.getValue().toString();
                            String goodDate = badDate.substring(0, badDate.length()-2);
                            obj.put("dtg", goodDate);
                        }
                        else obj.put("dtg", pair.getValue().toString());
                    }
                    else if(pair.getKey().toString().equals("inp")){
                        obj.put("input", pair.getValue().toString());
                    }else if(pair.getKey().toString().equals("txt")) {
                        obj.put("text", pair.getValue().toString());
                    }else if(pair.getKey().toString().equals("nodeid")){
                        obj.put("nodeID", pair.getValue().toString());
                    }else if(pair.getKey().toString().equals("edgeid")){
                        obj.put("edgeID", pair.getValue().toString());
                    }else if(pair.getKey().toString().equals("graphid")){
                        obj.put("graphID", pair.getValue().toString());                                  
                    }else {
                        obj.put(pair.getKey(), pair.getValue().toString());
                    }                    
                }//else                
            }//while each node property
            jsonArray.add(obj);
        }//for each node 

        return jsonArray;
    }

        //constructs a json object containing all saved variations of an analysis and returns it in a json string format
    public String getHistoryOfAnalysis(String graphID) throws SQLException {
        ArrayList<HashMap<String,Object>> rs = dbcn.executeQuery(
                "SELECT * FROM CISPACES_GRAPH_HISTORY WHERE GRAPHID = ? ORDER BY TIMEST", graphID);
        JSONObject jsonHistory = new JSONObject();
        if(!rs.isEmpty()){
            JSONArray jsonHistoryArray = getResultListJSON(rs);
            jsonHistory.put("history",jsonHistoryArray);

        }else{
            jsonHistory.put("history", new JSONArray());

        }

        return jsonHistory.toString();
    }

    /**
     * History for a caller who owns the graph or is reading a shared graph.
     * @return null when the graph is missing or the caller may not read it
     */
    public String getHistoryOfAnalysis(String graphID, String caller) throws SQLException {
        if (!callerMayRead(graphID, caller)) {
            return null;
        }
        return getHistoryOfAnalysis(graphID);
    }

    /**
     * Replace the live nodes and edges of a graph with a history snapshot.
     * Deletes and inserts share one connection with auto-commit off. A failed insert rolls the deletes back.
     * @return false when the snapshot was not applied
     */
    public boolean updateAnalysis(HashMap analysis) {
        if (analysis == null) {
            return false;
        }
        org.json.JSONObject obj;
        try {
            obj = new org.json.JSONObject(analysis);
        } catch (RuntimeException ex) {
            log.log(Level.SEVERE, "updateAnalysis rejected a snapshot that is not an object", ex);
            return false;
        }
        if (!obj.has("graphID") || obj.isNull("graphID") || !obj.has("nodes") || !obj.has("edges")) {
            log.log(Level.SEVERE, "updateAnalysis snapshot is missing graphID, nodes, or edges");
            return false;
        }
        String oldGraphID = obj.getString("graphID").trim();
        if (oldGraphID.isEmpty() || !elementsTargetGraph(obj, oldGraphID)) {
            log.log(Level.SEVERE, "updateAnalysis rejected a snapshot whose node or edge graphID does not match the owned graph");
            return false;
        }

        if (!dbcn.beginTransaction()) {
            return false;
        }
        try {
            if (!dbcn.executeUpdate("DELETE FROM CISPACES_NODE WHERE GRAPHID = ?", oldGraphID)) {
                dbcn.rollback();
                return false;
            }
            if (!dbcn.executeUpdate("DELETE FROM CISPACES_EDGE WHERE GRAPHID = ?", oldGraphID)) {
                dbcn.rollback();
                return false;
            }

            org.json.JSONArray nodes = obj.getJSONArray("nodes");
            for (int i = 0; i < nodes.length(); i++) {
                org.json.JSONObject node = nodes.getJSONObject(i);
                String nodeID = tryToGet(node, "nodeID");
                String commit = firstString(node, "commit", "cmt");
                if (!dbcn.executeUpdate(
                        "INSERT INTO CISPACES_NODE (nodeid, source, uncert, eval, txt, inp, dtg, cmt, type, annot, graphid, islocked) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        sqlString(nodeID),
                        sqlString(tryToGet(node, "source")),
                        sqlString(tryToGet(node, "uncert")),
                        sqlString(tryToGet(node, "eval")),
                        sqlString(tryToGet(node, "text")),
                        sqlString(tryToGet(node, "input")),
                        sqlTimestamp(parseTimestamp(tryToGet(node, "dtg"))),
                        sqlString(commit),
                        sqlString(tryToGet(node, "type")),
                        sqlString(annotForStorage(node)),
                        sqlString(oldGraphID),
                        Boolean.valueOf(parseBooleanFlag(firstString(node, "islocked", "isLocked"))))) {
                    dbcn.rollback();
                    return false;
                }
            }

            org.json.JSONArray edges = obj.getJSONArray("edges");
            for (int i = 0; i < edges.length(); i++) {
                org.json.JSONObject edge = edges.getJSONObject(i);
                if (!dbcn.executeUpdate(
                        "INSERT INTO CISPACES_EDGE (edgeID, target, source, formEdgeID, graphID, islocked) VALUES (?, ?, ?, ?, ?, ?)",
                        sqlString(firstString(edge, "edgeID", "edgeid")),
                        sqlString(tryToGet(edge, "target")),
                        sqlString(tryToGet(edge, "source")),
                        sqlString(firstString(edge, "formedgeid", "formEdgeID")),
                        sqlString(oldGraphID),
                        Boolean.valueOf(parseBooleanFlag(firstString(edge, "islocked", "isLocked"))))) {
                    dbcn.rollback();
                    return false;
                }
            }

            return dbcn.commitTransaction();
        } catch (RuntimeException ex) {
            log.log(Level.SEVERE, "updateAnalysis failed and was rolled back", ex);
            dbcn.rollback();
            return false;
        }
    }


    /**
     * Every node and edge must name the snapshot graph, or omit a graph id.
     * A different id is rejected so an import cannot write into another analysis.
     */
    public static boolean elementsTargetGraph(org.json.JSONObject snapshot, String graphID) {
        if (snapshot == null || graphID == null || graphID.trim().isEmpty()) {
            return false;
        }
        String expected = graphID.trim();
        return arrayTargetsGraph(snapshot.optJSONArray("nodes"), expected)
                && arrayTargetsGraph(snapshot.optJSONArray("edges"), expected);
    }

    private static boolean arrayTargetsGraph(org.json.JSONArray elements, String expected) {
        if (elements == null) {
            return false;
        }
        for (int i = 0; i < elements.length(); i++) {
            org.json.JSONObject element = elements.optJSONObject(i);
            if (element == null) {
                return false;
            }
            String nested = firstString(element, "graphID", "graphid");
            if (nested != null && !nested.trim().equals(expected)) {
                return false;
            }
        }
        return true;
    }

    public static String tryToGet(org.json.JSONObject jsonObj, String key) {
        if (jsonObj == null || key == null || !jsonObj.has(key) || jsonObj.isNull(key)) {
            return null;
        }
        Object value = jsonObj.opt(key);
        return value == null ? null : value.toString();
    }

    /**
     * History JSON stores annot as an object. The reader still expects the stored
     * form {@code key=value}, which is what a live node insert writes.
     */
    private static String annotForStorage(org.json.JSONObject node) {
        if (node == null || !node.has("annot") || node.isNull("annot")) {
            return null;
        }
        Object raw = node.opt("annot");
        if (!(raw instanceof org.json.JSONObject)) {
            return raw.toString();
        }
        org.json.JSONObject annot = (org.json.JSONObject) raw;
        if (annot.length() == 0) {
            return "{}";
        }
        StringBuilder stored = new StringBuilder();
        Iterator<?> keys = annot.keys();
        while (keys.hasNext()) {
            String key = String.valueOf(keys.next());
            if (stored.length() > 0) {
                stored.append(',');
            }
            stored.append(key).append('=').append(annot.opt(key));
        }
        return stored.toString();
    }

    private static String firstString(org.json.JSONObject jsonObj, String... keys) {
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            String value = tryToGet(jsonObj, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static boolean parseBooleanFlag(String value) {
        return value != null && Boolean.parseBoolean(value.trim());
    }

    private static Timestamp parseTimestamp(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        if (trimmed.endsWith(".0")) {
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        }
        return Timestamp.valueOf(trimmed);
    }

    private static Object sqlString(String value) {
        return value == null ? SqlNull.varchar() : value;
    }

    private static Object sqlTimestamp(Timestamp value) {
        return value == null ? SqlNull.timestamp() : value;
    }
}
