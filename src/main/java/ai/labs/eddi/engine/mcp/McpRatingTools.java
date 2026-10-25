/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IRestConversationRatingAdmin;
import ai.labs.eddi.engine.api.IRestConversationRatingAdmin.ReviewRequest;
import ai.labs.eddi.engine.api.RatingQueryParams;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import static ai.labs.eddi.engine.mcp.McpToolUtils.codedFailure;
import static ai.labs.eddi.engine.mcp.McpToolUtils.errorJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.requireAnyRole;

/**
 * MCP tools for conversation ratings — the read and triage half of
 * {@code IRestConversationRatingAdmin}: list, summarize, count what needs
 * attention, and review one rating. Export and bulk review stay REST-only.
 * <p>
 * <b>Same rule as REST, applied to the MCP caller.</b> The tools call the
 * in-process rating resource bean, not the loopback REST client: that bean's
 * {@code ResourceAccessGuard} and {@code ConversationAccessGuard} read the
 * request's {@link SecurityIdentity}, which here is the MCP caller. So an
 * administrator sees every agent; an editor must name an agent
 * ({@code AGENT_ID_REQUIRED} otherwise) and hold {@code EDIT} on it; the
 * rater's userId is returned to administrators only; and the reviewer recorded
 * by {@code review_conversation_rating} is the caller's principal — the same
 * name the Manager's Feedback page records, so one person is one reviewer on
 * both surfaces. The loopback client would send no credentials at all: 401 with
 * authorization on, and no scoping with it off.
 * <p>
 * Tier {@link McpRoles#AUTHOR} (admin, editor), equal to the REST
 * {@code @RolesAllowed}: {@code eddi-viewer} is an MCP read role for
 * conversations it may open, and a rating carries the rater's free-text comment
 * about a conversation the viewer could not read.
 *
 * @since 6.6.0
 */
@ApplicationScoped
@McpErrorResults
public class McpRatingTools {

    private static final Logger LOGGER = Logger.getLogger(McpRatingTools.class);

    private static final String AGENT_ID_ARG = "Agent id. Required unless you are an administrator (an editor gets AGENT_ID_REQUIRED "
            + "without it, and needs EDIT permission on the agent)";

    private final IRestConversationRatingAdmin ratings;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public McpRatingTools(IRestConversationRatingAdmin ratings, IJsonSerialization jsonSerialization, SecurityIdentity identity,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.ratings = ratings;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    @Tool(name = "list_conversation_ratings", description = "List the ratings people gave an agent's conversations and replies "
            + "(thumbs up/down or 1-5 stars, tags and comments), newest first or lowest score first, one page at a time. "
            + "Each row has the score normalized to 0..1, the agent version and environment it was given on, its review status "
            + "(NEW or REVIEWED) and whether you may open the transcript. needsAttention=true lists exactly the triage queue: "
            + "NEW ratings that are low (normalized 0.5 or less) or carry a comment.")
    public String listConversationRatings(@ToolArg(description = AGENT_ID_ARG, required = false) String agentId,
                                          @ToolArg(description = "Only ratings given on this agent version", required = false) Integer agentVersion,
                                          @ToolArg(description = "Only this environment: production or test",
                                                   required = false) String environment,
                                          @ToolArg(description = "From this ISO-8601 instant or date, inclusive", required = false) String from,
                                          @ToolArg(description = "To this ISO-8601 instant, exclusive, or date, inclusive",
                                                   required = false) String to,
                                          @ToolArg(description = "Lowest normalized score, 0..1", required = false) Double minScore,
                                          @ToolArg(description = "Highest normalized score, 0..1", required = false) Double maxScore,
                                          @ToolArg(description = "conversation or message", required = false) String kind,
                                          @ToolArg(description = "Only ratings carrying this tag", required = false) String tag,
                                          @ToolArg(description = "true: only ratings with a comment; false: only without",
                                                   required = false) Boolean hasComment,
                                          @ToolArg(description = "NEW or REVIEWED", required = false) String reviewStatus,
                                          @ToolArg(description = "true: only the triage queue (NEW and low or commented)",
                                                   required = false) Boolean needsAttention,
                                          @ToolArg(description = "Only the ratings of this conversation", required = false) String conversationId,
                                          @ToolArg(description = "newest or lowest (default: newest)", required = false) String sort,
                                          @ToolArg(description = "Page start, default 0", required = false) Integer index,
                                          @ToolArg(description = "Page size, default 20, max 100", required = false) Integer limit) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        try {
            RatingQueryParams query = query(agentId, agentVersion, environment, from, to, minScore, maxScore, kind, tag, hasComment,
                    reviewStatus, needsAttention, conversationId);
            return jsonSerialization.serialize(ratings.listRatings(query, sort, index, limit));
        } catch (Exception e) {
            return failure("list_conversation_ratings", e);
        }
    }

