package ai.labs.eddi.configs.git;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.git.model.GitCallsConfiguration;
import ai.labs.eddi.models.DocumentDescriptor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * @author rpi
 */

// @Api(value = "Configurations -> (2) Conversation LifeCycle Tasks -> (3) GitCalls", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/gitcallsstore/gitcalls")
@Tag(name = "04. Gitcalls", description = "lifecycle extension for package")
public interface IRestGitCallsStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.gitcalls/gitcallsstore/gitcalls/";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "JSON Schema (for validation).")
    @Operation(description = "Read JSON Schema for regular gitCalls definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read list of gitCalls descriptors.")
    List<DocumentDescriptor> readGitCallsDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                     @QueryParam("index") @DefaultValue("0") Integer index,
                                                     @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read gitCalls.")
    GitCallsConfiguration readGitCalls(@PathParam("id") String id,
                                       @Parameter(name = "version", required = true, example = "1")
                                       @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Update gitCalls.")
    Response updateGitCalls(@PathParam("id") String id,
                            @Parameter(name = "version", required = true, example = "1")
                            @QueryParam("version") Integer version, GitCallsConfiguration httpCallsConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Create gitCalls.")
    Response createGitCalls(GitCallsConfiguration httpCallsConfiguration);

    @POST
    @Path("/{id}")
    @Operation(description = "Duplicate this gitCalls.")
    Response duplicateGitCalls(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @Operation(description = "Delete gitCalls.")
    Response deleteGitCalls(@PathParam("id") String id,
                            @Parameter(name = "version", required = true, example = "1")
                            @QueryParam("version") Integer version);
}

