/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.EmailLogView;
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
    @Path("/{id}/email-log")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "The mail log of an email integration, newest first: every mail it received or sent in the ledger's retention "
            + "(30 days by default), its state and, for a mail it did not act on, the reason (e.g. blocked:not_allowlisted). No subjects or "
            + "bodies. Filters: direction (IN|OUT), kind (TRIGGER|REPLY|NOTIFY|NOTICE), state, reason (a prefix such as 'blocked'), "
            + "since/until (ISO-8601), limit (1-200, default 50); pass nextCursor as 'after' for the next page. Needs edit access.")
    EmailLogView.Page readEmailLog(@PathParam("id") String id,
                                   @QueryParam("direction") String direction,
                                   @QueryParam("kind") String kind,
                                   @QueryParam("state") String state,
                                   @QueryParam("reason") String reason,
                                   @QueryParam("since") String since,
                                   @QueryParam("until") String until,
                                   @QueryParam("limit") Integer limit,
                                   @QueryParam("after") String after);

    @POST
    @Path("/{id}/email-log/lookup")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Look up one message in an email integration's mail log by its Message-ID — why EDDI did or did not answer "
            + "a mail. The body is {\"messageId\": \"<...@...>\"}; returns its rows (the inbound mail and EDDI's reply, if any), 404 when the "
            + "ledger has none. Read-only. Needs edit access.")
    List<EmailLogView> lookupEmailLog(@PathParam("id") String id, EmailLogView.LookupRequest request);
}