    @Tool(name = "summarize_conversation_ratings", description = "Aggregate an agent's ratings: count, average normalized score, "
            + "score distribution, CSAT (share of 4-5 star ratings), thumbs-up rate, comment count and top tags — overall, or "
            + "bucketed by day, week, agentVersion or channel. To see how an agent is rated since a version, group by agentVersion "
            + "and compare the buckets from that version on.")
    public String summarizeConversationRatings(@ToolArg(description = AGENT_ID_ARG, required = false) String agentId,
                                               @ToolArg(description = "Only ratings given on this agent version",
                                                        required = false) Integer agentVersion,
                                               @ToolArg(description = "Only this environment: production or test",
                                                        required = false) String environment,
                                               @ToolArg(description = "From this ISO-8601 instant or date, inclusive", required = false) String from,
                                               @ToolArg(description = "To this ISO-8601 instant, exclusive, or date, inclusive",
                                                        required = false) String to,
                                               @ToolArg(description = "Lowest normalized score, 0..1", required = false) Double minScore,
                                               @ToolArg(description = "Highest normalized score, 0..1", required = false) Double maxScore,
                                               @ToolArg(description = "conversation or message", required = false) String kind,
                                               @ToolArg(description = "Only ratings carrying this tag", required = false) String tag,
                                               @ToolArg(description = "true: only ratings with a comment; false: only without",
                                                        required = false) Boolean hasComment,
                                               @ToolArg(description = "NEW or REVIEWED", required = false) String reviewStatus,
                                               @ToolArg(description = "true: only the triage queue (NEW and low or commented)",
                                                        required = false) Boolean needsAttention,
                                               @ToolArg(description = "none, day, week, agentVersion or channel (default: none)",
                                                        required = false) String groupBy) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        try {
            RatingQueryParams query = query(agentId, agentVersion, environment, from, to, minScore, maxScore, kind, tag, hasComment,
                    reviewStatus, needsAttention, null);
            return jsonSerialization.serialize(ratings.summarizeRatings(query, groupBy));
        } catch (Exception e) {
            return failure("summarize_conversation_ratings", e);
        }
    }

    @Tool(name = "count_ratings_needing_attention", description = "Count the ratings in the triage queue: NEW (not yet reviewed) "
            + "ratings that are low (normalized 0.5 or less) or carry a comment. A re-rating puts a reviewed rating back in the queue.")
    public String countRatingsNeedingAttention(@ToolArg(description = AGENT_ID_ARG, required = false) String agentId) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        try {
            return jsonSerialization.serialize(ratings.attentionCount(agentId));
        } catch (Exception e) {
            return failure("count_ratings_needing_attention", e);
        }
    }

    @Tool(name = "review_conversation_rating", description = "Triage one rating: mark it REVIEWED (records you as the reviewer, "
            + "with the time) or reopen it as NEW, and/or set a review note (at most 2000 characters; an empty note clears it). "
            + "Needs EDIT permission on the rating's agent. If the person re-rated or withdrew the rating meanwhile, nothing is "
            + "changed and the error is RATING_CHANGED: read it again and decide again.")
    public String reviewConversationRating(@ToolArg(description = "Rating id, from list_conversation_ratings (required)") String ratingId,
                                           @ToolArg(description = "REVIEWED or NEW; omit to change only the note",
                                                    required = false) String reviewStatus,
                                           @ToolArg(description = "Review note; omit to keep the current one", required = false) String reviewNote) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (ratingId == null || ratingId.isBlank()) {
            return errorJson("ratingId is required", "BAD_REQUEST", null);
        }
        if ((reviewStatus == null || reviewStatus.isBlank()) && reviewNote == null) {
            return errorJson("Give reviewStatus, reviewNote or both", "BAD_REQUEST", null);
        }
        try {
            return jsonSerialization.serialize(ratings.reviewRating(ratingId.trim(), new ReviewRequest(reviewStatus, reviewNote)));
        } catch (Exception e) {
            return failure("review_conversation_rating", e);
        }
    }

    private static RatingQueryParams query(String agentId, Integer agentVersion, String environment, String from, String to,
                                           Double minScore, Double maxScore, String kind, String tag, Boolean hasComment,
                                           String reviewStatus, Boolean needsAttention, String conversationId) {
        RatingQueryParams query = new RatingQueryParams();
        query.agentId = agentId;
        query.agentVersion = agentVersion;
        query.environment = environment;
        query.from = from;
        query.to = to;
        query.minScore = minScore;
        query.maxScore = maxScore;
        query.kind = kind;
        query.tag = tag;
        query.hasComment = hasComment;
        query.reviewStatus = reviewStatus;
        query.needsAttention = needsAttention;
        query.conversationId = conversationId;
        return query;
    }

    /**
     * Keeps the rating resource's error codes; see
     * {@link McpToolUtils#codedFailure}.
     */
    private static String failure(String tool, Exception e) {
        return codedFailure(LOGGER, tool, e);
    }
}
