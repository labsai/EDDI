package ai.labs.eddi.testing.rest;

import ai.labs.eddi.testing.model.TestCaseState;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Path("/testcases/run")
@Tag(name = "11. Conversation Testing", description = "Test bots automatically")
public interface IRestTestCaseRuntime {
    @GET
    @Path("/{id}")
    TestCaseState getTestCaseState(@PathParam("id") String id);

    @POST
    @Path("/{id}")
    Response runTestCase(@PathParam("id") String id);
}
