package ai.labs.eddi.configs.bots;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.bots.model.BotConfiguration;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
// @Api(value = "Configurations -> (4) Bots", authorizations =
// {@Authorization(value = "eddi_auth")})
@Path("/botstore/bots")
@Tag(name = "07. Bots", description = "bot configuration")
public interface IRestBotStore extends IRestVersionInfo {
        String resourceURI = "eddi://ai.labs.bot/botstore/bots/";

        @GET
        @Path("/jsonSchema")
        @Produces(MediaType.APPLICATION_JSON)
        @APIResponse(responseCode = "200", description = "JSON Schema (for validation).")
        @Operation(description = "Read JSON Schema for bot definition.")
        Response readJsonSchema();

        @GET
        @Path("descriptors")
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(description = "Read list of bot descriptors.")
        List<DocumentDescriptor> readBotDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                        @QueryParam("index") @DefaultValue("0") Integer index,
                        @QueryParam("limit") @DefaultValue("20") Integer limit);

        @POST
        @Path("descriptors")
        @Consumes(MediaType.TEXT_PLAIN)
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(description = "Read list of bot descriptors including a given packageUri.")
        List<DocumentDescriptor> readBotDescriptors(
                        @QueryParam("filter") @DefaultValue("") String filter,
                        @QueryParam("index") @DefaultValue("0") Integer index,
                        @QueryParam("limit") @DefaultValue("20") Integer limit,
                        @Parameter(name = "body", example = "eddi://ai.labs.package/packagestore/packages/ID?version=VERSION") @DefaultValue("") String containingPackageUri,
                        @QueryParam("includePreviousVersions") @DefaultValue("false") Boolean includePreviousVersions);

        @GET
        @Path("/{id}")
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(description = "Read bot.")
        BotConfiguration readBot(@PathParam("id") String id,
                        @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version);

        @PUT
        @Path("/{id}")
        @Consumes(MediaType.APPLICATION_JSON)
        @Operation(description = "Update bot.")
        Response updateBot(@PathParam("id") String id,
                        @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version,
                        BotConfiguration botConfiguration);

        @PUT
        @Path("/{id}/updateResourceUri")
        @Consumes(MediaType.TEXT_PLAIN)
        @Operation(description = "Update references to other resources within this bot resource.")
        Response updateResourceInBot(@PathParam("id") String id,
                        @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version,
                        URI resourceURI);

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Operation(description = "Create bot.")
        Response createBot(BotConfiguration botConfiguration);

        @POST
        @Path("/{id}")
        @Operation(description = "Duplicate this bot.")
        Response duplicateBot(@PathParam("id") String id,
                        @QueryParam("version") Integer version,
                        @QueryParam("deepCopy") @DefaultValue("false") Boolean deepCopy);

        @DELETE
        @Path("/{id}")
        @Operation(
                summary = "Delete bot",
                description = "Delete a bot configuration. When cascade=true, also deletes all referenced packages "
                        + "and their extension resources (behavior sets, HTTP calls, output sets, langchains, "
                        + "property setters, dictionaries). Partial cascade failures are logged but do not prevent "
                        + "the bot itself from being deleted.")
        @APIResponse(responseCode = "200", description = "Bot deleted successfully.")
        @APIResponse(responseCode = "404", description = "Bot not found.")
        Response deleteBot(@PathParam("id") String id,
                        @Parameter(name = "version", required = true, example = "1",
                                description = "Version of the bot to delete.")
                        @QueryParam("version") Integer version,
                        @Parameter(description = "If true, permanently remove from database. "
                                + "If false (default), soft-delete only.")
                        @QueryParam("permanent") @DefaultValue("false") Boolean permanent,
                        @Parameter(description = "If true, also delete all packages and extension resources "
                                + "referenced by this bot. Resources shared with other bots are still deleted — "
                                + "use with care.")
                        @QueryParam("cascade") @DefaultValue("false") Boolean cascade);
}
