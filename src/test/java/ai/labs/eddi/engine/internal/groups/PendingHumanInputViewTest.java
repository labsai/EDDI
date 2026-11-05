/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.PendingHumanInput;
import ai.labs.eddi.configs.groups.model.HumanRequestDetail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PendingHumanInputView — the kind fields every summary carries")
class PendingHumanInputViewTest {

    private static GroupConversation pending(HumanRequestDetail detail) {
        var gc = new GroupConversation();
        gc.setPendingHumanInput(new PendingHumanInput("h1", "H", 0, 0, -1, "HUMAN_INPUT", "p", "SKIP_TURN", Instant.now(), detail));
        return gc;
    }

    @Test
    @DisplayName("nothing pending adds nothing")
    void nothingPending() {
        Map<String, Object> summary = new LinkedHashMap<>();
        PendingHumanInputView.addTo(summary, new GroupConversation(), true);
        assertTrue(summary.isEmpty());
        assertNull(PendingHumanInputView.kindOf(new GroupConversation()));
    }

    @Test
    @DisplayName("a plain turn is TURN")
    void turn() {
        Map<String, Object> summary = new LinkedHashMap<>();
        PendingHumanInputView.addTo(summary, pending(null), true);
        assertEquals(Map.of("pendingKind", "TURN"), summary);
    }

    @Test
    @DisplayName("a tie-break carries its options")
    void tieBreak() {
        Map<String, Object> summary = new LinkedHashMap<>();
        PendingHumanInputView.addTo(summary, pending(HumanRequestDetail.tieBreak(List.of("A", "B"), 3, "NO_DECISION")), true);
        assertEquals(Map.of("pendingKind", "TIE_BREAK", "pendingOptions", List.of("A", "B")), summary);
    }

    @Test
    @DisplayName("a task carries its task id")
    void task() {
        Map<String, Object> summary = new LinkedHashMap<>();
        PendingHumanInputView.addTo(summary, pending(new HumanRequestDetail(HumanRequestDetail.KIND_TASK, null, "t-7", null, null)), true);
        assertEquals(Map.of("pendingKind", "TASK", "pendingTaskId", "t-7"), summary);
    }

    @Test
    @DisplayName("a caller who may not read discussion content gets the kind but not the tie-break options")
    void optionsFollowContentVisibility() {
        var summary = new LinkedHashMap<String, Object>();

        PendingHumanInputView.addTo(summary, pending(HumanRequestDetail.tieBreak(List.of("A", "B"), 3, "NO_DECISION")), false);

        assertEquals("TIE_BREAK", summary.get("pendingKind"));
        assertFalse(summary.containsKey("pendingOptions"));
    }
}
