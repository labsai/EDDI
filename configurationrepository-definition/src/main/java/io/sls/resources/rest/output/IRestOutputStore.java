package io.sls.resources.rest.output;

import io.sls.resources.rest.IRestVersionInfo;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.resources.rest.method.PATCH;
import io.sls.resources.rest.output.model.OutputConfigurationSet;
import io.sls.resources.rest.patch.PatchInstruction;
import org.jboss.resteasy.annotations.GZIP;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * User: jarisch
 * Date: 04.06.12
 * Time: 20:34
 */
@Path("/outputstore/outputsets")
public interface IRestOutputStore extends IRestVersionInfo {
    String resourceURI = "resource://io.sls.output/outputstore/outputsets/";
    String versionQueryParam = "?version=";

    @GET
    @GZIP
    @Produces(MediaType.APPLICATION_JSON)
    List<DocumentDescriptor> readOutputDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                   @QueryParam("index") @DefaultValue("0") Integer index,
                                                   @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @GZIP
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    OutputConfigurationSet readOutputSet(@PathParam("id") String id, @QueryParam("version") Integer version,
                                         @QueryParam("filter") @DefaultValue("") String filter,
                                         @QueryParam("order") @DefaultValue("") String order,
                                         @QueryParam("index") @DefaultValue("0") Integer index,
                                         @QueryParam("limit") @DefaultValue("20") Integer limit) throws Exception;

    @GET
    @GZIP
    @Path("/{id}/outputKeys")
    @Produces(MediaType.APPLICATION_JSON)
    List<String> readOutputKeys(@PathParam("id") String id, @QueryParam("version") Integer version,
                                @QueryParam("filter") @DefaultValue("") String filter,
                                @QueryParam("order") @DefaultValue("") String order,
                                @QueryParam("limit") @DefaultValue("20") Integer limit);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    URI updateOutputSet(@PathParam("id") String id, @QueryParam("version") Integer version, @GZIP OutputConfigurationSet outputConfigurationSet);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createOutputSet(@GZIP OutputConfigurationSet outputConfigurationSet);

    @DELETE
    @Path("/{id}")
    void deleteOutputSet(@PathParam("id") String id, @QueryParam("version") Integer version);

    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    URI patchOutputSet(@PathParam("id") String id, @QueryParam("version") Integer version, @GZIP PatchInstruction<OutputConfigurationSet>[] patchInstructions);
}
