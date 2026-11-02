/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig.Scale;
import ai.labs.eddi.engine.alerts.AlertErasureRedactor;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import ai.labs.eddi.engine.ratings.model.ReviewStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ratings join GDPR erasure and export")
class ConversationRatingGdprParticipantTest {

    private final IConversationRatingStore store = mock(IConversationRatingStore.class);
    private final AlertErasureRedactor alerts = mock(AlertErasureRedactor.class);
    private final ConversationRatingGdprParticipant participant = new ConversationRatingGdprParticipant(store, alerts);

    @Test
    @DisplayName("erasure deletes every rating the user gave — after blanking the comment excerpts alerts copied from them")
    void erase() {
        Instant at = Instant.parse("2026-10-24T10:00:00Z");
        var rating = new ConversationRating("r1", "c", -1, "a", 1, null, "api", "u1", Scale.THUMBS, 0, 0.0, List.of(), "awful", at, at);
        when(store.readByUserId("u1", Integer.MAX_VALUE)).thenReturn(List.of(rating));
        when(store.deleteByUserId("u1")).thenReturn(4L);

        assertEquals(4, participant.erase("u1"));
        var order = inOrder(alerts, store);
        order.verify(alerts).redactRatingComments(List.of("r1"));
        order.verify(store).deleteByUserId("u1");
        verify(store).clearReviewer("u1");
        assertEquals("conversationRatings", participant.name());
    }

    @Test
    @DisplayName("export lists the user's ratings, capped, without the reviewer's identity, and nothing when there are none")
    void export() {
        Instant at = Instant.parse("2026-10-24T10:00:00Z");
        var rating = new ConversationRating("r", "c", -1, "a", 1, null, "api", "u1", Scale.THUMBS, 1, 1.0, List.of(), "hi", at, at,
                ReviewStatus.REVIEWED, "maintainer@example", at, "followed up");
        when(store.readByUserId("u1", ConversationRatingGdprParticipant.MAX_EXPORTED_RATINGS)).thenReturn(List.of(rating));

        List<?> exported = (List<?>) participant.export("u1");
        ConversationRating only = (ConversationRating) exported.get(0);
        assertNull(only.reviewedBy(), "the reviewer is somebody else's personal data");
        assertEquals("hi", only.comment());
        assertEquals(ReviewStatus.REVIEWED, only.reviewStatus());
        assertNull(participant.export("nobody"));
    }
}
