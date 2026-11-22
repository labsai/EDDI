/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.EmailHeldMailView;
import ai.labs.eddi.configs.channels.model.EmailSenderLists;
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

    @GET
    @Path("/{id}/email-held")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-approver"})
    @Operation(description = "The mail of an email integration held for review instead of dropped (emailControl.onRefused): "
            + "{integrationId, integrationName, items: [{rowId, sender, authVerdict, heldFor, heldAt, expiresAt, runAndAllowOffered, "
            + "runsAnonymously}], truncated}, oldest first. No content: open one with GET .../email-held/{rowId}. Needs edit access to the "
            + "integration, or to be one of its reviewers (emailControl.review.reviewers; by default eddi-approver).")
    EmailHeldMailView.Queue readHeldEmail(@PathParam("id") String id, @QueryParam("limit")
    @DefaultValue("50") Integer limit);

    @GET
    @Path("/{id}/email-held/{rowId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-approver"})
    @Operation(description = "A live preview of one held mail, re-read from the mailbox by its Message-ID (polled, move and Held "
            + "folders) and never stored: sender, authentication verdict, subject, the first 4000 characters of the text (quoted history "
            + "stripped), attachment names. 404 when the row is not held, 409 when the mail is no longer in the mailbox, 503 when the "
            + "mailbox cannot be read. Same access as the list.")
    EmailHeldMailView.Preview readHeldEmailPreview(@PathParam("id") String id, @PathParam("rowId") String rowId);

    @POST
    @Path("/{id}/email-held/{rowId}/decision")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-approver"})
    @Operation(description = "Decide a held mail: {\"decision\": \"discard\" | \"runOnce\" | \"runAndAllow\", \"note\": \"...\"}. discard "
            + "drops it (review_discarded). runOnce runs it at the next poll, skipping only the checks that held it; it still meets the "
            + "target's approvalPolicy. runAndAllow also adds the exact sender address to the allowlist (a new version, audited, the note "
            + "as reason): it needs edit access and an aligned DKIM pass of the sender. A mail released without one runs anonymously, with "
            + "the target's readOnlyTools only, and never causes outgoing mail. 404 when the row is not held, 409 when it was decided "
            + "meanwhile. Never callable from a conversation an email started. Audited.")
    Response decideHeldEmail(@PathParam("id") String id, @PathParam("rowId") String rowId, EmailHeldMailView.DecisionRequest request);

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
