package ai.labs.testing.rest;

import ai.labs.resources.rest.method.PATCH;
import ai.labs.resources.rest.patch.PatchInstruction;
import ai.labs.testing.descriptor.model.SimpleTestCaseDescriptor;
import ai.labs.testing.descriptor.model.TestCaseDescriptor;
import ai.labs.testing.model.TestCase;
import io.swagger.annotations.Api;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
@Api(value = "testcases")
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
    @Path("/{conversationId}")
    @Consumes(MediaType.APPLICATION_JSON)
    void patchDescriptor(@PathParam("conversationId") String id, @QueryParam("version") Integer version, PatchInstruction<SimpleTestCaseDescriptor> patchInstruction);


    @GET
    @Path("/{conversationId}")
    @Produces(MediaType.APPLICATION_JSON)
    TestCase readTestCase(@PathParam("conversationId") String id);

    @POST
    Response createTestCase(String conversationId);

    @PUT
    @Path("/{conversationId}")
    URI updateTestCase(@PathParam("conversationId") String id, TestCase testCase);

    @DELETE
    @Path("/{conversationId}")
    void deleteTestCase(@PathParam("conversationId") String id);
}
