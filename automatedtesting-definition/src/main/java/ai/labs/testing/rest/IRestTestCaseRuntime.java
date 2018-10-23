package ai.labs.testing.rest;

import ai.labs.testing.model.TestCaseState;
import io.swagger.annotations.Api;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Api(value = "testcases")
@Path("/testcases/run")
public interface IRestTestCaseRuntime {
    @GET
    @Path("/{conversationId}")
    TestCaseState getTestCaseState(@PathParam("conversationId") String id);

    @POST
    @Path("/{conversationId}")
    Response runTestCase(@PathParam("conversationId") String id);
}
