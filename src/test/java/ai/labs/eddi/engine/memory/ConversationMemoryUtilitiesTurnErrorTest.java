/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The top-level {@code error} of a non-streaming response: present for an ERROR
 * turn that reported a reason, shaped like the streaming {@code task_failed}
 * event, and absent otherwise.
 */
@DisplayName("ConversationMemoryUtilities.turnError")
class ConversationMemoryUtilitiesTurnErrorTest {

    private static ConversationOutput outputWithDigest() {
        var output = new ConversationOutput();
        output.put(MemoryKeys.TASK_ERRORS, List.of(Map.of("type", "errorDigest", "taskId", "eddi://ai.labs.llm", "taskType", "langchain",
                "errorType", "LifecycleException", "text", "Task 'eddi://ai.labs.llm' failed: mock upstream failure")));
        return output;
    }

    @Test
    @DisplayName("an ERROR turn reports the failing task, its error type and the sanitized message")
    void errorTurnCarriesTheDigest() {
        var error = ConversationMemoryUtilities.turnError(ConversationState.ERROR, List.of(new ConversationOutput(), outputWithDigest()));

        assertEquals("eddi://ai.labs.llm", error.taskId());
        assertEquals("langchain", error.taskType());
        assertEquals("LifecycleException", error.errorType());
        assertEquals("Task 'eddi://ai.labs.llm' failed: mock upstream failure", error.message());
    }

    @Test
    @DisplayName("a healthy turn, or an ERROR turn with nothing reported, has no error field")
    void noErrorOtherwise() {
        assertNull(ConversationMemoryUtilities.turnError(ConversationState.READY, List.of(outputWithDigest())));
        assertNull(ConversationMemoryUtilities.turnError(ConversationState.ERROR, List.of(new ConversationOutput())));
        assertNull(ConversationMemoryUtilities.turnError(ConversationState.ERROR, List.of()));
        // An earlier turn's failure does not describe the latest turn.
        assertNull(ConversationMemoryUtilities.turnError(ConversationState.ERROR, List.of(outputWithDigest(), new ConversationOutput())));
    }
}
