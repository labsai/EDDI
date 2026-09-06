/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST endpoints for GDPR compliance operations.
 * <p>
 * Provides cascading user data deletion (Art. 17 — Right to Erasure), full user
 * data export (Art. 15/20 — Right of Access / Data Portability), and processing
 * restriction (Art. 18 — Right to Restriction).
 *
 * @author ginccc
 * @since 6.0.0
 */
@Path("/admin/gdpr")
@Tag(name = "Security / GDPR / Privacy", description = "User data erasure and export for GDPR/CCPA compliance")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("eddi-admin")
public interface IRestGdprAdmin {

    @DELETE
    @Path("/{userId}")
    @Operation(
               operationId = "deleteUserDataGdpr",
               summary = "Cascade-delete all user data (GDPR Art. 17)",
               description = "Deletes user memories and conversations. "
                       + "Pseudonymizes audit ledger and database log entries. "
                       + "Returns a summary of affected records. "
                       + "Responds 200 when every step of the cascade succeeded and 207 Multi-Status when "
                       + "one or more failed — 'failedSteps' in the body names them, and the erasure must "
                       + "NOT be reported to the data subject as fulfilled until they succeed.")
    @APIResponse(responseCode = "200", description = "Erasure cascade completed in full",
                 content = @Content(schema = @Schema(implementation = GdprDeletionResult.class)))
    @APIResponse(responseCode = "207", description = "Erasure cascade partially failed — see 'failedSteps'",
                 content = @Content(schema = @Schema(implementation = GdprDeletionResult.class)))
    Response deleteUserData(
                            @PathParam("userId")
                            @Parameter(description = "User ID to erase", required = true) String userId);

    /**
     * <strong>Known gap.</strong> The bundle covers five categories: memories,
     * conversation snapshots, managed conversation mappings, audit processing
     * records and attachment metadata. It does <em>not</em> yet include the group
     * discussion transcripts, shared artifacts, schedules and HITL tool journal
     * entries that {@link #deleteUserData} erases as this user's personal data —
     * the two halves of the feature disagree about what the user's data is, and
     * only the deleting half is right. Closing it needs read-by-user methods those
     * four stores do not have (their erasure counterparts page with an escaped
     * regex and an exact re-check), plus a decision on how a bundle that could run
     * to hundreds of megabytes is delivered.
     * <p>
     * <strong>Which is why this endpoint does not answer 200 today.</strong> The
     * gap is part of the response rather than a footnote here: every bundle carries
     * {@code complete: false} and an {@code omittedCategories} list naming those
     * four stores, and the status is 207 Multi-Status. Without it, a user whose
     * data lives only in the four omitted categories received an empty bundle with
     * a 200 documented as "complete export bundle" — an Art. 20 answer that
     * overstates itself to the one reader who cannot check it. It becomes 200 once
     * the four exporters land and the list goes empty.
     * <p>
     * Conversation snapshots are capped per bundle; audit entries were already
     * capped. When the conversation cap bites, the response says so in the payload
     * ({@code totalConversations}, {@code conversationsTruncated}) and counts
     * against {@code complete} the same way — a bundle that silently omits
     * conversations while answering 200 lets a DPO hand a data subject an
     * incomplete Art. 15 bundle believing it complete, which is the same
     * misreporting {@link #deleteUserData} answers 207 for. The audit cap is still
     * reported in the server log only.
     * <p>
     * 207 rather than the 206 Partial Content this used to send: 206 is a range
     * status, RFC 9110 requires a {@code Content-Range} with it, and this endpoint
     * neither reads a {@code Range} nor produces one.
     */
    @GET
    @Path("/{userId}/export")
    @Operation(
               operationId = "exportUserDataGdpr",
               summary = "Export all user data (GDPR Art. 15/20)",
               description = "Returns all data associated with a user: memories, conversations, "
                       + "managed conversation mappings, audit processing records and attachment "
                       + "metadata. Group discussion transcripts, shared artifacts, schedules and "
                       + "HITL journal entries are NOT yet included, although the erasure endpoint "
                       + "does delete them — see the interface javadoc. "
                       + "Responds 200 only when the bundle covers everything, and 207 Multi-Status "
                       + "otherwise: 'complete' says which, 'omittedCategories' names the categories "
                       + "this exporter does not reach, and 'conversationsTruncated' with "
                       + "'totalConversations' report the per-request conversation cap. Because those "
                       + "four categories are always omitted today, the answer is currently always 207 "
                       + "— the export must NOT be handed to the data subject as a complete Art. 15/20 "
                       + "bundle while 'complete' is false.")
    @APIResponse(responseCode = "200", description = "Complete export bundle — every category covered, nothing truncated",
                 content = @Content(schema = @Schema(implementation = UserDataExport.class)))
    @APIResponse(responseCode = "207",
                 description = "Incomplete bundle — see 'complete', 'omittedCategories' and 'conversationsTruncated'",
                 content = @Content(schema = @Schema(implementation = UserDataExport.class)))
    Response exportUserData(
                            @PathParam("userId")
                            @Parameter(description = "User ID to export", required = true) String userId);

    @POST
    @Path("/{userId}/restrict")
    @Operation(
               operationId = "restrictProcessingGdpr",
               summary = "Restrict processing for a user (GDPR Art. 18)",
               description = "Stops new conversation processing while preserving "
                       + "all user data. Use when a user disputes data accuracy "
                       + "or objects to processing.")
    void restrictProcessing(
                            @PathParam("userId")
                            @Parameter(description = "User ID to restrict", required = true) String userId);

    @DELETE
    @Path("/{userId}/restrict")
    @Operation(
               operationId = "unrestrictProcessingGdpr",
               summary = "Remove processing restriction (GDPR Art. 18)",
               description = "Restores normal conversation processing for a user.")
    void unrestrictProcessing(
                              @PathParam("userId")
                              @Parameter(description = "User ID to unrestrict", required = true) String userId);

    @GET
    @Path("/{userId}/restrict")
    @Operation(
               operationId = "checkProcessingRestrictionGdpr",
               summary = "Check processing restriction status",
               description = "Returns true if processing is currently restricted for this user.")
    boolean isProcessingRestricted(
                                   @PathParam("userId")
                                   @Parameter(description = "User ID to check", required = true) String userId);
}
