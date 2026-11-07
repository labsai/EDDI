/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStep;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.modules.rules.impl.conditions.BaseMatcher.ConversationStepOccurrence;
import ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.stream.Stream;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.FAIL;
import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.SUCCESS;
import static org.mockito.Mockito.*;

/**
 * @author ginccc
 */
public class ActionMatcherTest extends BaseMatcherTest {
    private static final String KEY_ACTIONS = "actions";
    private final String actionsValue = "someAction,someOtherAction";
    private List<String> expectedActions;
    private List<String> inputActions;

    @BeforeEach
    public void setUp() {
        expectedActions = Arrays.asList("someAction", "someOtherAction");
        inputActions = Arrays.asList("someAction", "someOtherAction", "someNotNeededAction");
        matcher = new ActionMatcher();
    }

    /**
     * An explicit JSON null ("actions": null) still satisfies containsKey, so
     * reading the key rather than the value used to hand null straight to the
     * splitter and throw an opaque NullPointerException — losing the E3 message
     * that names the offending rule. A null or blank value must reach
     * validateConfiguration() instead, which is the whole point of rejecting empty
     * matchers at save time.
     */
    @Test
    public void nullActionsValueIsRejectedWithTheRuleNamingMessage() {
        Map<String, String> values = new HashMap<>();
        values.put(KEY_ACTIONS, null);

        matcher.setConfigs(values);

        var thrown = Assertions.assertThrows(IllegalArgumentException.class, () -> matcher.validateConfiguration());
        Assertions.assertTrue(thrown.getMessage().contains(KEY_ACTIONS), thrown.getMessage());
    }

    @Test
    public void blankActionsValueIsRejectedWithTheRuleNamingMessage() {
        Map<String, String> values = new HashMap<>();
        values.put(KEY_ACTIONS, "   ");

        matcher.setConfigs(values);

        Assertions.assertThrows(IllegalArgumentException.class, () -> matcher.validateConfiguration());
    }

    @Test
    public void setValues_actions() {
        // setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_ACTIONS, actionsValue);

        // test
        matcher.setConfigs(values);

        // assert
        Assertions.assertEquals(expectedActions, ((ActionMatcher) matcher).getActions());
    }

    @Test
    public void execute_occurrence_currentStep() throws Exception {
        // setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_ACTIONS, actionsValue);
        values.put(KEY_OCCURRENCE, ConversationStepOccurrence.currentStep.toString());
        matcher.setConfigs(values);
        IConversationMemory memory = mock(IConversationMemory.class);
        IWritableConversationStep currentConversationStep = mock(IWritableConversationStep.class);
        when(currentConversationStep.getLatestData(eq(ACTIONS))).thenAnswer(invocation -> new Data<>(KEY_ACTIONS, inputActions));
        when(memory.getCurrentStep()).thenAnswer(invocation -> currentConversationStep);

        // test
        ExecutionState actualExecutionState = matcher.execute(memory, new LinkedList<>());

        // assert
        Assertions.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
        verify(memory).getCurrentStep();
        verify(currentConversationStep).getLatestData(ACTIONS);
    }

    @Test
    public void execute_occurrence_lastStep() throws Exception {
        // setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_ACTIONS, actionsValue);
        values.put(KEY_OCCURRENCE, ConversationStepOccurrence.lastStep.toString());
        matcher.setConfigs(values);
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationStep previousConversationStep = mock(IConversationStep.class);
        when(previousConversationStep.getLatestData(eq(ACTIONS))).thenAnswer(invocation -> new Data<>(KEY_ACTIONS, inputActions));
        when(memory.getPreviousSteps())
                .thenAnswer(invocation -> new ConversationMemory.ConversationStepStack(Collections.singletonList(previousConversationStep)));

        // test
        ExecutionState actualExecutionState = matcher.execute(memory, new LinkedList<>());

        // assert
        Assertions.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
        verify(memory).getPreviousSteps();
        verify(previousConversationStep).getLatestData(ACTIONS);
    }

    @Test
    public void execute_occurrence_anyStep() throws Exception {
        // setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_ACTIONS, actionsValue);
        values.put(KEY_OCCURRENCE, ConversationStepOccurrence.anyStep.toString());
        matcher.setConfigs(values);
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationStep previousConversationStep1 = mock(IConversationStep.class);
        IConversationStep previousConversationStep2 = mock(IConversationStep.class);
        setupIConversationMemoryGetAllStepsAndGetLatestDataStubs(previousConversationStep1, previousConversationStep2, memory);

        // test
        ExecutionState actualExecutionState = matcher.execute(memory, new LinkedList<>());

        // assert
        Assertions.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
        verify(memory).getAllSteps();
        verify(previousConversationStep1).getLatestData(KEY_ACTIONS);
        verify(previousConversationStep2).getLatestData(KEY_ACTIONS);
    }

