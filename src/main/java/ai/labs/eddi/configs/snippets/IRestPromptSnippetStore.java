package ai.labs.eddi.configs.snippets;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * REST API for {@link PromptSnippet} CRUD operations.
 * <p>
 * Prompt snippets are reusable system prompt building blocks stored as
 * versioned configuration documents. They are automatically available in LLM
 * task system prompts via {@code {{snippets.<name>}}}.
 *
 * @author ginccc
 * @since 6.0.0
 */
@Path("/snippetstore/snippets")
@Tag(name = "Prompt Snippets")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestPromptSnippetStore extends IRestVersionInfo {
    String resourceBaseType = "eddi://ai.labs.snippet";
    String resourceURI = resourceBaseType + "/snippetstore/snippets/";
    String versionQueryParam = "?version=";

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "readSnippetDescriptors", description = "Read list of prompt snippet descriptors.")
    List<DocumentDescriptor> readSnippetDescriptors(@QueryParam("filter")
    @DefaultValue("") String filter,
                                                    @QueryParam("index")
                                                    @DefaultValue("0") Integer index,
                                                    @QueryParam("limit")
                                                    @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "readSnippet", description = "Read a prompt snippet.")
    PromptSnippet readSnippet(@PathParam("id") String id,
                              @Parameter(name = "version", required = true, example = "1")
                              @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "updateSnippet", description = "Update a prompt snippet.")
    Response updateSnippet(@PathParam("id") String id,
                           @Parameter(name = "version", required = true, example = "1")
                           @QueryParam("version") Integer version,
                           PromptSnippet snippet);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "createSnippet", description = "Create a prompt snippet.")
    Response createSnippet(PromptSnippet snippet);

    @DELETE
    @Path("/{id}")
    @Operation(operationId = "deleteSnippet", description = "Delete a prompt snippet.")
    Response deleteSnippet(@PathParam("id") String id,
                           @Parameter(name = "version", required = true, example = "1")
                           @QueryParam("version") Integer version,
                           @QueryParam("permanent")
                           @DefaultValue("false") Boolean permanent);
}
