package ai.labs.eddi.testing.rest;

import ai.labs.eddi.testing.model.TestCaseState;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

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
