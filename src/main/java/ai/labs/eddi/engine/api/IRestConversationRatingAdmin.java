/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig.Scale;
import ai.labs.eddi.engine.ratings.model.RatingKind;
import ai.labs.eddi.engine.ratings.model.RatingSummary;
import ai.labs.eddi.engine.ratings.model.ReviewStatus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Instant;
import java.util.List;

/**
 * Conversation ratings for the people who run an agent: a paged list,
 * per-bucket summaries computed in the database, a CSV export, and a triage
 * loop (mark reviewed, note, attention count).
 *
 * <h3>Who sees what</h3> Administrators see every agent's ratings and, alone,
 * the id of the user who gave each one. Editors must name an {@code agentId}
 * and hold {@code EDIT} on it; they see score, tags and comment, never a user
 * reference. Whether a row's transcript may be opened is decided per row by the
 * existing conversation-read rule (owner, administrator, or conversation
 * review) and reported as {@code canOpenTranscript} — a rating never becomes a
 * way around it. No role hierarchy in REST: the roles are enumerated.
 *
 * <h3>Filters</h3> See {@link RatingQueryParams}.
 */
@Path("/ratings")
@Tag(name = "Conversation Ratings", description = "Analyze and triage the ratings people gave conversations with an agent")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestConversationRatingAdmin {

    /**
     * One rating as a maintainer sees it.
     *
     * @param userId
     *            the user who gave it — administrators only, null for everyone else
     * @param canOpenTranscript
     *            whether the caller may open the rated conversation (it still
     *            exists and the conversation-read rule admits them)
     */
    record RatingRow(String id, String conversationId, int stepIndex, RatingKind kind, String agentId, Integer agentVersion,
            String environment, String channel, Scale scale, int score, double normalizedScore, List<String> tags, String comment,
            Instant createdAt, Instant updatedAt, ReviewStatus reviewStatus, String reviewedBy, Instant reviewedAt, String reviewNote,
            String userId, boolean canOpenTranscript) {
    }

    /**
     * A page of ratings.
     *
     * @param total
     *            how many ratings match the filter in all
     */
    record RatingPage(long total, int index, int limit, List<RatingRow> ratings) {
    }

    /** Summaries per bucket. */
    record RatingSummaries(String groupBy, List<RatingSummary> buckets) {
    }

    /**
     * A triage change. A field left out is left as it is.
     *
     * @param reviewStatus
     *            {@code NEW} or {@code REVIEWED}; the reviewer is always the
     *            caller, never taken from the body
     * @param reviewNote
     *            the internal note, at most 2000 characters; an empty string
     *            removes it
     */
    record ReviewRequest(String reviewStatus, String reviewNote) {
    }

    /** How many ratings a bulk review marked. */
    record ReviewResult(long reviewed) {
    }

    /** How many ratings need attention. */
    record AttentionCount(long count) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List conversation ratings",
               description = "Newest first (or lowest first with sort=lowest). agentId is required unless the caller is an administrator.")
    @APIResponse(responseCode = "200", description = "One page of ratings.")
    @APIResponse(responseCode = "400", description = "agentId missing for a non-administrator, or a parameter could not be read.")
    @APIResponse(responseCode = "403", description = "The caller does not hold EDIT on the agent.")
    RatingPage listRatings(@BeanParam RatingQueryParams query,
                           @QueryParam("sort") String sort,
                           @QueryParam("index")
                           @DefaultValue("0") Integer index,
                           @QueryParam("limit")
                           @DefaultValue("20") Integer limit);

    @GET
    @Path("/summary")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Summarize conversation ratings",
               description = "count, average normalized score, distribution, CSAT (five-star share of 4-5), thumbs-up rate, "
                       + "comment count and top tags, per bucket: groupBy=none|day|week|agentVersion|channel.")
    @APIResponse(responseCode = "200", description = "The summaries.")
    @APIResponse(responseCode = "400", description = "agentId missing for a non-administrator, or a parameter could not be read.")
    @APIResponse(responseCode = "403", description = "The caller does not hold EDIT on the agent.")
    RatingSummaries summarizeRatings(@BeanParam RatingQueryParams query, @QueryParam("groupBy") String groupBy);

    @GET
    @Path("/export")
    @Produces("text/csv")
    @Operation(summary = "Export conversation ratings as CSV",
               description = "Same filters as the list, newest first, at most 100,000 rows. Cells that a spreadsheet would "
                       + "read as a formula are prefixed with an apostrophe.")
    @APIResponse(responseCode = "200", description = "The CSV file.")
    @APIResponse(responseCode = "400", description = "agentId missing for a non-administrator, or a parameter could not be read.")
    @APIResponse(responseCode = "403", description = "The caller does not hold EDIT on the agent.")
    Response exportRatings(@BeanParam RatingQueryParams query);

    @GET
    @Path("/attention-count")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Count ratings that need attention",
               description = "NEW ratings that are low (normalized score at most 0.5) or carry a comment, within the caller's scope. "
                       + "Backs the Manager's Feedback badge.")
    @APIResponse(responseCode = "200", description = "The count.")
    @APIResponse(responseCode = "400", description = "agentId missing for a non-administrator.")
    @APIResponse(responseCode = "403", description = "The caller does not hold EDIT on the agent.")
    AttentionCount attentionCount(@QueryParam("agentId") String agentId);

    @PATCH
    @Path("/{ratingId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Triage one rating", description = "Sets its review status and/or internal note. The reviewer is the caller.")
    @APIResponse(responseCode = "200", description = "The updated rating.")
    @APIResponse(responseCode = "400", description = "Unknown status, or the note is too long.")
    @APIResponse(responseCode = "403", description = "The caller does not hold EDIT on the rating's agent.")
    @APIResponse(responseCode = "404", description = "No such rating.")
    @APIResponse(responseCode = "409", description = "The person re-rated meanwhile; reload and look again.")
    RatingRow reviewRating(@PathParam("ratingId") String ratingId, ReviewRequest review);

    @POST
    @Path("/review")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Mark ratings reviewed in bulk",
               description = "Marks every NEW rating matching the filter (same filters and scoping as the list) reviewed by the caller.")
    @APIResponse(responseCode = "200", description = "How many were marked.")
    @APIResponse(responseCode = "400", description = "agentId missing for a non-administrator, or a parameter could not be read.")
    @APIResponse(responseCode = "403", description = "The caller does not hold EDIT on the agent.")
    ReviewResult markReviewed(@BeanParam RatingQueryParams query);
}
