package ai.labs.testing.rest;

import ai.labs.testing.model.TestCaseState;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

/**
 * @author ginccc
 */
@Path("/testcases/run")
public interface IRestTestCaseRuntime {
    @GET
    @Path("/{id}")
    TestCaseState getTestCaseState(@PathParam("id") String id);

    @POST
    @Path("/{id}")
    void runTestCase(@PathParam("id") String id);
}
