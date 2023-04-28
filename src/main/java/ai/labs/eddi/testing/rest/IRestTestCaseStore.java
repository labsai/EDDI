package ai.labs.eddi.testing.rest;

import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.testing.descriptor.model.SimpleTestCaseDescriptor;
import ai.labs.eddi.testing.descriptor.model.TestCaseDescriptor;
import ai.labs.eddi.testing.model.TestCase;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
@Path("/testcasestore/testcases")
@Tag(name = "11. Conversation Testing", description = "Test bots automatically")
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
