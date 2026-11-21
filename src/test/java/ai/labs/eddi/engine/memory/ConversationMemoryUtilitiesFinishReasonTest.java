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

import static ai.labs.eddi.engine.memory.MemoryKeys.TURN_FINISH_REASON;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B4 / B12: why a turn's answer is not a plain complete one ({@code length},
 * {@code content_filter}, {@code empty}) reaches the client — in the default
 * (non-detailed) response, which is also what the streamed {@code done} event
 * is built from. Before, it was recorded only in step data the non-detailed
 * filter drops, so only the {@code /v1} adapter (which asks for detailed
 * snapshots) could report it.
 */
class ConversationMemoryUtilitiesFinishReasonTest {

    private static ConversationMemorySnapshot truncatedTurn() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId("conv-test");
        snapshot.setAgentId("agent-test");
        snapshot.setAgentVersion(1);
        var output = new ConversationOutput();
        output.put("input:initial", "hello");
        output.put("actions", List.of("send_message"));
        output.put(TURN_FINISH_REASON, "length");
        snapshot.getConversationOutputs().add(output);
        snapshot.getConversationSteps().add(new ConversationStepSnapshot());
        return snapshot;
    }

    @Test
    @DisplayName("a non-detailed response keeps finishReason")
    void nonDetailedKeepsFinishReason() {
        var simple = ConversationMemoryUtilities.convertSimpleConversationMemory(truncatedTurn(), false, true);

        assertEquals("length", simple.getConversationOutputs().getLast().get(TURN_FINISH_REASON));
    }

    @Test
    @DisplayName("a detailed response keeps it too")
    void detailedKeepsFinishReason() {
        var simple = ConversationMemoryUtilities.convertSimpleConversationMemory(truncatedTurn(), true, true);

        assertEquals("length", simple.getConversationOutputs().getLast().get(TURN_FINISH_REASON));
    }
}
