package ai.labs.resources.rest.output;

import ai.labs.resources.rest.IRestVersionInfo;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.method.PATCH;
import ai.labs.resources.rest.output.model.OutputConfigurationSet;
import ai.labs.resources.rest.patch.PatchInstruction;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */
@Api(value = "configurations")
@Path("/outputstore/outputsets")
public interface IRestOutputStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.output/outputstore/outputsets/";
    String versionQueryParam = "?version=";

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    List<DocumentDescriptor> readOutputDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                   @QueryParam("index") @DefaultValue("0") Integer index,
                                                   @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    OutputConfigurationSet readOutputSet(@PathParam("id") String id,
                                         @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                         @QueryParam("version") Integer version,
                                         @QueryParam("filter") @DefaultValue("") String filter,
                                         @QueryParam("order") @DefaultValue("") String order,
                                         @QueryParam("index") @DefaultValue("0") Integer index,
                                         @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}/outputKeys")
    @Produces(MediaType.APPLICATION_JSON)
    List<String> readOutputKeys(@PathParam("id") String id,
                                @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                @QueryParam("version") Integer version,
                                @QueryParam("filter") @DefaultValue("") String filter,
                                @QueryParam("order") @DefaultValue("") String order,
                                @QueryParam("limit") @DefaultValue("20") Integer limit);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    Response updateOutputSet(@PathParam("id") String id,
                             @ApiParam(name = "version", required = true, format = "integer", example = "1")
                        @QueryParam("version") Integer version, OutputConfigurationSet outputConfigurationSet);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createOutputSet(OutputConfigurationSet outputConfigurationSet);

    @DELETE
    @Path("/{id}")
    Response deleteOutputSet(@PathParam("id") String id,
                             @ApiParam(name = "version", required = true, format = "integer", example = "1")
                         @QueryParam("version") Integer version);

    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    Response patchOutputSet(@PathParam("id") String id,
                            @ApiParam(name = "version", required = true, format = "integer", example = "1")
                       @QueryParam("version") Integer version, PatchInstruction<OutputConfigurationSet>[] patchInstructions);
}
