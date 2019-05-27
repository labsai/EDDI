package ai.labs.testing.rest;

import ai.labs.testing.model.TestCaseState;
import io.swagger.annotations.Api;
import io.swagger.annotations.Authorization;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Api(value = "Bot Engine -> Testing", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/testcases/run")
public interface IRestTestCaseRuntime {
    @GET
    @Path("/{id}")
    TestCaseState getTestCaseState(@PathParam("id") String id);

    @POST
    @Path("/{id}")
    Response runTestCase(@PathParam("id") String id);
}
