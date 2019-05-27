package ai.labs.resources.rest.bots;

import ai.labs.models.DocumentDescriptor;
import ai.labs.resources.rest.IRestVersionInfo;
import ai.labs.resources.rest.bots.model.BotConfiguration;
import io.swagger.annotations.*;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
@Api(value = "Configurations -> (4) Bots", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/botstore/bots")
public interface IRestBotStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.bot/botstore/bots/";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(code = 200, response = Map.class, message = "JSON Schema (for validation).")
    @ApiOperation(value = "Read JSON Schema for bot definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read list of bot descriptors.")
    List<DocumentDescriptor> readBotDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                @QueryParam("index") @DefaultValue("0") Integer index,
                                                @QueryParam("limit") @DefaultValue("20") Integer limit);

    @POST
    @Path("descriptors")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read list of bot descriptors including a given packageUri.")
    List<DocumentDescriptor> readBotDescriptors(
            @QueryParam("filter") @DefaultValue("") String filter,
            @QueryParam("index") @DefaultValue("0") Integer index,
            @QueryParam("limit") @DefaultValue("20") Integer limit,
            @ApiParam(name = "body", value = "eddi://ai.labs.package/packagestore/packages/ID?version=VERSION")
            @DefaultValue("") String containingPackageUri,
            @QueryParam("includePreviousVersions") @DefaultValue("false") Boolean includePreviousVersions);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read bot.")
    BotConfiguration readBot(@PathParam("id") String id,
                             @ApiParam(name = "version", required = true, format = "integer", example = "1")
                             @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update bot.")
    Response updateBot(@PathParam("id") String id,
                       @ApiParam(name = "version", required = true, format = "integer", example = "1")
                       @QueryParam("version") Integer version, BotConfiguration botConfiguration);

    @PUT
    @Path("/{id}/updateResourceUri")
    @Consumes(MediaType.TEXT_PLAIN)
    @ApiOperation(value = "Update references to other resources within this bot resource.")
    Response updateResourceInBot(@PathParam("id") String id,
                                 @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                 @QueryParam("version") Integer version, URI resourceURI);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Create bot.")
    Response createBot(BotConfiguration botConfiguration);

    @POST
    @Path("/{id}")
    @ApiOperation(value = "Duplicate this bot.")
    Response duplicateBot(@PathParam("id") String id,
                          @QueryParam("version") Integer version,
                          @QueryParam("deepCopy") @DefaultValue("false") Boolean deepCopy);

    @DELETE
    @Path("/{id}")
    @ApiOperation(value = "Delete bot.")
    Response deleteBot(@PathParam("id") String id,
                       @ApiParam(name = "version", required = true, format = "integer", example = "1")
                       @QueryParam("version") Integer version);
}
