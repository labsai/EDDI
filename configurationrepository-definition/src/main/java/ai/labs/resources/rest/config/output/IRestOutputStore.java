package ai.labs.resources.rest.config.output;

import ai.labs.models.DocumentDescriptor;
import ai.labs.resources.rest.IRestVersionInfo;
import ai.labs.resources.rest.config.output.model.OutputConfigurationSet;
import ai.labs.resources.rest.method.PATCH;
import ai.labs.resources.rest.patch.PatchInstruction;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
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
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
@Api(value = "Configurations -> (2) Conversation LifeCycle Tasks -> (3) Output", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/outputstore/outputsets")
public interface IRestOutputStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.output/outputstore/outputsets/";
    String versionQueryParam = "?version=";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(code = 200, response = Map.class, message = "JSON Schema (for validation).")
    @ApiOperation(value = "Read JSON Schema for output definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read list of output descriptors.")
    List<DocumentDescriptor> readOutputDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                   @QueryParam("index") @DefaultValue("0") Integer index,
                                                   @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read output.")
    OutputConfigurationSet readOutputSet(@PathParam("id") String id,
                                         @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                         @QueryParam("version") Integer version,
                                         @QueryParam("filter") @DefaultValue("") String filter,
                                         @QueryParam("order") @DefaultValue("") String order,
                                         @QueryParam("index") @DefaultValue("0") Integer index,
                                         @QueryParam("limit") @DefaultValue("0") Integer limit);

    @GET
    @Path("/{id}/outputKeys")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read output keys.")
    List<String> readOutputKeys(@PathParam("id") String id,
                                @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                @QueryParam("version") Integer version,
                                @QueryParam("filter") @DefaultValue("") String filter,
                                @QueryParam("order") @DefaultValue("") String order,
                                @QueryParam("limit") @DefaultValue("20") Integer limit);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update output.")
    Response updateOutputSet(@PathParam("id") String id,
                             @ApiParam(name = "version", required = true, format = "integer", example = "1")
                             @QueryParam("version") Integer version, OutputConfigurationSet outputConfigurationSet);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Create output.")
    Response createOutputSet(OutputConfigurationSet outputConfigurationSet);

    @POST
    @Path("/{id}")
    @ApiOperation(value = "Duplicate this output.")
    Response duplicateOutputSet(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @ApiOperation(value = "Delete output.")
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
