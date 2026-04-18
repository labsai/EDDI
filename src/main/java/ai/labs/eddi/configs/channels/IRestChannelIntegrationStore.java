package ai.labs.eddi.configs.channels;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * JAX-RS interface for channel integration configuration CRUD.
 * <p>
 * Admin-only — channel configurations expose target topology and vault
 * references. Same security posture as {@code IRestAgentStore}.
 *
 * @since 6.1.0
 */
@Path("/channelstore/channels")
@Tag(name = "Channel Integrations")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestChannelIntegrationStore extends IRestVersionInfo {
    String resourceBaseType = "eddi://ai.labs.channel";
    String resourceURI = resourceBaseType + "/channelstore/channels/";

    @GET
    @Path("/descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read list of channel integration descriptors.")
    List<DocumentDescriptor> readChannelDescriptors(
                                                    @QueryParam("filter")
                                                    @DefaultValue("") String filter,
                                                    @QueryParam("index")
                                                    @DefaultValue("0") Integer index,
                                                    @QueryParam("limit")
                                                    @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read channel integration configuration.")
    ChannelIntegrationConfiguration readChannel(
                                                @PathParam("id") String id,
                                                @Parameter(name = "version", required = true, example = "1")
                                                @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Update channel integration configuration.")
    Response updateChannel(
                           @PathParam("id") String id,
                           @Parameter(name = "version", required = true, example = "1")
                           @QueryParam("version") Integer version,
                           ChannelIntegrationConfiguration channelConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Create channel integration configuration.")
    Response createChannel(ChannelIntegrationConfiguration channelConfiguration);

    @POST
    @Path("/{id}")
    @Operation(description = "Duplicate this channel integration configuration.")
    Response duplicateChannel(@PathParam("id") String id,
                              @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @Operation(description = "Delete channel integration configuration.")
    Response deleteChannel(
                           @PathParam("id") String id,
                           @Parameter(name = "version", required = true, example = "1")
                           @QueryParam("version") Integer version,
                           @QueryParam("permanent")
                           @DefaultValue("false") Boolean permanent);
}
