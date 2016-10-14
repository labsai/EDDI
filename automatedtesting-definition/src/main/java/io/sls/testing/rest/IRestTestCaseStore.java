package io.sls.testing.rest;

import io.sls.resources.rest.method.PATCH;
import io.sls.resources.rest.patch.PatchInstruction;
import io.sls.testing.descriptor.model.SimpleTestCaseDescriptor;
import io.sls.testing.descriptor.model.TestCaseDescriptor;
import io.sls.testing.model.TestCase;
import org.jboss.resteasy.annotations.GZIP;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.net.URI;
import java.util.List;

/**
 * User: jarisch
 * Date: 22.11.12
 * Time: 14:29
 */
@Path("/testcasestore/testcases")
public interface IRestTestCaseStore {
    String resourceURI = "resource://io.sls.testcases/testcasestore/testcases/";
    String versionQueryParam = "?version=";

    @GET
    @GZIP
    @Produces(MediaType.APPLICATION_JSON)
    List<TestCaseDescriptor> readTestCaseDescriptors(@QueryParam("botId") String botId,
                                                     @QueryParam("botVersion") Integer botVersion,
                                                     @QueryParam("index") @DefaultValue("0") Integer index,
                                                     @QueryParam("limit") @DefaultValue("20") Integer limit);

    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    void patchDescriptor(@PathParam("id") String id, @QueryParam("version") Integer version, @GZIP PatchInstruction<SimpleTestCaseDescriptor> patchInstruction);


    @GET
    @GZIP
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    TestCase readTestCase(@PathParam("id") String id);

    @POST
    URI createTestCase(String conversationId);

    @PUT
    @Path("/{id}")
    URI updateTestCase(@PathParam("id") String id, @GZIP TestCase testCase);

    @DELETE
    @Path("/{id}")
    void deleteTestCase(@PathParam("id") String id);
}
