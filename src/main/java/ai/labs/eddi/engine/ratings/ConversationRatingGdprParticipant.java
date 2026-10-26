/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings;

import ai.labs.eddi.engine.alerts.AlertErasureRedactor;
import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Joins GDPR erasure and export for the conversation rating store.
 * <p>
 * A rating is personal data — it names the user who gave it and may carry a
 * comment they wrote — and it deliberately outlives the conversation it rates,
 * so no conversation-store sweep reaches it. Registering this bean is what
 * enrols it: {@code GdprComplianceService} runs every {@link IGdprParticipant}
 * after its named stores, and puts {@link #export} into the
 * {@code additionalData} section of the export under {@link #name()}.
 * <p>
 * Erasure removes the ratings the user gave, and forgets the user as the
 * reviewer of ratings they triaged as a maintainer (the ratings themselves are
 * other people's data and stay). The export carries the user's ratings with the
 * reviewer's identity left out — that names somebody else.
 * <p>
 * An alert about a rating carries an excerpt of its comment. Alert events hold
 * no user id, so they are found through the ratings: before the ratings are
 * deleted, the excerpts of every alert about them are blanked.
 */
@ApplicationScoped
public class ConversationRatingGdprParticipant implements IGdprParticipant {

    /** An export carries at most this many ratings. */
    static final int MAX_EXPORTED_RATINGS = 10_000;

    private final IConversationRatingStore ratingStore;
    private final AlertErasureRedactor alertRedactor;

    @Inject
    public ConversationRatingGdprParticipant(IConversationRatingStore ratingStore, AlertErasureRedactor alertRedactor) {
        this.ratingStore = ratingStore;
        this.alertRedactor = alertRedactor;
    }

    @Override
    public String name() {
        return "conversationRatings";
    }

    @Override
    public long erase(String userId) {
        // First, while the ratings still say whose they are.
        List<String> ratingIds = ratingStore.readByUserId(userId, Integer.MAX_VALUE).stream().map(ConversationRating::id).toList();
        // Waits for deliveries holding a send claim on one of these alerts.
        alertRedactor.redactRatingComments(ratingIds);
        long deleted = ratingStore.deleteByUserId(userId);
        ratingStore.clearReviewer(userId);
        return deleted;
    }

    @Override
    public Object export(String userId) {
        List<ConversationRating> held = ratingStore.readByUserId(userId, MAX_EXPORTED_RATINGS);
        if (held.isEmpty()) {
            return null;
        }
        return held.stream().map(ConversationRatingGdprParticipant::withoutReviewer).toList();
    }

    private static ConversationRating withoutReviewer(ConversationRating r) {
        return new ConversationRating(r.id(), r.conversationId(), r.stepIndex(), r.agentId(), r.agentVersion(), r.environment(),
                r.channel(), r.userId(), r.scale(), r.score(), r.normalizedScore(), r.tags(), r.comment(), r.createdAt(), r.updatedAt(),
                r.reviewStatus(), null, r.reviewedAt(), r.reviewNote());
    }
}
