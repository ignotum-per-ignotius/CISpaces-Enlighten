/**
 * This class contains the methods that handle the HTTP requests coming from the front-end.
 *
 * @author Yordanka Ivanova
 * @author j.s.robinson@software.ac.uk
 * @since July 2017
 */

package vcservlet;

import database.DBQuery;
import filters.JWTTokenNeeded;
import filters.JWTTokenNeededFilter;
import filters.JwtSubjects;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.logging.Level;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import org.json.JSONObject;
import java.security.Key;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.logging.Logger;
import filters.KeyGenerator;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import static javax.ws.rs.core.HttpHeaders.AUTHORIZATION;


/**
 * URL is http://localhost:8080/VC/rest/ for the server side
 */
@Path("/")
public class VCServlet {

    @Context
    private UriInfo uriInfo;

    @Context
    private ContainerRequestContext requestContext;

    @PersistenceContext
    private EntityManager em;
    
    //a logger which prints to stdout
    private static Logger log;

    public VCServlet(){
        log = Logger.getLogger(getClass().getName());
    }

    //this method is here just to make sure the service is up and running
    //URL http://localhost:8080/VC/rest/getInfo
    @GET
    @Path("/getInfo")
    @Produces("text/html")
    public String sayHello(){
        return "Hello Jersey";
    }
    
