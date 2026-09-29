/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.MemoryKeys.TASK_ERRORS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failed task's report survives the non-detailed response — the default one,
 * and the one the Manager and the streaming {@code done} event use.
 * <p>
 * The key was missing from the list of output keys that response keeps, so the
 * report was written and then stripped: the caller got {@code ERROR} and
 * nothing to say why — under strict write's {@code digest} mode as well, whose
 * docs said the UI could render it.
 */
class ConversationMemoryUtilitiesTaskErrorsTest {

    private static ConversationMemorySnapshot failedTurn() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId("conv-test");
        snapshot.setAgentId("agent-test");
        snapshot.setAgentVersion(1);
        var output = new ConversationOutput();
        output.put("input:initial", "hello");
        output.put("expressions", "unknown(hello)");
        output.put("actions", List.of("send_message"));
        output.put(TASK_ERRORS, List.of(Map.of("type", "errorDigest", "text", "Task 'eddi://ai.labs.llm' failed: quota exceeded")));
        snapshot.getConversationOutputs().add(output);
        snapshot.getConversationSteps().add(new ConversationStepSnapshot());
        return snapshot;
    }

    @Test
    @DisplayName("a non-detailed response keeps taskErrors")
    void nonDetailedKeepsTaskErrors() {
        var simple = ConversationMemoryUtilities.convertSimpleConversationMemory(failedTurn(), false, true);

        var output = simple.getConversationOutputs().getLast();
        assertTrue(output.containsKey(TASK_ERRORS), "the failure report must reach the caller: " + output.keySet());
        assertFalse(output.containsKey("expressions"), "the non-detailed filter still applies to everything else");
        assertEquals(List.of("send_message"), output.get("actions"));
    }

    @Test
    @DisplayName("a detailed response keeps it too")
    void detailedKeepsTaskErrors() {
        var simple = ConversationMemoryUtilities.convertSimpleConversationMemory(failedTurn(), true, true);

        assertTrue(simple.getConversationOutputs().getLast().containsKey(TASK_ERRORS));
    }
}
