package ai.labs.eddi.configs.output;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */
// @Api(value = "Configurations -> (2) Conversation LifeCycle Tasks -> (3) Output", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/outputstore/outputsets")
@Tag(name = "05. Output", description = "lifecycle extension for package")
public interface IRestOutputStore extends IRestVersionInfo {
    String resourceBaseType = "eddi://ai.labs.output";
    String resourceURI = resourceBaseType + "/outputstore/outputsets/";
    String versionQueryParam = "?version=";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "JSON Schema (for validation).")
    @Operation(description = "Read JSON Schema for output definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read list of output descriptors.")
    List<DocumentDescriptor> readOutputDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                   @QueryParam("index") @DefaultValue("0") Integer index,
                                                   @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read output.")
    OutputConfigurationSet readOutputSet(@PathParam("id") String id,
                                         @Parameter(name = "version", required = true, example = "1")
                                         @QueryParam("version") Integer version,
                                         @QueryParam("filter") @DefaultValue("") String filter,
                                         @QueryParam("order") @DefaultValue("") String order,
                                         @QueryParam("index") @DefaultValue("0") Integer index,
                                         @QueryParam("limit") @DefaultValue("0") Integer limit);

    @GET
    @Path("/{id}/outputKeys")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read output keys.")
    List<String> readOutputKeys(@PathParam("id") String id,
                                @Parameter(name = "version", required = true, example = "1")
                                @QueryParam("version") Integer version,
                                @QueryParam("filter") @DefaultValue("") String filter,
                                @QueryParam("limit") @DefaultValue("20") Integer limit);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Update output.")
    Response updateOutputSet(@PathParam("id") String id,
                             @Parameter(name = "version", required = true, example = "1")
                             @QueryParam("version") Integer version, OutputConfigurationSet outputConfigurationSet);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Create output.")
    Response createOutputSet(OutputConfigurationSet outputConfigurationSet);

    @POST
    @Path("/{id}")
    @Operation(description = "Duplicate this output.")
    Response duplicateOutputSet(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @Operation(description = "Delete output.")
    Response deleteOutputSet(@PathParam("id") String id,
                             @Parameter(name = "version", required = true, example = "1")
                             @QueryParam("version") Integer version);

    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    Response patchOutputSet(@PathParam("id") String id,
                            @Parameter(name = "version", required = true, example = "1")
                            @QueryParam("version") Integer version, List<PatchInstruction<OutputConfigurationSet>> patchInstructions);
}
