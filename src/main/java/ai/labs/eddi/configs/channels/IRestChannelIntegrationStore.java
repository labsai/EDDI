/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.EmailSenderLists;
import ai.labs.eddi.configs.channels.model.EmailSuppressionView;
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
 * The authoring tier ({@code eddi-admin} and {@code eddi-editor}), the same
 * security posture as {@code IRestAgentStore} — channel configurations expose
 * target topology and vault references.
 *
 * @since 6.1.0
 */
@Path("/channelstore/channels")
@Tag(name = "Integrations / Channels", description = "Channel integration configuration (Slack, webhook, email)")
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
    @Operation(description = "Update channel integration configuration. For an email integration, a save that empties a non-empty "
            + "sender allowlist opens the integration to every sender: it is refused with 400 unless confirmOpen=true, and then answered "
            + "with the warning email.allowlist-emptied. Every change to the sender lists is recorded in the audit ledger.")
    Response updateChannel(
                           @PathParam("id") String id,
                           @Parameter(name = "version", required = true, example = "1")
                           @QueryParam("version") Integer version,
                           @Parameter(name = "confirmOpen", description = "Confirms emptying a non-empty email sender allowlist.")
                           @QueryParam("confirmOpen") Boolean confirmOpen,
                           ChannelIntegrationConfiguration channelConfiguration);

    /**
     * {@link #updateChannel(String, Integer, Boolean, ChannelIntegrationConfiguration)}
     * without confirming an emptied allowlist.
     */
    default Response updateChannel(String id, Integer version, ChannelIntegrationConfiguration channelConfiguration) {
        return updateChannel(id, version, null, channelConfiguration);
    }

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

    @GET
    @Path("/{id}/email-suppressions")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "List the addresses an email integration no longer replies to because a reply to them bounced "
            + "permanently: the SHA-256 of each address, its domain, the reason and when. Needs edit access to the integration.")
    List<EmailSuppressionView> readEmailSuppressions(@PathParam("id") String id,
                                                     @QueryParam("limit")
                                                     @DefaultValue("100") Integer limit);

    @POST
    @Path("/{id}/email-suppressions/clear")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Clear one suppressed address of an email integration, so replies to it are sent again. The body is "
            + "{\"address\": \"...\"}; 204 when an entry was removed, 404 when the address was not suppressed. Needs edit access.")
    Response clearEmailSuppression(@PathParam("id") String id, EmailSuppressionView.ClearRequest request);

    @GET
    @Path("/{id}/email-senders")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read the sender allowlist and blocklist of an email integration's current version: "
            + "{\"version\", \"allow\": [...], \"block\": [...]}. An empty allowlist allows every sender. Needs view access.")
    EmailSenderLists.View readEmailSenders(@PathParam("id") String id);

    @POST
    @Path("/{id}/email-senders")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Add and remove sender-list entries of an email integration without rewriting it. The body is "
            + "{\"expectedVersion\", \"add\": {\"allow\": [...], \"block\": [...]}, \"remove\": {...}, \"reason\", \"confirmOpen\"}; "
            + "entries are user@domain, *@domain or *@*.domain. A change is a new version of the integration, saved like any "
            + "other; entries that change nothing are reported, not refused. 409 with the current lists when expectedVersion is "
            + "not the current version; 400 for an invalid entry, an entry on both lists, more than 200 entries, or removing "
            + "the last allowlist entry without confirmOpen: true. Needs edit access.")
    Response updateEmailSenders(@PathParam("id") String id, EmailSenderLists.UpdateRequest request);
}
