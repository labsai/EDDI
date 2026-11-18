/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.memory.model.ConversationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F5: the delivery of a turn is claimed exactly once, with the outcome of the
 * claimant.
 */
class DeferredDeliveryTest {

    @Test
    @DisplayName("a turn delivered as stored can no longer be reported (or counted) as not stored")
    void storedDeliveryWinsOverALaterNotStoredClaim() {
        var delivery = new DeferredDelivery();
        List<ConversationState> delivered = new ArrayList<>();
        delivery.defer(delivered::add);

        delivery.deliver(null);

        assertFalse(delivery.deliverNotStored(TurnNotStored.Reason.OUTCOME_UNKNOWN.error()));
        assertNull(delivery.notStored(), "the stored turn's outcome is not overwritten");
        assertEquals(1, delivered.size());
    }

    @Test
    @DisplayName("the first not-stored claim keeps its precise reason; the backstop's outcome_unknown loses")
    void firstClaimKeepsItsReason() {
        var delivery = new DeferredDelivery();
        List<ConversationState> delivered = new ArrayList<>();
        delivery.defer(delivered::add);

        assertTrue(delivery.deliverNotStored(TurnNotStored.Reason.CONFLICT.error()));
        assertFalse(delivery.deliverNotStored(TurnNotStored.Reason.OUTCOME_UNKNOWN.error()));

        assertEquals("conflict", delivery.notStored().errorType());
        assertEquals(List.of(ConversationState.ERROR), delivered);
    }
}
