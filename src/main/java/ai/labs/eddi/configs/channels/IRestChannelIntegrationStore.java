/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.EmailSelfTestResult;
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

    @POST
    @Path("/{id}/email-self-test")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Start the sending-health self-test of an email integration: it sends one short mail from the "
            + "integration to its own address through its SMTP relay (smtpHost; trigger mode too), signed as emailControl.smime says, "
            + "carrying a single-use token valid for 15 minutes. The answer holds the token — shown only here — and status 'waiting'; "
            + "poll GET .../email-self-test/{token} for the result once the mailbox poll has picked the mail up. The test mail never "
            + "starts an agent turn. At most 5 tests per integration per hour (429). Needs edit access.")
    EmailSelfTestResult startEmailSelfTest(@PathParam("id") String id);

    @GET
    @Path("/{id}/email-self-test/{token}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "The result of an email self-test: status waiting | done | expired | send_failed and, once done, "
            + "the DKIM verdict and signing domain, DMARC alignment, whether the S/MIME signature verified and whether the domain "
            + "publishes DMARC, each as a plain-language finding with what to do. On Microsoft 365 an unsigned result is "
            + "'inconclusive' (it does not DKIM-sign mail inside one organisation). 404 for an unknown token. Needs edit access.")
    EmailSelfTestResult readEmailSelfTest(@PathParam("id") String id, @PathParam("token") String token);
}
