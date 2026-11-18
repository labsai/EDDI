/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationDocumentSizeTest {

    @Test
    @DisplayName("an answer stored in a step and in the output is counted at least once per copy")
    void countsEveryCopyOfAnAnswer() {
        String answer = "a".repeat(1_000_000);
        var snapshot = snapshotWithAnswer(answer);
        long estimate = ConversationDocumentSize.estimate(snapshot);
        // result + possibleResults + the conversation output: three copies.
        assertTrue(estimate >= 3_000_000, "estimate " + estimate);
        assertTrue(estimate < 3_100_000, "estimate " + estimate);
    }

    @Test
    @DisplayName("strings are counted as UTF-8, so CJK text counts three bytes a character")
    void utf8() {
        assertEquals(3, ConversationDocumentSize.utf8Length("日"));
        assertEquals(4, ConversationDocumentSize.utf8Length("😀"));
        assertEquals(2, ConversationDocumentSize.utf8Length("é"));
        assertEquals(0, ConversationDocumentSize.utf8Length(null));
        long ascii = ConversationDocumentSize.estimate(snapshotWithAnswer("a".repeat(10_000)));
        long cjk = ConversationDocumentSize.estimate(snapshotWithAnswer("日".repeat(10_000)));
        assertTrue(cjk > ascii + 50_000, "ascii " + ascii + ", cjk " + cjk);
    }

    @Test
    @DisplayName("the estimate does not undercount the JSON a store writes for a typical conversation")
    void notBelowTheSerialisedSize() throws Exception {
        var snapshot = snapshotWithAnswer("The weather in Paris is sunny with 22°C. ".repeat(200));
        snapshot.getConversationProperties().put("city", new Property("city", "Paris", Property.Scope.longTerm));
        Map<String, Object> nested = new HashMap<>();
        nested.put("list", List.of(1, 2, 3, "x".repeat(500)));
        snapshot.getConversationProperties().put("data", new Property("data", nested, Property.Scope.conversation));
        long json = new ObjectMapper().findAndRegisterModules().writeValueAsBytes(snapshot).length;
        assertTrue(ConversationDocumentSize.estimate(snapshot) >= json * 0.9,
                "estimate " + ConversationDocumentSize.estimate(snapshot) + " vs JSON " + json);
    }

    @Test
    @DisplayName("a self-referencing value ends the walk instead of overflowing it")
    void cycleIsSafe() {
        var snapshot = snapshotWithAnswer("x");
        List<Object> loop = new ArrayList<>();
        loop.add(loop);
        snapshot.getConversationOutputs().getFirst().put("loop", loop);
        assertTrue(ConversationDocumentSize.estimate(snapshot) > 0);
    }

    private static ConversationMemorySnapshot snapshotWithAnswer(String answer) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId("conversation-1");
        snapshot.setAgentId("agent-1");
        var step = new ConversationStepSnapshot();
        var workflow = new WorkflowRunSnapshot();
        workflow.getLifecycleTasks().add(new ResultSnapshot("output:text", answer, List.of(answer), new Date(), null, true));
        step.getWorkflows().add(workflow);
        snapshot.getConversationSteps().add(step);
        var output = new ConversationOutput();
        output.put("output", answer);
        snapshot.getConversationOutputs().add(output);
        return snapshot;
    }
}
