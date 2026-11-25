/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents.spend;

import ai.labs.eddi.engine.documents.spend.IDocumentSpendStore.SubjectKind;
import ai.labs.eddi.engine.gdpr.UserDataExport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DocumentSpendGdprParticipant")
class DocumentSpendGdprParticipantTest {

    private static final String USER = "5f9a1c2b3d4e5f6a7b8c9d0e";
    private static final long TODAY = 20_414L;

    private final InMemoryDocumentSpendStore store = new InMemoryDocumentSpendStore();
    private final DocumentSpendGdprParticipant participant = new DocumentSpendGdprParticipant(store);

    @Test
    @DisplayName("reports under documentSpend in both SPIs and has nothing in flight to stop")
    void names() {
        assertEquals("documentSpend", participant.name());
        assertEquals("documentSpend", participant.erasureStepName());
        assertEquals(0, participant.stopInFlightWork(USER));
    }

    @Test
    @DisplayName("erases the user's buckets by the exact id and keeps team buckets")
    void erase() {
        store.add(SubjectKind.USER, USER, TODAY, 1.0, TODAY - 34);
        store.add(SubjectKind.USER, " " + USER, TODAY, 1.0, TODAY - 34);
        store.add(SubjectKind.TEAM, "team:/acme", TODAY, 1.0, TODAY - 34);

        assertEquals(1L, participant.erase(USER));
        assertTrue(store.findBySubject(SubjectKind.USER, USER, 10).isEmpty());
        assertEquals(1.0, store.spent(SubjectKind.TEAM, "team:/acme", TODAY), 1e-12);
        assertEquals(0L, participant.erase(null));
        assertEquals(0L, participant.erase(""));
    }

    @Test
    @DisplayName("exports day, dollars and calls, newest first")
    @SuppressWarnings("unchecked")
    void export() {
        store.add(SubjectKind.USER, USER, TODAY - 1, 0.5, TODAY - 35);
        store.add(SubjectKind.USER, USER, TODAY, 1.25, TODAY - 34);
        store.add(SubjectKind.USER, USER, TODAY, 0.0, TODAY - 34);

        Map<String, Object> section = (Map<String, Object>) participant.export(USER);

        List<Map<String, Object>> days = (List<Map<String, Object>>) section.get("days");
        assertEquals(2, days.size());
        assertEquals(Map.of("day", "2025-11-22", "spentUsd", 1.25, "calls", 2L), days.getFirst());
        assertEquals("2025-11-21", days.get(1).get("day"));
        assertEquals(false, section.get(UserDataExport.SECTION_TRUNCATED));
        assertNull(participant.export("someone-else"));
        assertNull(participant.export(null));
    }

    @Test
    @DisplayName("marks an export past the cap as truncated")
    void truncated() {
        for (int i = 0; i <= DocumentSpendGdprParticipant.MAX_EXPORTED_DAYS; i++) {
            store.add(SubjectKind.USER, USER, TODAY - i, 0.01, 0);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> section = (Map<String, Object>) participant.export(USER);
        assertEquals(DocumentSpendGdprParticipant.MAX_EXPORTED_DAYS, ((List<?>) section.get("days")).size());
        assertEquals(true, section.get(UserDataExport.SECTION_TRUNCATED));
    }
}