    @Test
    public void execute_occurrence_never() throws Exception {
        // setup
        Map<String, String> values = new HashMap<>();
        String expectedActionValue = "nonMatchingAction";
        values.put(KEY_ACTIONS, expectedActionValue);
        values.put(KEY_OCCURRENCE, ConversationStepOccurrence.never.toString());
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationStep previousConversationStep1 = mock(IConversationStep.class);
        IConversationStep previousConversationStep2 = mock(IConversationStep.class);
        matcher.setConfigs(values);

        setupIConversationMemoryGetAllStepsAndGetLatestDataStubs(previousConversationStep1, previousConversationStep2, memory);

        // test
        ExecutionState actualExecutionState = matcher.execute(memory, new LinkedList<>());

        // assert
        Assertions.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
        verify(memory).getAllSteps();
        verify(previousConversationStep1).getLatestData(KEY_ACTIONS);
        verify(previousConversationStep2).getLatestData(KEY_ACTIONS);
    }

    private void setupIConversationMemoryGetAllStepsAndGetLatestDataStubs(final IConversationMemory.IConversationStep previousConversationStep1,
                                                                          final IConversationMemory.IConversationStep previousConversationStep2,
                                                                          final IConversationMemory memory) {
        when(previousConversationStep1.getLatestData(eq(KEY_ACTIONS)))
                .thenAnswer(invocation -> new Data<>(KEY_ACTIONS, Collections.singletonList("someNonMatchingAction")));
        when(previousConversationStep2.getLatestData(eq(KEY_ACTIONS))).thenAnswer(invocation -> new Data<>(KEY_ACTIONS, inputActions));
        when(memory.getAllSteps()).thenAnswer(
                invocation -> new ConversationMemory.ConversationStepStack(Arrays.asList(previousConversationStep1, previousConversationStep2)));
    }

    /**
     * What a configured {@code actionmatcher} means, run against a real
     * conversation memory. A comma list is an AND that must appear as an ordered,
     * adjacent sublist of the step's actions (AGENTS.md §5, rule 3) — the tests
     * above stub every lookup and assert SUCCESS only, so they kept passing with
     * the sublist check swapped for {@code containsAll}.
     */
    @Nested
    class Semantics {

        static Stream<Arguments> rows() {
            return Stream.of(
                    row("a comma list matches an ordered, adjacent sublist", "a,b", "currentStep", SUCCESS, List.of("x", "a", "b", "y")),
                    row("a comma list does not match when the actions are not adjacent", "a,c", "currentStep", FAIL, List.of("a", "b", "c")),
                    row("a comma list does not match in the reverse order", "b,a", "currentStep", FAIL, List.of("a", "b")),
                    row("a single action matches when present", "a", "currentStep", SUCCESS, List.of("x", "a")),
                    row("a single action fails when absent", "a", "currentStep", FAIL, List.of("x")),
                    row("'empty' matches a step without actions", "empty", "currentStep", SUCCESS, (List<String>) null),
                    row("'empty' fails on a step with actions", "empty", "currentStep", FAIL, List.of("a")),
                    row("lastStep fails on the first step of a conversation", "a", "lastStep", FAIL, List.of("a")),
                    row("lastStep reads the previous step", "a", "lastStep", SUCCESS, List.of("a"), List.of("x")),
                    row("lastStep ignores the current step", "a", "lastStep", FAIL, List.of("x"), List.of("a")),
                    row("anyStep matches an earlier step", "a", "anyStep", SUCCESS, List.of("a"), List.of("x")),
                    row("anyStep fails when no step matches", "a", "anyStep", FAIL, List.of("x"), List.of("y")),
                    row("never fails when an earlier step matched", "a", "never", FAIL, List.of("a"), List.of("x")),
                    row("never matches when no step matched", "a", "never", SUCCESS, List.of("x"), List.of("y")));
        }

        /**
         * @param steps
         *            the actions of each step, oldest first; the last is the current
         *            step, and {@code null} stores no actions in that step
         */
        @SafeVarargs
        private static Arguments row(String description, String configuredActions, String occurrence, ExecutionState expected,
                                     List<String>... steps) {
            return Arguments.of(description, configuredActions, occurrence, expected, Arrays.asList(steps));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("rows")
        void matches(String description, String configuredActions, String occurrence, ExecutionState expected, List<List<String>> steps) {
            var memory = new ConversationMemory("conversation-1", "agent-1", 1, "user-1");
            for (int i = 0; i < steps.size(); i++) {
                if (i > 0) {
                    memory.startNextStep();
                }
                if (steps.get(i) != null) {
                    memory.getCurrentStep().storeData(new Data<>(ACTIONS.key(), steps.get(i)));
                }
            }
            var actionMatcher = new ActionMatcher();
            actionMatcher.setConfigs(Map.of(KEY_ACTIONS, configuredActions, KEY_OCCURRENCE, occurrence));
            actionMatcher.validateConfiguration();

            Assertions.assertEquals(expected, actionMatcher.execute(memory, new LinkedList<>()));
        }
    }
}