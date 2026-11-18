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
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.undercouch.bson4jackson.BsonFactory;
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

    /**
     * S7 calibration: the estimate against the BSON the MongoDB store really writes
     * (the same bson4jackson mapper PersistenceModule configures), for a step-heavy
     * document of small results — the shape the first estimate under-counted by
     * ~28% (field names, timestamps, array index names).
     */
    @Test
    @DisplayName("calibration: never below the real BSON size, at most 35% above it")
    void calibratedAgainstRealBson() throws Exception {
        var bson = new ObjectMapper(new BsonFactory());
        bson.registerModule(new JavaTimeModule());
        bson.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        new SerializationCustomizer(false).customize(bson);

        for (int[] shape : new int[][]{{2000, 30, 1}, {200, 30, 12}, {500, 10, 2000}, {10, 5, 12}}) {
            var snapshot = generated(shape[0], shape[1], shape[2]);
            long real = bson.writeValueAsBytes(snapshot).length;
            long estimate = ConversationDocumentSize.estimate(snapshot);
            String shown = "steps=%d results=%d valueLength=%d: estimate %d, BSON %d".formatted(shape[0], shape[1], shape[2], estimate, real);
            assertTrue(estimate >= real, shown);
            assertTrue(estimate <= real * 1.35, shown);
        }
    }

    private static ConversationMemorySnapshot generated(int steps, int results, int valueLength) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId("aabbccddeeff112233445566");
        snapshot.setAgentId("agent-1");
        snapshot.setUserId("user-1");
        for (int i = 0; i < steps; i++) {
            var step = new ConversationStepSnapshot();
            var workflow = new WorkflowRunSnapshot();
            for (int r = 0; r < results; r++) {
                Object value = switch (r % 3) {
                    case 0 -> "v".repeat(valueLength);
                    case 1 -> List.of("a", "b");
                    default -> Map.of("k", "x".repeat(valueLength));
                };
                workflow.getLifecycleTasks().add(new ResultSnapshot("key:" + r, value, List.of(value), new Date(), "wf-1", r % 2 == 0));
            }
            step.getWorkflows().add(workflow);
            var output = new ConversationOutput();
            output.put("input", "hello " + i);
            output.put("actions", List.of("greet"));
            output.put("output", List.of(Map.of("type", "text", "text", "answer " + i)));
            step.setConversationOutput(output);
            snapshot.getConversationSteps().add(step);
            snapshot.getConversationOutputs().add(output);
        }
        return snapshot;
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
