/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.internal;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Strict write discipline rolls a failed task's output back with these. Only
 * removing added keys left a changed key changed — and under
 * {@code continueOnFailure} the failed task's output then reached the reply.
 */
class OutputRollbackTest {

    @Test
    @DisplayName("a list the task appended to in place gets its previous contents back")
    void restoresAListAppendedInPlace() {
        var output = new ConversationOutput();
        output.put("output", new ArrayList<>(List.of("hello")));
        var snapshot = LifecycleManager.snapshotOutput(output);

        @SuppressWarnings("unchecked")
        var list = (List<Object>) output.get("output");
        list.add("half-written by the failed task");
        LifecycleManager.restoreOutput(output, snapshot);

        assertEquals(List.of("hello"), output.get("output"));
    }

    @Test
    @DisplayName("a replaced value and a map changed in place are restored, an added key removed")
    void restoresReplacedAndRemovesAdded() {
        var output = new ConversationOutput();
        output.put("input", "hi");
        output.put("meta", new LinkedHashMap<>(Map.of("a", 1)));
        var snapshot = LifecycleManager.snapshotOutput(output);

        output.put("input", "changed");
        @SuppressWarnings("unchecked")
        var meta = (Map<String, Object>) output.get("meta");
        meta.put("b", 2);
        output.put("added", "x");
        LifecycleManager.restoreOutput(output, snapshot);

        assertEquals("hi", output.get("input"));
        assertEquals(Map.of("a", 1), output.get("meta"));
        assertFalse(output.containsKey("added"));
    }
}
