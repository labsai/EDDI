package ai.labs.testing.rest;

import ai.labs.resources.rest.method.PATCH;
import ai.labs.resources.rest.patch.PatchInstruction;
import ai.labs.testing.descriptor.model.SimpleTestCaseDescriptor;
import ai.labs.testing.descriptor.model.TestCaseDescriptor;
import ai.labs.testing.model.TestCase;
import io.swagger.annotations.Api;
import io.swagger.annotations.Authorization;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
@Api(value = "Bot Engine -> Testing", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/testcasestore/testcases")
public interface IRestTestCaseStore {
    String resourceURI = "eddi://ai.labs.testcases/testcasestore/testcases/";
    String versionQueryParam = "?version=";

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<TestCaseDescriptor> readTestCaseDescriptors(@QueryParam("botId") String botId,
                                                     @QueryParam("botVersion") Integer botVersion,
                                                     @QueryParam("index") @DefaultValue("0") Integer index,
                                                     @QueryParam("limit") @DefaultValue("20") Integer limit);

    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    void patchDescriptor(@PathParam("id") String id, @QueryParam("version") Integer version, PatchInstruction<SimpleTestCaseDescriptor> patchInstruction);


    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    TestCase readTestCase(@PathParam("id") String id);

    @POST
    Response createTestCase(String id);

    @PUT
    @Path("/{id}")
    URI updateTestCase(@PathParam("id") String id, TestCase testCase);

    @DELETE
    @Path("/{id}")
    void deleteTestCase(@PathParam("id") String id);
}
