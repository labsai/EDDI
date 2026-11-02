/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ToolQuotaGdprParticipant")
class ToolQuotaGdprParticipantTest {

    private final InMemoryToolQuotaStore store = new InMemoryToolQuotaStore();
    private final ToolQuotaGdprParticipant participant = new ToolQuotaGdprParticipant(store);

    private static ToolQuotaKey key(String subject, String counter) {
        return new ToolQuotaKey("aaaaaaaaaaaaaaaaaaaaaaaa", counter, subject, 1_000L);
    }

    @Test
    @DisplayName("erasure deletes the erased user's counters and only theirs")
    void erasesBySubject() {
        store.tryAcquire(key("user-1", "tool:a"), 5, Instant.now());
        store.tryAcquire(key("user-1", "pool:p"), 5, Instant.now());
        store.tryAcquire(key("user-2", "tool:a"), 5, Instant.now());

        assertEquals(2, participant.erase("user-1"));

        assertEquals(0, store.peek(key("user-1", "tool:a")));
        assertEquals(1, store.peek(key("user-2", "tool:a")));
    }

    @Test
    @DisplayName("export carries the user's counters, or nothing when there are none")
    void exports() {
        assertNull(participant.export("user-1"));
        store.tryAcquire(key("user-1", "tool:a"), 5, Instant.now());
        @SuppressWarnings("unchecked")
        List<ToolQuotaUsage> exported = (List<ToolQuotaUsage>) participant.export("user-1");
        assertEquals(1, exported.size());
        assertEquals("tool:a", exported.get(0).counter());
        assertEquals(1, exported.get(0).used());
    }

    @Test
    @DisplayName("it joins the erasure cascade under a stable step name")
    void stepName() {
        assertEquals("toolQuotas", participant.name());
        assertTrue(participant.order() < IGdprParticipant.LAST);
    }
}
