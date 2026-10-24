/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IRestConversationRatingAdmin;
import ai.labs.eddi.engine.api.RatingQueryParams;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.ratings.IConversationRatingStore;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import ai.labs.eddi.engine.ratings.model.RatingFilter;
import ai.labs.eddi.engine.ratings.model.RatingGroupBy;
import ai.labs.eddi.engine.ratings.model.RatingKind;
import ai.labs.eddi.engine.ratings.model.RatingSort;
import ai.labs.eddi.engine.ratings.model.ReviewStatus;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.logging.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * @see IRestConversationRatingAdmin
 */
@ApplicationScoped
public class RestConversationRatingAdmin implements IRestConversationRatingAdmin {

    private static final Logger LOGGER = Logger.getLogger(RestConversationRatingAdmin.class);

    /** The largest page of the list. */
    static final int MAX_PAGE_SIZE = 100;
    /** The most rows an export carries. */
    static final int MAX_EXPORT_ROWS = 100_000;
    /** Set on an export the row cap cut short. */
    static final String TRUNCATED_HEADER = "X-Ratings-Truncated";
    /** The reviewer recorded when the caller has no name (authorization off). */
    static final String ANONYMOUS_REVIEWER = "anonymous";

    private final ResourceAccessGuard accessGuard;
    private final ConversationAccessGuard conversationAccessGuard;
    private final IConversationDescriptorStore descriptorStore;
    private final IConversationRatingStore ratingStore;
    private final Clock clock;

    @Inject
    public RestConversationRatingAdmin(ResourceAccessGuard accessGuard, ConversationAccessGuard conversationAccessGuard,
            IConversationDescriptorStore descriptorStore, IConversationRatingStore ratingStore) {
        this(accessGuard, conversationAccessGuard, descriptorStore, ratingStore, Clock.systemUTC());
    }

    /** Test seam: a fixed clock for {@code reviewedAt}. */
    RestConversationRatingAdmin(ResourceAccessGuard accessGuard, ConversationAccessGuard conversationAccessGuard,
            IConversationDescriptorStore descriptorStore, IConversationRatingStore ratingStore, Clock clock) {
        this.accessGuard = accessGuard;
        this.conversationAccessGuard = conversationAccessGuard;
        this.descriptorStore = descriptorStore;
        this.ratingStore = ratingStore;
        this.clock = clock;
    }

    @Override
    public RatingPage listRatings(RatingQueryParams query, String sort, Integer index, Integer limit) {
        RatingFilter filter = filter(query);
        RatingSort order = parse("sort", () -> RatingSort.parse(sort));
        int start = index == null ? 0 : index;
        if (start < 0) {
            throw RatingErrors.badRequest(RatingErrors.INVALID_PARAMETER, "index must be 0 or greater");
        }
        int size = limit == null ? 20 : Math.max(1, Math.min(MAX_PAGE_SIZE, limit));

        boolean includeUserId = accessGuard.isAdmin();
        Map<String, Boolean> transcriptAccess = new HashMap<>();
        List<RatingRow> rows = new ArrayList<>();
        for (ConversationRating rating : ratingStore.query(filter, order, start, size)) {
            boolean canOpen = transcriptAccess.computeIfAbsent(rating.conversationId(), this::canOpenTranscript);
            rows.add(toRow(rating, includeUserId, canOpen));
        }
        return new RatingPage(ratingStore.count(filter), start, size, rows);
    }

    @Override
    public RatingSummaries summarizeRatings(RatingQueryParams query, String groupBy) {
        RatingFilter filter = filter(query);
        RatingGroupBy grouping = parse("groupBy", () -> RatingGroupBy.parse(groupBy));
        return new RatingSummaries(groupKey(grouping), ratingStore.summarize(filter, grouping));
    }

