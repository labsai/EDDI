/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.TurnError;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // An entry without the machine-readable keys is a generic, non-retryable
        // failure.
        assertEquals(TurnError.TURN_FAILED, error.code());
        assertFalse(error.retryable());
        assertNull(error.retryAfterMs());
    }

    @Test
    @DisplayName("one object carries the failing task and the machine-readable verdict")
    void combinedObject() {
        var output = new ConversationOutput();
        output.put(MemoryKeys.TASK_ERRORS,
                List.of(Map.of("type", "errorDigest", "taskId", "eddi://ai.labs.llm", "taskType", "langchain", "errorType", "LifecycleException",
                        "text", "Task 'eddi://ai.labs.llm' failed: slow down", TurnError.KEY_CODE, "RATE_LIMITED", TurnError.KEY_RETRYABLE, true,
                        TurnError.KEY_RETRY_AFTER_MS, 1500L, TurnError.KEY_MESSAGE, "slow down")));

        var error = ConversationMemoryUtilities.turnError(ConversationState.ERROR, List.of(output));

        assertEquals("RATE_LIMITED", error.code());
        assertTrue(error.retryable());
        assertEquals(1500L, error.retryAfterMs());
        assertEquals("Task 'eddi://ai.labs.llm' failed: slow down", error.message());
        assertEquals("eddi://ai.labs.llm", error.taskId());
        assertEquals("langchain", error.taskType());
        assertEquals("LifecycleException", error.errorType());
    }

    @Test
    @DisplayName("the JSON keeps every field of both shapes; task fields are omitted only when unknown")
    void jsonShape() throws Exception {
        var mapper = new ObjectMapper();
        var full = mapper.readTree(mapper.writeValueAsString(
                ConversationMemoryUtilities.turnError(ConversationState.ERROR, List.of(outputWithDigest()))));
        assertEquals("eddi://ai.labs.llm", full.get("taskId").asText());
        assertEquals("langchain", full.get("taskType").asText());
        assertEquals("LifecycleException", full.get("errorType").asText());
        assertEquals("TURN_FAILED", full.get("code").asText());
        assertTrue(full.has("retryable"));
        assertTrue(full.has("retryAfterMs") && full.get("retryAfterMs").isNull(), "retryAfterMs:null is emitted");
        assertTrue(full.has("message"));

        var bare = mapper.readTree(mapper.writeValueAsString(TurnError.internal("boom")));
        assertFalse(bare.has("taskId") || bare.has("taskType") || bare.has("errorType"));
        assertTrue(bare.has("retryAfterMs"));
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
