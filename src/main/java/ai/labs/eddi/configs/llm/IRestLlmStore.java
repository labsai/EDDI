package ai.labs.eddi.configs.llm;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * @author ginccc
 */
@Path("/llmstore/llms")
@Tag(name = "LLM Configuration")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestLlmStore extends IRestVersionInfo {
    String resourceBaseType = "eddi://ai.labs.llm";
    String resourceURI = resourceBaseType + "/llmstore/llms/";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "JSON Schema (for validation).")
    @Operation(description = "Read JSON Schema for regular LLM config definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read list of LLM config descriptors.")
    List<DocumentDescriptor> readLlmDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
            @QueryParam("index") @DefaultValue("0") Integer index, @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read LLM config.")
    LlmConfiguration readLlm(@PathParam("id") String id,
            @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Update LLM config.")
    Response updateLlm(@PathParam("id") String id,
            @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version, LlmConfiguration llmConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Create LLM config.")
    Response createLlm(LlmConfiguration llmConfiguration);

    @POST
    @Path("/{id}")
    @Operation(description = "Duplicate this LLM config.")
    Response duplicateLlm(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @Operation(description = "Delete LLM config.")
    Response deleteLlm(@PathParam("id") String id,
            @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version,
            @QueryParam("permanent") @DefaultValue("false") Boolean permanent);
}