    @Override
    public Response exportRatings(RatingQueryParams query) {
        RatingFilter filter = filter(query);
        boolean includeUserId = accessGuard.isAdmin();
        long matching = ratingStore.count(filter);
        LOGGER.infof("[RATINGS-EXPORT] '%s' exported %d conversation ratings (agentId=%s, userIds=%s)", sanitize(reviewer()),
                Math.min(matching, MAX_EXPORT_ROWS), sanitize(filter.agentId() == null ? "*" : filter.agentId()), includeUserId);

        StreamingOutput body = output -> {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            writer.write(RatingCsv.header(includeUserId));
            try {
                ratingStore.stream(filter, MAX_EXPORT_ROWS, rating -> {
                    try {
                        writer.write(RatingCsv.row(rating, includeUserId));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
            writer.flush();
        };
        Response.ResponseBuilder response = Response.ok(body, "text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"conversation-ratings.csv\"");
        if (matching > MAX_EXPORT_ROWS) {
            response.header(TRUNCATED_HEADER, "true");
        }
        return response.build();
    }

    @Override
    public AttentionCount attentionCount(String agentId) {
        return new AttentionCount(ratingStore.count(RatingFilter.needingAttention(requireScope(agentId))));
    }

    @Override
    public RatingRow reviewRating(String ratingId, ReviewRequest review) {
        ConversationRating rating = ratingStore.readById(ratingId)
                .orElseThrow(() -> RatingErrors.notFound(RatingErrors.RATING_NOT_FOUND, "Rating not found"));
        // The rating's own agent decides, exactly as agentId does on the list.
        requireAgentAccess(rating.agentId());
        if (review == null) {
            throw RatingErrors.badRequest(RatingErrors.INVALID_PARAMETER, "A body with reviewStatus and/or reviewNote is required");
        }
        ReviewStatus requested = parse("reviewStatus", () -> ReviewStatus.parse(review.reviewStatus()));
        String note = rating.reviewNote();
        if (review.reviewNote() != null) {
            String trimmed = RestConversationRating.sanitizeComment(review.reviewNote());
            if (trimmed != null && trimmed.codePointCount(0, trimmed.length()) > ConversationRating.MAX_REVIEW_NOTE_LENGTH) {
                throw RatingErrors.badRequest(RatingErrors.NOTE_TOO_LONG,
                        "reviewNote may be at most " + ConversationRating.MAX_REVIEW_NOTE_LENGTH + " characters");
            }
            note = trimmed;
        }

        ReviewStatus status = requested == null ? rating.reviewStatus() : requested;
        String reviewedBy = rating.reviewedBy();
        Instant reviewedAt = rating.reviewedAt();
        if (requested == ReviewStatus.REVIEWED) {
            // From the principal, never from the body.
            reviewedBy = reviewer();
            reviewedAt = clock.instant();
        } else if (requested == ReviewStatus.NEW) {
            reviewedBy = null;
            reviewedAt = null;
        }
        ConversationRating updated = ratingStore.updateReview(rating.id(), rating.updatedAt(), status == null ? ReviewStatus.NEW : status,
                reviewedBy, reviewedAt, note)
                .orElseThrow(() -> RatingErrors.conflict(RatingErrors.RATING_CHANGED,
                        "The rating was changed or withdrawn meanwhile; reload it and review again"));
        return toRow(updated, accessGuard.isAdmin(), canOpenTranscript(updated.conversationId()));
    }

    @Override
    public ReviewResult markReviewed(RatingQueryParams query) {
        RatingFilter filter = filter(query);
        String reviewer = reviewer();
        long marked = ratingStore.markReviewed(filter, reviewer, clock.instant());
        LOGGER.infof("[RATINGS-REVIEW] '%s' marked %d conversation ratings reviewed (agentId=%s)", sanitize(reviewer), marked,
                sanitize(filter.agentId() == null ? "*" : filter.agentId()));
        return new ReviewResult(marked);
    }

    /**
     * The filter of a request, after the scope check: a caller who does not see
     * everything must name an agent and hold {@code EDIT} on it.
     */
    RatingFilter filter(RatingQueryParams query) {
        RatingQueryParams q = query == null ? new RatingQueryParams() : query;
        String scopedAgent = requireScope(q.agentId);
        requireScore("minScore", q.minScore);
        requireScore("maxScore", q.maxScore);
        return new RatingFilter(scopedAgent, q.agentVersion, blankToNull(q.environment), instant("from", q.from, false),
                instant("to", q.to, true), q.minScore, q.maxScore, parse("kind", () -> RatingKind.parse(q.kind)), blankToNull(q.tag),
                q.hasComment, parse("reviewStatus", () -> ReviewStatus.parse(q.reviewStatus)), q.needsAttention,
                blankToNull(q.conversationId));
    }

    String requireScope(String agentId) {
        String id = blankToNull(agentId);
        if (id == null) {
            if (!accessGuard.seesEverything()) {
                throw RatingErrors.badRequest(RatingErrors.AGENT_ID_REQUIRED, "agentId is required");
            }
            return null;
        }
        requireAgentAccess(id);
        return id;
    }

    private void requireAgentAccess(String agentId) {
        if (accessGuard.seesEverything()) {
            return;
        }
        if (agentId == null) {
            // A rating that names no agent cannot be checked: administrators only.
            throw RatingErrors.forbidden(RatingErrors.NOT_PERMITTED, "Access denied");
        }
        // Throws ForbiddenException (403) without EDIT.
        accessGuard.requireAccess(agentId, AccessLevel.EDIT, "agent");
    }

    private String reviewer() {
        return conversationAccessGuard.callerActor(ANONYMOUS_REVIEWER);
    }

    /**
     * Whether the caller may open the rated conversation: it still exists, and the
     * conversation-read rule admits them — owner, administrator, or a maintainer of
     * an agent version with conversation review enabled.
     */
    boolean canOpenTranscript(String conversationId) {
        ConversationDescriptor descriptor;
        try {
            descriptor = descriptorStore.readDescriptor(conversationId, 0);
        } catch (ResourceNotFoundException e) {
            return false;
        } catch (ResourceStoreException | RuntimeException e) {
            LOGGER.debugf("Could not check transcript access to %s: %s", sanitize(conversationId), sanitize(e.getMessage()));
            return false;
        }
        if (descriptor == null) {
            return false;
        }
        return conversationAccessGuard.canAccessConversation(descriptor.getUserId())
                || conversationAccessGuard.canReview(descriptor.getAgentResource());
    }

    private static RatingRow toRow(ConversationRating rating, boolean includeUserId, boolean canOpenTranscript) {
        return new RatingRow(rating.id(), rating.conversationId(), rating.stepIndex(), rating.kind(), rating.agentId(),
                rating.agentVersion(), rating.environment(), rating.channel(), rating.scale(), rating.score(), rating.normalizedScore(),
                rating.tags(), rating.comment(), rating.createdAt(), rating.updatedAt(), rating.reviewStatus(), rating.reviewedBy(),
                rating.reviewedAt(), rating.reviewNote(), includeUserId ? rating.userId() : null, canOpenTranscript);
    }

    private static String groupKey(RatingGroupBy grouping) {
        return switch (grouping) {
            case NONE -> "none";
            case DAY -> "day";
            case WEEK -> "week";
            case AGENT_VERSION -> "agentVersion";
            case CHANNEL -> "channel";
        };
    }

    private static void requireScore(String name, Double value) {
        if (value != null && (value.isNaN() || value < 0 || value > 1)) {
            throw RatingErrors.badRequest(RatingErrors.INVALID_PARAMETER, name + " is a normalized score between 0 and 1");
        }
    }

    /**
     * An ISO-8601 instant, or a date read as UTC — its start for {@code from}, the
     * start of the next day for the exclusive {@code to}.
     */
    static Instant instant(String name, String value, boolean exclusiveEnd) {
        String text = blankToNull(value);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException notAnInstant) {
            try {
                LocalDate date = LocalDate.parse(text);
                return (exclusiveEnd ? date.plusDays(1) : date).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException notADate) {
                throw RatingErrors.badRequest(RatingErrors.INVALID_PARAMETER,
                        name + " must be an ISO-8601 instant (2026-10-01T00:00:00Z) or date (2026-10-01)");
            }
        }
    }

    private interface Parser<T> {
        T parse();
    }

    private static <T> T parse(String name, Parser<T> parser) {
        try {
            return parser.parse();
        } catch (IllegalArgumentException e) {
            String message = e.getMessage() != null && e.getMessage().startsWith(name) ? e.getMessage() : name + " has an unknown value";
            throw RatingErrors.badRequest(RatingErrors.INVALID_PARAMETER, message);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
