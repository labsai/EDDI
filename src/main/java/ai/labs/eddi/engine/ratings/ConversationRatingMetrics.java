/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings;

import ai.labs.eddi.engine.ratings.model.ConversationRating;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Micrometer meters for conversation ratings, exposed at {@code /q/metrics}:
 * <ul>
 * <li>{@code eddi.conversation.ratings} — counter of stored ratings, tagged
 * {@code kind} (conversation|message), {@code scale}, {@code bucket}
 * (low|neutral|high, see {@link RatingScales#bucket}) and {@code agentId};</li>
 * <li>{@code eddi.conversation.ratings.normalized} — distribution of the
 * normalized score (0..1), tagged {@code agentId} and {@code kind};</li>
 * <li>{@code eddi.conversation.ratings.withdrawn} — counter of withdrawn
 * ratings, tagged {@code kind}.</li>
 * </ul>
 * A re-rating counts again: the counter measures rating events, the stored rows
 * (and the {@code /ratings/summary} endpoint) hold the current state.
 */
@ApplicationScoped
public class ConversationRatingMetrics {

    static final String RATINGS = "eddi.conversation.ratings";
    static final String NORMALIZED = "eddi.conversation.ratings.normalized";
    static final String WITHDRAWN = "eddi.conversation.ratings.withdrawn";

    private final MeterRegistry registry;

    @Inject
    public ConversationRatingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Records one stored rating. */
    public void recordRating(ConversationRating rating) {
        String kind = rating.kind().value();
        String agentId = rating.agentId() == null ? "unknown" : rating.agentId();
        Counter.builder(RATINGS)
                .description("Conversation ratings given")
                .tag("kind", kind)
                .tag("scale", rating.scale().name())
                .tag("bucket", RatingScales.bucket(rating.normalizedScore()))
                .tag("agentId", agentId)
                .register(registry)
                .increment();
        DistributionSummary.builder(NORMALIZED)
                .description("Normalized conversation rating score (0..1)")
                .tag("agentId", agentId)
                .tag("kind", kind)
                .register(registry)
                .record(rating.normalizedScore());
    }

    /** Records a withdrawn rating. */
    public void recordWithdrawal(boolean conversationRating) {
        Counter.builder(WITHDRAWN)
                .description("Conversation ratings withdrawn by the person who gave them")
                .tag("kind", conversationRating ? "conversation" : "message")
                .register(registry)
                .increment();
    }
}
