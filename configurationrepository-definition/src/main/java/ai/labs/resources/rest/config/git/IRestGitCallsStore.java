package ai.labs.resources.rest.config.git;

import ai.labs.models.DocumentDescriptor;
import ai.labs.resources.rest.IRestVersionInfo;
import ai.labs.resources.rest.config.git.model.GitCallsConfiguration;
import io.swagger.annotations.*;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

/**
 * @author rpi
 */

@Api(value = "Configurations -> (2) Conversation LifeCycle Tasks -> (3) GitCalls", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/gitcallsstore/gitcalls")
public interface IRestGitCallsStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.gitcalls/gitcallsstore/gitcalls/";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(code = 200, response = Map.class, message = "JSON Schema (for validation).")
    @ApiOperation(value = "Read JSON Schema for regular gitCalls definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read list of gitCalls descriptors.")
    List<DocumentDescriptor> readGitCallsDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                      @QueryParam("index") @DefaultValue("0") Integer index,
                                                      @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read gitCalls.")
    GitCallsConfiguration readGitCalls(@PathParam("id") String id,
                                        @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                         @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update gitCalls.")
    Response updateGitCalls(@PathParam("id") String id,
                             @ApiParam(name = "version", required = true, format = "integer", example = "1")
                             @QueryParam("version") Integer version, GitCallsConfiguration httpCallsConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Create gitCalls.")
    Response createGitCalls(GitCallsConfiguration httpCallsConfiguration);

    @POST
    @Path("/{id}")
    @ApiOperation(value = "Duplicate this gitCalls.")
    Response duplicateGitCalls(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @ApiOperation(value = "Delete gitCalls.")
    Response deleteGitCalls(@PathParam("id") String id,
                             @ApiParam(name = "version", required = true, format = "integer", example = "1")
                             @QueryParam("version") Integer version);
}

