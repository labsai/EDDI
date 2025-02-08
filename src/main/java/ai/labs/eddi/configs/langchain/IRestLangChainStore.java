package ai.labs.eddi.configs.langchain;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration;
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
// @Api(value = "Configurations -> (2) Conversation LifeCycle Tasks -> (3) LangChain", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/langchainstore/langchains")
@Tag(name = "03. LangChains", description = "lifecycle extension for package")
public interface IRestLangChainStore extends IRestVersionInfo {
    String resourceBaseType = "eddi://ai.labs.langchain";
    String resourceURI = resourceBaseType + "/langchainstore/langchains/";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "JSON Schema (for validation).")
    @Operation(description = "Read JSON Schema for regular langChain definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read list of langChain descriptors.")
    List<DocumentDescriptor> readLangChainDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                      @QueryParam("index") @DefaultValue("0") Integer index,
                                                      @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read langChain.")
    LangChainConfiguration readLangChain(@PathParam("id") String id,
                                         @Parameter(name = "version", required = true, example = "1")
                                         @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Update langChain.")
    Response updateLangChain(@PathParam("id") String id,
                             @Parameter(name = "version", required = true, example = "1")
                             @QueryParam("version") Integer version, LangChainConfiguration langChainConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Create langChain.")
    Response createLangChain(LangChainConfiguration langChainConfiguration);

    @POST
    @Path("/{id}")
    @Operation(description = "Duplicate this langChain.")
    Response duplicateLangChain(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @Operation(description = "Delete langChain.")
    Response deleteLangChain(@PathParam("id") String id,
                             @Parameter(name = "version", required = true, example = "1")
                             @QueryParam("version") Integer version);
}
