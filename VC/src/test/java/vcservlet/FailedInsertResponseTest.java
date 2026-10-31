package vcservlet;

import org.junit.Test;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FailedInsertResponseTest {

    @Test
    public void unauthenticatedNodeWriteIsRejected() {
        try {
            new VCServlet().getJSONInputNode("{\"nodeID\":\"n1\",\"text\":\"a claim\"}");
            fail("an unauthenticated node write must be rejected");
        } catch (WebApplicationException rejected) {
            assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), rejected.getResponse().getStatus());
        }
    }

    @Test
    public void unauthenticatedEdgeWriteIsRejected() {
        try {
            new VCServlet().getJSONInputEdge("{\"edgeID\":\"e1\",\"source\":\"n1\",\"target\":\"n2\"}");
            fail("an unauthenticated edge write must be rejected");
        } catch (WebApplicationException rejected) {
            assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), rejected.getResponse().getStatus());
        }
    }

    @Test
    public void failedBodyIsHttp500() {
        Response response = VCServlet.writeResponse("{\"response\":\"fail\"}");
        assertTrue(response.getEntity().toString().contains("\"response\":\"fail\""));
        assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
    }

    @Test
    public void successBodyStaysSuccessful() {
        Response response = VCServlet.writeResponse("{\"response\":\"success\"}");
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals("{\"response\":\"success\"}", response.getEntity());
    }
}
