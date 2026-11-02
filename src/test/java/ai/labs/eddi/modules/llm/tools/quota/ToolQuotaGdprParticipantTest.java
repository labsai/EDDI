/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.gdpr.UserDataExport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        Map<String, Object> section = section(participant.export("user-1"));
        List<ToolQuotaUsage> exported = counters(section);
        assertEquals(1, exported.size());
        assertEquals("tool:a", exported.get(0).counter());
        assertEquals(1, exported.get(0).used());
        assertFalse(UserDataExport.isSectionTruncated(section));
    }

    @Test
    @DisplayName("past the cap the section is marked truncated, which counts against the bundle's completeness")
    void exportBeyondCapIsMarkedTruncated() {
        for (int i = 0; i <= ToolQuotaGdprParticipant.MAX_EXPORTED_COUNTERS; i++) {
            store.tryAcquire(key("user-1", "tool:t" + i), 5, Instant.now());
        }
        Map<String, Object> section = section(participant.export("user-1"));
        assertEquals(ToolQuotaGdprParticipant.MAX_EXPORTED_COUNTERS, counters(section).size());
        assertTrue(UserDataExport.isSectionTruncated(section));
    }

    @Test
    @DisplayName("erasure and export find default-subject counters for a padded or over-long user id")
    void normalizesTheUserIdLikeTheDefaultSubject() {
        String longId = "u".repeat(300);
        store.tryAcquire(key(ToolQuotaService.userSubject(longId), "tool:a"), 5, Instant.now());
        store.tryAcquire(key("user-1", "tool:a"), 5, Instant.now());

        assertEquals(1, counters(section(participant.export(longId))).size());
        assertEquals(1, participant.erase("  user-1  "));
        assertEquals(1, participant.erase(longId));
        assertEquals(0, participant.erase("   "));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Object exported) {
        return (Map<String, Object>) exported;
    }

    @SuppressWarnings("unchecked")
    private static List<ToolQuotaUsage> counters(Map<String, Object> section) {
        return (List<ToolQuotaUsage>) section.get("counters");
    }

    @Test
    @DisplayName("it joins the erasure cascade under a stable step name")
    void stepName() {
        assertEquals("toolQuotas", participant.name());
        assertTrue(participant.order() < IGdprParticipant.LAST);
    }
}
