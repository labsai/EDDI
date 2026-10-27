/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings.rest;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig.Scale;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IRestAgentProfile.RatingProfile;
import ai.labs.eddi.engine.api.IRestConversationRating;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.ConversationDescriptorMemo;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.ratings.ConversationRatingMetrics;
import ai.labs.eddi.engine.ratings.ConversationRatingPolicy;
import ai.labs.eddi.engine.ratings.IConversationRatingStore;
import ai.labs.eddi.engine.ratings.RatingLimits;
import ai.labs.eddi.engine.ratings.RatingScales;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.utils.RestUtilities;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * @see IRestConversationRating
 */
@ApplicationScoped
public class RestConversationRating implements IRestConversationRating {

    private static final Logger LOGGER = Logger.getLogger(RestConversationRating.class);

    /** The channels a rating may name; anything else is stored as {@code other}. */
    static final Set<String> KNOWN_CHANNELS = Set.of("chat-ui", "manager", "api", "mcp", "slack", "teams", "other");
    static final String DEFAULT_CHANNEL = "api";
    static final String OTHER_CHANNEL = "other";

    private final ConversationAccessGuard conversationAccessGuard;
    private final OwnershipValidator ownershipValidator;
    private final SecurityIdentity identity;
    private final IConversationDescriptorStore descriptorStore;
    private final Instance<ConversationDescriptorMemo> descriptorMemo;
    private final IConversationMemoryStore memoryStore;
    private final ConversationRatingPolicy ratingPolicy;
    private final IConversationRatingStore ratingStore;
    private final GdprComplianceService gdprComplianceService;
    private final ConversationRatingMetrics metrics;
    private final Clock clock;

    @Inject
    public RestConversationRating(ConversationAccessGuard conversationAccessGuard, OwnershipValidator ownershipValidator,
            SecurityIdentity identity, IConversationDescriptorStore descriptorStore, Instance<ConversationDescriptorMemo> descriptorMemo,
            IConversationMemoryStore memoryStore, ConversationRatingPolicy ratingPolicy, IConversationRatingStore ratingStore,
            GdprComplianceService gdprComplianceService, ConversationRatingMetrics metrics) {
        this(conversationAccessGuard, ownershipValidator, identity, descriptorStore, descriptorMemo, memoryStore, ratingPolicy,
                ratingStore, gdprComplianceService, metrics, Clock.systemUTC());
    }

