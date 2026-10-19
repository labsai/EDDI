/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStepStack;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Golden-equivalence tests for the current-step-only conversion (WS3b): the
 * memory overload of
 * {@link ConversationMemoryUtilities#convertSimpleConversationMemorySnapshot(IConversationMemory, Boolean, Boolean, List)}
 * must return exactly what the full conversion followed by the trim returned
 * before — redaction, secret-turn masking, error and undo/redo flags included —
 * for every {@code returnCurrentStepOnly} × {@code returnDetailed} ×
 * {@code returningFields} combination.
 */
class ConversationMemoryCurrentStepConversionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    // Fake values, assembled so a secret scanner does not read them as real ones.
    private static final String CREDENTIAL_VALUE = String.join("", "sk-live-", "Zq8rT2vX", "9mKp4LwN", "7bYc3HdF");
    private static final String HIDDEN_INPUT = String.join("-", "hunter2", "correct", "horse", "battery");

    static Stream<Arguments> combinations() {
        List<List<String>> fieldSets = Arrays.asList(null, List.of(), List.of(""), List.of("conversationSteps"), List.of("conversationOutputs"),
                List.of("conversationProperties"), List.of("conversationSteps", "conversationOutputs"));
        List<Arguments> args = new ArrayList<>();
        for (var memory : List.of("plain", "secretLast", "secretEarlier", "error", "redo", "single")) {
            for (boolean detailed : new boolean[]{true, false}) {
                for (boolean currentOnly : new boolean[]{true, false}) {
                    for (var fields : fieldSets) {
                        args.add(Arguments.of(memory, detailed, currentOnly, fields));
                    }
                }
            }
        }
        return args.stream();
    }

    @ParameterizedTest(name = "{0} detailed={1} currentOnly={2} fields={3}")
    @MethodSource("combinations")
    @DisplayName("memory overload equals full conversion + trim")
    void matchesFullConversion(String scenario, boolean detailed, boolean currentOnly, List<String> fields) throws Exception {
        var memory = memory(scenario);

        var expected = ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(
                ConversationMemoryUtilities.convertConversationMemory(memory), detailed, currentOnly, fields);
        var actual = ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(memory, detailed, currentOnly, fields);

        assertEquals(MAPPER.writeValueAsString(expected), MAPPER.writeValueAsString(actual));
    }

    @Test
    @DisplayName("current-step path still redacts, drops audit keys and masks a secret turn")
    void currentStepPathKeepsRedaction() throws Exception {
        var json = MAPPER.writeValueAsString(
                ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(memory("secretLast"), true, true, null));

        assertFalse(json.contains(CREDENTIAL_VALUE), json);
        assertFalse(json.contains(HIDDEN_INPUT), json);
        assertFalse(json.contains("audit:compiled_prompt"), json);
        assertTrue(json.contains(MemoryKeys.SECRET_INPUT_PLACEHOLDER), json);
    }

    @Test
    @DisplayName("current-step path converts only the newest step")
    void convertsOnlyCurrentStep() {
        var real = memory("plain");
        IConversationStepStack stack = mock(IConversationStepStack.class, delegatesTo(real.getAllSteps()));
        var memory = spy(real);
        doReturn(stack).when(memory).getAllSteps();

        SimpleConversationMemorySnapshot result = ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(memory, true, true, null);

        assertEquals(1, result.getConversationSteps().size());
        assertTrue(result.isUndoAvailable());
        // The stack is newest-first: index 0 is the current step.
        verify(stack, atLeastOnce()).get(0);
        verify(stack, never()).get(intThat(index -> index != 0));
    }

    @Test
    @DisplayName("current-step path sums the cost of every step without converting them")
    void currentStepPathSumsCostOfEveryStepWithoutConverting() {
        var real = memory("plain");
        var steps = real.getAllSteps();
        // Newest-first stack: price three of the four steps, one of them invalid.
        ((IWritableConversationStep) steps.get(0)).storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.25));
        ((IWritableConversationStep) steps.get(2)).storeData(new Data<>(MemoryKeys.AUDIT_COST, 1.5));
        ((IWritableConversationStep) steps.get(3)).storeData(new Data<>(MemoryKeys.AUDIT_COST, Double.NaN));

        Double full = ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(real, false, false, null).getConversationCost();
        IConversationStepStack stack = mock(IConversationStepStack.class, delegatesTo(steps));
        var memory = spy(real);
        doReturn(stack).when(memory).getAllSteps();
        Double currentOnly = ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(memory, false, true, null).getConversationCost();

        assertEquals(1.75, full.doubleValue(), 1e-9);
        assertEquals(full.doubleValue(), currentOnly.doubleValue(), 1e-9);
        verify(stack, never()).get(intThat(index -> index != 0));
    }

    private static ConversationMemory memory(String scenario) {
        var memory = new ConversationMemory("6612b6e5c9e77c0a1b2c3d4e", "6612b6e5c9e77c0a1b2c3d4f", 3, "user-42");
        memory.getConversationProperties().put("name", new Property("name", "Ada", Property.Scope.conversation));
        int turns = "single".equals(scenario) ? 1 : 4;
        for (int turn = 0; turn < turns; turn++) {
            if (turn > 0) {
                memory.startNextStep();
            }
            boolean secret = "secretLast".equals(scenario) && turn == turns - 1 || "secretEarlier".equals(scenario) && turn == 1;
            fillTurn(memory, turn, secret);
        }
        if ("error".equals(scenario)) {
            memory.setConversationState(ConversationState.ERROR);
            memory.getCurrentStep().addConversationOutputList(MemoryKeys.TASK_ERRORS,
                    List.of(Map.of("taskId", "ai.labs.llm", "message", "model timed out")));
        }
        if ("redo".equals(scenario)) {
            memory.undoLastStep();
        }
        return memory;
    }

    private static void fillTurn(ConversationMemory memory, int turn, boolean secret) {
        var step = memory.getCurrentStep();
        String input = secret ? HIDDEN_INPUT : "hello " + turn;
        step.storeData(new Data<>(MemoryKeys.INPUT_INITIAL.key(), input));
        step.addConversationOutputString(MemoryKeys.INPUT.key(), input);
        step.storeData(new Data<>("input:normalized", input.toLowerCase()));
        step.storeData(new Data<>("actions", List.of("greet", "turn_" + turn)));
        step.addConversationOutputList("actions", List.of("greet", "turn_" + turn));
        step.storeData(new Data<>("output:text:greet", "You said " + input));
        step.addConversationOutputList("output", List.of(Map.of("type", "text", "text", "You said " + input)));
        step.storeData(new Data<>("quickReplies:greet", List.of(Map.of("value", "again"))));
        step.storeData(new Data<>("audit:compiled_prompt", "SYSTEM PROMPT " + turn));
        step.storeData(new Data<>("httpCalls:apiKey", CREDENTIAL_VALUE));
        step.storeData(new Data<>("langchain:trace:assistant", "raw trace " + turn));
        step.addConversationOutputString("httpCalls:apiKey", CREDENTIAL_VALUE);
        if (secret) {
            step.storeData(new Data<>("context:secretInput", new Context(Context.ContextType.string, "true")));
            step.addConversationOutputMap("context", Map.of("secretInput", "true"));
        }
    }
}
