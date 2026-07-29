/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G11 — {@code convertConversationMemorySnapshot} indexed the step list by the
 * OUTPUT loop index, so any drift between the two lists (a legacy document, an
 * interrupted writer) threw {@link IndexOutOfBoundsException} and made the
 * whole conversation unloadable.
 */
class ConversationMemoryUtilitiesDriftTest {

    private ConversationMemorySnapshot snapshotWith(int steps, int outputs) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId("aabbccddeeff112233445566");
        snapshot.setAgentId("agent-1");
        snapshot.setAgentVersion(1);
        snapshot.setUserId("user-1");

        List<ConversationStepSnapshot> stepSnapshots = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            var resultSnapshot = new ResultSnapshot();
            resultSnapshot.setKey("input:initial");
            resultSnapshot.setResult("turn-" + i);

            var workflow = new WorkflowRunSnapshot();
            workflow.getLifecycleTasks().add(resultSnapshot);

            var step = new ConversationStepSnapshot();
            step.getWorkflows().add(workflow);
            stepSnapshots.add(step);
        }
        snapshot.setConversationSteps(stepSnapshots);

        List<ConversationOutput> conversationOutputs = new ArrayList<>();
        for (int i = 0; i < outputs; i++) {
            var output = new ConversationOutput();
            output.put("input", "turn-" + i);
            conversationOutputs.add(output);
        }
        snapshot.setConversationOutputs(conversationOutputs);
        return snapshot;
    }

    @Test
    @DisplayName("G11 — fewer steps than outputs loads with a warning instead of throwing")
    void fewerStepsThanOutputsStillLoads() {
        var snapshot = snapshotWith(1, 3);

        var memory = ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot);

        assertNotNull(memory);
        assertEquals(3, memory.getConversationOutputs().size(), "every output must still produce a step in the memory");
        assertEquals(3, memory.size());
        // Only the FIRST output had a matching step snapshot; the drifted tail is
        // skipped rather than throwing IndexOutOfBoundsException.
        assertNull(memory.getCurrentStep().getLatestData("input:initial"));
    }

    @Test
    @DisplayName("G11 — matching step/output counts restore every step's data")
    void matchingCountsRestoreEverything() {
        var snapshot = snapshotWith(3, 3);

        var memory = ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot);

        assertEquals(3, memory.size());
        assertEquals("turn-2", memory.getCurrentStep().getLatestData("input:initial").getResult());
    }
}