    /**
     * @param graphID a graph id
     * @return all saved variations of an analysis which can be imported into the work box
     * URL: http://localhost:8080/VC/rest/history
     */
    @GET
    @Path("/history")
    @JWTTokenNeeded
    @Produces(MediaType.APPLICATION_JSON)
    public Response getHistory(@CookieParam("graph_id") String graphID){
        log.log(Level.INFO, "*** VERSION CONTROL SERVICE - GET HISTORY REQUEST ***");
        String caller = authenticatedUserId();
        try {
            String response = new DBQuery().getHistoryOfAnalysis(graphID, caller);
            return readAnalysisForCaller(graphID, response);
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    
//    DEPRECATED - REMOVE SOON
//    
//    /**
//     * @param userID a user id coming from the client
//     * @return either a json representing the latest analysis this user has worked on, or a new graph id for starting a new analysis
//     * URL: http://localhost:8080/VC/rest/getAnalysis
//     */
//    @POST
//    @Path("/getAnalysis")
//    //@JWTTokenNeeded
//    @Produces(MediaType.APPLICATION_JSON)
//    public String getLatestAnalysis(String userID)
//    {
//        log.log(Level.INFO, "*** VERSION CONTROL SERVICE - GET LATEST ANALYSIS REQUEST ***");
//        log.log(Level.INFO, "user id payload " + userID);
//        DBQuery dbQuery = new DBQuery();
//        String response = dbQuery.getLatestAnalysis(userID);
//
//        return response;
//    }
    

    /**
     * Fetch metadata for *all* graphs owned by supplied user
     * @param userID The owner
     * @return A JSON formatted string comprising an array of graph metadata items. e.g.
     * {[{
        "timest": "2017-11-15 14:27:12",
        "isshared": "false",
        "parentgraphid": "null",
        "description": "Desc. 1",
        "graphID": "d9f2fddc-9a37-49bd-87d8-10a24b7fb20f",
        "title": "title 1",
        "userID": "5a90c91f-1884-4f45-bc2e-49057745293c"
    },]}
     */
    @GET
    @Path("/analyses/user/{userID}/meta")
    @JWTTokenNeeded
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAnalysesMetaByUserId(@PathParam("userID") String userID) {       
        log.log(Level.INFO, "*** VERSION CONTROL SERVICE - GET ANALYSES METADATA REQUEST");
        if(userID != null) {
            requireCaller(userID);
            log.log(Level.INFO, "** USER_ID="+userID);
            DBQuery dbquery = new DBQuery();
            try {
                String analyses = dbquery.getAnalysesMetaByUser(userID);
                return Response.ok()
                    .entity(analyses)
                    .build();
            } catch (SQLException e) {
                throw databaseError(e);
            }            
        }
        else return Response.status(Response.Status.NOT_FOUND).build();
        // ToDo - There's scope here for checking the user exists before looking for analyses -
        // Because that isn't done we end up returning 200 and an empty list
    }//getAnalysesMetaByUserId
    
    /**
     * Fetch metadata for the supplied graphID
     * @param graphID The graph
     * @return A JSON formatted string comprising metadata items. e.g.
     * {
        "timest": "2017-11-15 14:27:12",
        "isshared": "false",
        "parentgraphid": "null",
        "description": "Desc. 1",
        "graphID": "d9f2fddc-9a37-49bd-87d8-10a24b7fb20f",
        "title": "title 1",
        "userID": "5a90c91f-1884-4f45-bc2e-49057745293c"
        }     
    */
    @GET
    @Path("/analysis/{graphID}/meta")
    @JWTTokenNeeded
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAnalysisMeta(@PathParam("graphID") String graphID) {       
        log.log(Level.INFO, "*** VERSION CONTROL SERVICE - GET ANALYSIS METADATA REQUEST");
        if(graphID != null) {
            log.log(Level.INFO, "** GRAPH_ID="+graphID);
        }
        String caller = authenticatedUserId();
        try {
            String analysis = new DBQuery().getAnalysisMeta(graphID, caller);
            return readAnalysisForCaller(graphID, analysis);
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }//getAnalysisMeta
    
    
    /**
     * Fetch full data (metadata, nodes and edges) for the requested graphID
     * @param graphID The graph
     * @return A JSON formatted string e.g.
     * {"timest": "2017-11-15 14:28:40.0",
        "isshared": "false",
        "nodes":
        [
        ],
        "parentgraphid": "null",
        "edges":
        [
        ],
        "description": "Desc 4",
        "graphID": "842b9eef-8a21-4ad5-ff3b-f5f2931ff37a",
        "title": "Title 4",
        "userID": "5a90c91f-1884-4f45-bc2e-49057745293c"  }
    */
    @GET
    @Path("/analysis/{graphID}")
    @JWTTokenNeeded
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAnalysis(@PathParam("graphID") String graphID) {       
        log.log(Level.INFO, "*** VERSION CONTROL SERVICE - GET ANALYSIS REQUEST");
        if(graphID != null) {
            log.log(Level.INFO, "** GRAPH_ID="+graphID);
        }
        String caller = authenticatedUserId();
        try {
            String analysis = new DBQuery().getAnalysis(graphID, caller);
            return readAnalysisForCaller(graphID, analysis);
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }//getAnalysis    
    
    @DELETE
    @Path("/analysis/{graphID}")
    @JWTTokenNeeded
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteAnalysis(@PathParam("graphID") String graphID) {       
        log.log(Level.INFO, "*** VERSION CONTROL SERVICE - DELETE ANALYSIS REQUEST");
        if(graphID != null) {
            requireGraphOwner(graphID);
            log.log(Level.INFO, "** GRAPH_ID="+graphID);
            DBQuery dbquery = new DBQuery();           
            if(dbquery.deleteAnalysis(graphID)) return Response.ok().build();
            else return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
        else return Response.status(Response.Status.NOT_FOUND).build();
    }//getAnalysis    
    
    /*
    //ToDo - Secure this
    @GET
    @Path("/analysis/{analysisID}/export")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response exportAnalysis(@PathParam("analysisID") String analysisID) {       
        log.log(Level.INFO, "*** VERSION CONTROL SERVICE - EXPORT ANALYSIS REQUEST");
        if(analysisID != null) {
            try {
                log.log(Level.INFO, "** ANALYSIS_ID="+analysisID);
                DBQuery dbquery = new DBQuery();
                String analysis = dbquery.getAnalysis(analysisID);
                
                File file = new File(analysisID+".cis");
                FileWriter fileWriter = new FileWriter(file);
                fileWriter.write(analysis);
                fileWriter.flush();
                fileWriter.close();                
                return Response.ok(file, MediaType.APPLICATION_OCTET_STREAM)
                    .header("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"" )
                    .build();
            } catch (IOException ex) {
                log.log(Level.WARNING, "*** VERSION CONTROL SERVICE - EXPORT ANALYSIS REQUEST Failed due to an IOException");
                ex.printStackTrace();
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();                    
            }//catch            
        }//if
        else return Response.status(Response.Status.NOT_FOUND).build();
        
    }
    */

    /**
     * Authenticate the user
     * @param userData JSON String of form {"username":"Test user","password":"password"}
     * @return a response indicating whether the user has been validated
     * URL: http://localhost:8080/VC/rest/user
     */
    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response checkUser(String userData){
        log.log(Level.INFO, "*** VERSION CONTROL SERVICE - Login Request ***");
        VCForkControl vcForkControl = new VCForkControl();
        String userInfo;
        try {
            userInfo = vcForkControl.evalJSONLoginUser(userData);
        } catch (SQLException e) {
            throw databaseError(e);
        }
        if(userInfo != null) {
            
            // Subject is the user id so later requests can be checked against the graph owner.
            String userId = JwtSubjects.userId(userInfo);
            if (userId == null || userId.isEmpty()) {
                log.log(Level.SEVERE, "Authenticated user record has no user_id");
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
            }
            String token = issueToken(userId);
 
            // Return the token on the response
            return Response.ok()
                .header(AUTHORIZATION, "Bearer " + token)
                .entity(userInfo)
                .build();
            //return Response.status(Response.Status.OK).entity(userInfo).build();
        }    
        else
            return Response.status(Response.Status.FORBIDDEN).build();
    }
    
    /**
     * Generate a signed JWT Token, with the subject set as the supplied UserID, with a lifetime of 48hrs
     * @param userID - Becomes the subject
     * @return The JWT to be supplied with requests
     */
    private String issueToken(String userID) {
    Key key = KeyGenerator.generateKey();
    String jwtToken = Jwts.builder()
            .setSubject(userID)
            .setIssuer(uriInfo.getAbsolutePath().toString())
            .setIssuedAt(new Date())
            .setExpiration(toDate(LocalDateTime.now().plusHours(48)))
            .signWith(SignatureAlgorithm.HS512, key)
            .compact();
    log.info("#### issued token");
    return jwtToken;

    }
    
    private Date toDate(LocalDateTime localDateTime) {
        return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
    }
    
    /**
     * Add a user with the supplied properties
     * @param userData JSON String of form {"username":"Test user","password":"password","affiliation":"None"}
     * @return 
     */
    @JWTTokenNeeded
    @POST
    @Path("/user/add")
    public Response addUser(String userData){
        log.log(Level.INFO, "*** VERSION CONTROL SERVICE - Add user Request ***");
        VCForkControl vcForkControl = new VCForkControl();
        String userInfo = null;
        try {
                userInfo = vcForkControl.evalJSONAddUser(userData);
        }
        catch(IllegalArgumentException e) {
            //Username already exists
            return Response.status(Response.Status.CONFLICT).build();
        }
        catch(SQLException e) {
            throw databaseError(e);
        }
        if(userInfo != null)
            return Response.status(Response.Status.CREATED).entity(userInfo).build();
        else
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }//AddUser
    

    /**
     * Inserts a new graph into CISPACES_GRAPH
     * @param graph a JSON string containing the basic information for a new graph
     * @return a response indicating whether the graph has been inserted into the database
     * URL: http://localhost:8080/VC/rest/new
     */
    @POST
    @Path("/new")
    @JWTTokenNeeded
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createNewGraph(String graph){
        log.log(Level.INFO, "*** VERSION CONTROL SERVICE - New Graph Request ***");
        requireCaller(stringField(graph, "userID"));

        VCForkControl vcControl = new VCForkControl();
        boolean inserted = vcControl.evalJSONGraph(graph);

        log.log(Level.INFO,"*** VERSION CONTROL SERVICE - New Graph Request Handled ***");
        if (!inserted) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
        return Response.noContent().build();
    }

    /**
     * Save a new *version* of an existing graph.  
     * Because of the lack of foreign key checks this will merrily record history for graphs which have no record in CISPACES_GRAPH
     * @param graphInfo a json containing the graph id, the creator of the analysis' id and the title of the analysis
     * @return a response indicating whether the analysis has been saved
     * URL: http://localhost:8080/VC/rest/save
     */
    @POST
    @Path("/save")
    @JWTTokenNeeded
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response saveGraph(String graphInfo){
        log.log(Level.INFO, "*** VERSION CONTROL SERVICE - Save Graph Request ***");
        requireGraphOwner(stringField(graphInfo, "graphID"));

        VCForkControl vcForkControl = new VCForkControl();
        String result;
        try {
            result = vcForkControl.evalJSONSaveAnalysis(graphInfo);
        } catch (SQLException e) {
            throw databaseError(e);
        }

        log.log(Level.INFO,"*** VERSION CONTROL SERVICE - Save Graph Request Handled ***");

        if (result == null || result.contains("\"status\":false")) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(result).type(MediaType.APPLICATION_JSON).build();
        }
        return Response.ok(result, MediaType.APPLICATION_JSON).build();
    }


    /**
     * @param edge the JSON for an edge coming from the front-end upon the creation of a new edge
     * @return a response indicating whether the JSON has been processed and the relevant bits put into the database
     * URL: http://localhost:8080/VC/rest/edge/edgeid
     */
    @Path("/edge/{edgeid}")
    @POST
    @JWTTokenNeeded
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public String getJSONInputEdge(String edge){
        log.log(Level.INFO, "*** VERSION CONTROL SERVICE - New Edge Request ***");
        requireEdgeMutation(edge);

        VCForkControl vcControl = new VCForkControl();
        String out;
        try {
            out = vcControl.evalJSONEdge(edge);
        } catch (SQLException e) {
            throw databaseError(e);
        }

        log.log(Level.INFO,"*** VERSION CONTROL SERVICE - Edge Request Handled ***");

        return out;
    }


    /**
     * @param json the JSON for a node coming from the front-end upon the creation of a new node
     * @return a response indicating whether the JSON has been processed and the relevant bits put into the database
     * URL: http://localhost:8080/VC/rest/node/nodeid
     */
    @Path("/node/{nodeid}")
    @POST
    @JWTTokenNeeded
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public String getJSONInputNode(String json){
        log.log(Level.INFO, "*** VERSION CONTROL SERVICE - New Node Request ***");
        requireNodeMutation(json);

        VCForkControl vcControl = new VCForkControl();
        String out;
        try {
            out = vcControl.evalJSONNode(json);
        } catch (SQLException e) {
            throw databaseError(e);
        }

        log.log(Level.INFO,"*** VERSION CONTROL SERVICE - Node Request Handled ***");

        return out;
    }

    /**
     * @param json a string containing the data for the edge to be updated
     * @return a response indicating whether the JSON has been processed and the edge data updated in the database
     * URL: http://localhost:8080/VC/rest/edge/edgeid
     */
    @Path("/edge/{edgeid}")
    @PUT
    @JWTTokenNeeded
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public String putEdge(String json){
        log.log(Level.INFO, "*** VERSION CONTROL SERVICE - Put Edge Request ***");
        requireEdgeMutation(json);
        VCForkControl vcControl = new VCForkControl();
        try {
            return vcControl.evalJSONEdge(json);
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    /**
     * @param json a string containing the data for the node to be updated
     * @return a response indicating whether the JSON has been processed and the node data updated in the database
     * URL: http://localhost:8080/VC/rest/node/nodeid
     */
    @Path("/node/{nodeid}")
    @PUT
    @JWTTokenNeeded
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public String putNode(String json){
        log.log(Level.INFO, "*** VERSION CONTROL SERVICE - Put Node Request ***");
        requireNodeMutation(json);
        VCForkControl vcControl = new VCForkControl();
        try {
            return vcControl.evalJSONNode(json);
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }


    /**
     * @param edgeid a string containing the id of the edge to be deleted
     * @return a response indicating whether the JSON has been processed and the edge deleted from the database
     * URL: http://localhost:8080/VC/rest/edge/edgeid
     */
    @Path("/edge/{edgeid}")
    @DELETE
    @JWTTokenNeeded
    @Produces(MediaType.TEXT_PLAIN)
    @Consumes(MediaType.APPLICATION_JSON)
    public boolean deleteEdge(@PathParam("edgeid") String edgeid){
        log.log(Level.INFO, "*** VERSION CONTROL SERVICE - Delete Edge Request ***");

        DBQuery dbQuery = new DBQuery();
        requireGraphOwner(dbQuery.graphIdOfEdge(edgeid));
        boolean isDeleted = dbQuery.deleteEdge(edgeid);

        log.log(Level.INFO,"*** VERSION CONTROL SERVICE - Delete Edge Request Handled ***");

        return isDeleted;
    }

    /**
     * @param nodeid a string containing the id of the node to be deleted
     * @return a response indicating whether the JSON has been processed and the node deleted from the database
     * URL: http://localhost:8080/VC/rest/node/nodeid
     */
    @Path("/node/{nodeid}")
    @DELETE
    @JWTTokenNeeded
    @Produces(MediaType.TEXT_PLAIN)
    @Consumes(MediaType.APPLICATION_JSON)
    public boolean deleteNode(@PathParam("nodeid") String nodeid){
        log.log(Level.INFO, "*** VERSION CONTROL SERVICE - Delete Node Request ***");

        DBQuery dbQuery = new DBQuery();
        requireGraphOwner(dbQuery.graphIdOfNode(nodeid));
        boolean isDelelted = dbQuery.deleteNode(nodeid);

        log.log(Level.INFO,"*** VERSION CONTROL SERVICE - Delete Node Request Handled ***");

        return isDelelted;
    }

    /**
     * @param analysisid a string containing the id of the analysis to be loaded
     * @return a response indicating whether the JSON has been processed and the analysis updated in the database
     * URL: http://localhost:8080/VC/rest/updateAnalysis
     */
    @Path("/updateAnalysis")
    @POST
    @JWTTokenNeeded
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateAnalysis(String analysis){
        log.log(Level.INFO, "*** VERSION CONTROL SERVICE - Update Analysis Request");
        String graphId = stringField(analysis, "graphID");
        requireGraphOwner(graphId);
        if (!snapshotTargetsGraph(analysis, graphId)) {
            throw new WebApplicationException(Response.Status.BAD_REQUEST);
        }

        VCForkControl vcForkControl = new VCForkControl();
        if (!vcForkControl.evalJSONUpdateAnalysis(analysis)) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
        return Response.ok().build();
    }

    private String authenticatedUserId() {
        if (requestContext == null) {
            return null;
        }
        Object subject = requestContext.getProperty(JWTTokenNeededFilter.JWT_SUBJECT);
        if (subject == null) {
            return null;
        }
        return JwtSubjects.userId(subject.toString());
    }

    private void requireCaller(String userId) {
        String caller = authenticatedUserId();
        if (caller == null || caller.isEmpty()) {
            throw new WebApplicationException(Response.Status.UNAUTHORIZED);
        }
        if (userId == null || !caller.equals(userId.trim())) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
    }

    /**
     * Owner reads succeed. A shared graph is readable by any authenticated caller.
     * Missing graphs are 404. Someone else's unshared graph is 403.
     */
    private Response readAnalysisForCaller(String graphID, String body) {
        String caller = authenticatedUserId();
        if (caller == null || caller.isEmpty()) {
            throw new WebApplicationException(Response.Status.UNAUTHORIZED);
        }
        if (graphID == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (body != null) {
            return Response.ok().entity(body).build();
        }
        String owner = new DBQuery().getGraphOwner(graphID);
        if (owner == null || owner.trim().isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.status(Response.Status.FORBIDDEN).build();
    }

    private void requireGraphOwner(String graphId) {
        String caller = authenticatedUserId();
        if (caller == null || caller.isEmpty()) {
            throw new WebApplicationException(Response.Status.UNAUTHORIZED);
        }
        String owner = new DBQuery().getGraphOwner(graphId);
        if (owner == null || owner.trim().isEmpty()) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        if (!owner.trim().equals(caller)) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
    }

    private void requireNodeMutation(String json) {
        requireGraphOwner(stringField(json, "graphID"));
        String existingGraph = new DBQuery().graphIdOfNode(stringField(json, "nodeID"));
        if (existingGraph != null) {
            requireGraphOwner(existingGraph);
        }
    }

    private void requireEdgeMutation(String json) {
        requireGraphOwner(stringField(json, "graphID"));
        String existingGraph = new DBQuery().graphIdOfEdge(stringField(json, "edgeID"));
        if (existingGraph != null) {
            requireGraphOwner(existingGraph);
        }
    }

    private static boolean snapshotTargetsGraph(String analysis, String graphId) {
        try {
            return DBQuery.elementsTargetGraph(new JSONObject(analysis), graphId);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String stringField(String json, String key) {
        if (json == null) {
            return null;
        }
        try {
            JSONObject obj = new JSONObject(json);
            if (!obj.has(key) || obj.isNull(key)) {
                return null;
            }
            return obj.get(key).toString().trim();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private WebApplicationException databaseError(SQLException e) {
        log.log(Level.SEVERE, "Database query failed", e);
        return new WebApplicationException(Response.status(Response.Status.INTERNAL_SERVER_ERROR).build());
    }

}
