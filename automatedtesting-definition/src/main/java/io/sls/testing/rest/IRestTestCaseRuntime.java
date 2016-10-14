package io.sls.testing.rest;

import io.sls.testing.model.TestCaseState;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

/**
 * User: jarisch
 * Date: 22.11.12
 * Time: 13:15
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