    /** Test seam: a fixed clock for the rating window. */
    RestConversationRating(ConversationAccessGuard conversationAccessGuard, OwnershipValidator ownershipValidator,
            SecurityIdentity identity, IConversationDescriptorStore descriptorStore, Instance<ConversationDescriptorMemo> descriptorMemo,
            IConversationMemoryStore memoryStore, ConversationRatingPolicy ratingPolicy, IConversationRatingStore ratingStore,
            GdprComplianceService gdprComplianceService, ConversationRatingMetrics metrics, Clock clock) {
        this.conversationAccessGuard = conversationAccessGuard;
        this.ownershipValidator = ownershipValidator;
        this.identity = identity;
        this.descriptorStore = descriptorStore;
        this.descriptorMemo = descriptorMemo;
        this.memoryStore = memoryStore;
        this.ratingPolicy = ratingPolicy;
        this.ratingStore = ratingStore;
        this.gdprComplianceService = gdprComplianceService;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public ConversationRating rateConversation(String conversationId, RatingRequest rating) {
        return rate(conversationId, ConversationRating.CONVERSATION_STEP_INDEX, rating);
    }

    @Override
    public ConversationRating rateStep(String conversationId, int stepIndex, RatingRequest rating) {
        if (stepIndex < 0) {
            // Checked again after ownership; refusing early never leaks anything, since
            // a negative index is invalid for every conversation.
            throw RatingErrors.badRequest(RatingErrors.INVALID_STEP, "stepIndex must be 0 or greater");
        }
        return rate(conversationId, stepIndex, rating);
    }

    @Override
    public ConversationRatings readRatings(String conversationId) {
        // The owner, or an administrator — who may read the transcript itself anyway.
        conversationAccessGuard.requireConversationOwner(conversationId);
        ConversationRating conversation = null;
        List<ConversationRating> steps = new ArrayList<>();
        for (ConversationRating stored : ratingStore.readByConversation(conversationId)) {
            // The maintainers' triage (status, reviewer, internal note) is not the
            // rater's to see.
            if (stored.isConversationRating()) {
                conversation = stored.withoutReview();
            } else {
                steps.add(stored.withoutReview());
            }
        }
        return new ConversationRatings(conversation, steps, controlsOf(conversationId));
    }

    /**
     * The rating controls of the version this conversation runs on — resolved the
     * same way {@link #rate} resolves it, so a client never shows a control whose
     * write would be refused. Null when that version takes no ratings, or when the
     * conversation can no longer be resolved (a read must not fail for it).
     */
    private RatingProfile controlsOf(String conversationId) {
        try {
            ConversationDescriptor descriptor = liveDescriptor(conversationId);
            ConversationMemorySnapshot snapshot = loadSnapshot(conversationId);
            String agentId = snapshot != null && snapshot.getAgentId() != null ? snapshot.getAgentId() : agentIdOf(descriptor);
            Integer agentVersion = snapshot != null && snapshot.getAgentVersion() != null ? snapshot.getAgentVersion() : agentVersionOf(descriptor);
            return ratingPolicy.ratingConfigOf(agentId, agentVersion).map(RatingProfile::of).orElse(null);
        } catch (WebApplicationException e) {
            return null;
        }
    }

    @Override
    public Response withdrawConversationRating(String conversationId) {
        return withdraw(conversationId, ConversationRating.CONVERSATION_STEP_INDEX);
    }

    @Override
    public Response withdrawStepRating(String conversationId, int stepIndex) {
        if (stepIndex < 0) {
            throw RatingErrors.badRequest(RatingErrors.INVALID_STEP, "stepIndex must be 0 or greater");
        }
        return withdraw(conversationId, stepIndex);
    }

    private Response withdraw(String conversationId, int stepIndex) {
        // Withdrawing is the owner's (or an administrator's, moderating) call; it is
        // never blocked by the rating window, a disabled config or a restriction —
        // removing personal data is always allowed.
        conversationAccessGuard.requireConversationOwner(conversationId);
        if (ratingStore.delete(conversationId, stepIndex)) {
            metrics.recordWithdrawal(stepIndex == ConversationRating.CONVERSATION_STEP_INDEX);
        }
        return Response.noContent().build();
    }

    private ConversationRating rate(String conversationId, int stepIndex, RatingRequest request) {
        // 1. Who: the conversation's owner, and nobody on their behalf.
        String owner = conversationAccessGuard.requireConversationOwner(conversationId);
        if (owner == null || owner.isBlank()) {
            // An administrator addressing a conversation with no descriptor or no
            // owner: there is nobody whose rating this could be.
            throw RatingErrors.notFound(RatingErrors.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        if (!ownershipValidator.isOwner(identity, owner)) {
            // requireConversationOwner admits administrators too. A rating is the
            // owner's own voice; an administrator writing one would forge it.
            throw RatingErrors.forbidden(RatingErrors.NOT_CONVERSATION_OWNER, "Only the person who had the conversation may rate it");
        }
        if (gdprComplianceService.isProcessingRestricted(owner)) {
            throw new ProcessingRestrictedException("Processing is restricted for this user (GDPR Art. 18)");
        }

        // 2. Which conversation, on which agent version.
        ConversationDescriptor descriptor = liveDescriptor(conversationId);
        ConversationMemorySnapshot snapshot = loadSnapshot(conversationId);
        String agentId = snapshot != null && snapshot.getAgentId() != null ? snapshot.getAgentId() : agentIdOf(descriptor);
        Integer agentVersion = snapshot != null && snapshot.getAgentVersion() != null ? snapshot.getAgentVersion() : agentVersionOf(descriptor);

        // 3. Whether that version takes this rating, and whether it is too late.
        boolean conversationRating = stepIndex == ConversationRating.CONVERSATION_STEP_INDEX;
        RatingConfig config = ratingPolicy.ratingConfigOf(agentId, agentVersion)
                .orElseThrow(() -> RatingErrors.conflict(RatingErrors.RATING_DISABLED, "Ratings are not enabled for this agent"));
        if (conversationRating ? !config.isConversationRatingEnabled() : !config.isMessageRatingEnabled()) {
            throw RatingErrors.conflict(RatingErrors.RATING_DISABLED,
                    conversationRating ? "This agent does not ask for conversation ratings" : "This agent does not ask for reply ratings");
        }
        requireWithinWindow(config, descriptor);
        if (!conversationRating) {
            requireAgentOutput(snapshot, stepIndex);
        }

        // 4. What: score, tags, comment.
        if (request == null) {
            throw RatingErrors.badRequest(RatingErrors.INVALID_SCORE, "A rating needs a body with a score");
        }
        Scale scale = conversationRating ? config.getScale() : Scale.THUMBS;
        if (!RatingScales.isValid(scale, request.score())) {
            throw RatingErrors.badRequest(RatingErrors.INVALID_SCORE,
                    scale == Scale.THUMBS ? "score must be 0 (down) or 1 (up)" : "score must be between 1 and 5");
        }
        List<String> tags = validTags(config, request.tags());
        String comment = validComment(config, request.comment());

        ConversationRating rating = new ConversationRating(null, conversationId, stepIndex, agentId, agentVersion,
                environmentOf(snapshot, descriptor), channelOf(request.channel()), owner, scale, request.score(),
                RatingScales.normalize(scale, request.score()), tags, comment, null, null);
        ConversationRating stored = ratingStore.upsert(rating);
        metrics.recordRating(stored);
        return stored.withoutReview();
    }

    private ConversationDescriptor liveDescriptor(String conversationId) {
        ConversationDescriptorMemo.Resolved resolved;
        try {
            resolved = ConversationDescriptorMemo.resolve(descriptorStore, descriptorMemo, conversationId);
        } catch (ResourceStoreException e) {
            LOGGER.warnf("Could not load the descriptor of conversation %s for a rating: %s", sanitize(conversationId),
                    sanitize(e.getMessage()));
            throw new InternalServerErrorException("Could not load the conversation");
        }
        if (resolved.descriptor() == null || resolved.archived()) {
            // A deleted conversation takes no new ratings; existing ones stay readable.
            throw RatingErrors.notFound(RatingErrors.CONVERSATION_NOT_FOUND, "Conversation not found");
        }
        return resolved.descriptor();
    }

    private ConversationMemorySnapshot loadSnapshot(String conversationId) {
        try {
            return memoryStore.loadConversationMemorySnapshot(conversationId);
        } catch (ResourceNotFoundException e) {
            // Purged memory with a surviving descriptor: the descriptor still names the
            // agent; a reply rating then finds no step and is refused.
            return null;
        } catch (ResourceStoreException e) {
            LOGGER.warnf("Could not load conversation %s for a rating: %s", sanitize(conversationId), sanitize(e.getMessage()));
            throw new InternalServerErrorException("Could not load the conversation");
        }
    }

    private void requireWithinWindow(RatingConfig config, ConversationDescriptor descriptor) {
        int hours = config.getRatingWindowHours();
        if (hours <= 0 || descriptor.getLastModifiedOn() == null) {
            return;
        }
        Instant closes = descriptor.getLastModifiedOn().toInstant().plus(Duration.ofHours(hours));
        if (clock.instant().isAfter(closes)) {
            throw RatingErrors.conflict(RatingErrors.RATING_WINDOW_CLOSED, "This conversation can no longer be rated");
        }
    }

    private static void requireAgentOutput(ConversationMemorySnapshot snapshot, int stepIndex) {
        List<ConversationOutput> outputs = snapshot == null ? null : snapshot.getConversationOutputs();
        if (outputs == null || stepIndex >= outputs.size() || !hasOutput(outputs.get(stepIndex))) {
            throw RatingErrors.badRequest(RatingErrors.INVALID_STEP, "Step " + stepIndex + " holds no agent reply");
        }
    }

    private static boolean hasOutput(Map<String, Object> output) {
        Object value = output == null ? null : output.get("output");
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof CharSequence text) {
            return !text.toString().isBlank();
        }
        return value != null;
    }

    private static List<String> validTags(RatingConfig config, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        List<String> allowed = config.getReasonTags() == null ? List.of() : config.getReasonTags();
        Set<String> tags = new LinkedHashSet<>();
        for (String tag : requested) {
            if (tag == null || !allowed.contains(tag)) {
                throw RatingErrors.badRequest(RatingErrors.INVALID_TAG,
                        allowed.isEmpty() ? "This agent defines no reason tags" : "Unknown reason tag; allowed are " + allowed);
            }
            tags.add(tag);
        }
        return List.copyOf(tags);
    }

    private static String validComment(RatingConfig config, String requested) {
        String comment = sanitizeComment(requested);
        if (comment == null) {
            return null;
        }
        if (!config.isCommentEnabled()) {
            throw RatingErrors.badRequest(RatingErrors.COMMENT_NOT_ALLOWED, "This agent does not accept comments");
        }
        int max = RatingLimits.commentMaxLength(config);
        if (comment.codePointCount(0, comment.length()) > max) {
            throw RatingErrors.badRequest(RatingErrors.COMMENT_TOO_LONG, "The comment may be at most " + max + " characters");
        }
        return comment;
    }

    /**
     * Trims the comment and drops control characters other than line breaks and
     * tabs (a NUL cannot even be stored in PostgreSQL); blank becomes null. The
     * comment stays plain text — clients render it escaped.
     */
    static String sanitizeComment(String comment) {
        if (comment == null) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(comment.length());
        comment.codePoints().filter(cp -> cp == '\n' || cp == '\r' || cp == '\t' || !Character.isISOControl(cp))
                .forEach(cleaned::appendCodePoint);
        String trimmed = cleaned.toString().strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String channelOf(String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_CHANNEL;
        }
        String channel = requested.trim().toLowerCase(Locale.ROOT);
        return KNOWN_CHANNELS.contains(channel) ? channel : OTHER_CHANNEL;
    }

    private static String environmentOf(ConversationMemorySnapshot snapshot, ConversationDescriptor descriptor) {
        if (snapshot != null && snapshot.getEnvironment() != null) {
            return snapshot.getEnvironment().name();
        }
        return descriptor.getEnvironment() == null ? null : descriptor.getEnvironment().name();
    }

    private static String agentIdOf(ConversationDescriptor descriptor) {
        IResourceId id = resourceIdOf(descriptor);
        return id == null ? null : id.getId();
    }

    private static Integer agentVersionOf(ConversationDescriptor descriptor) {
        IResourceId id = resourceIdOf(descriptor);
        return id == null ? null : id.getVersion();
    }

    private static IResourceId resourceIdOf(ConversationDescriptor descriptor) {
        if (descriptor == null || descriptor.getAgentResource() == null) {
            return null;
        }
        try {
            return RestUtilities.extractResourceId(descriptor.getAgentResource());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
