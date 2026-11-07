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
import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.ExpressionFactory;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionProvider;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.expressions.value.Value;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.stream.Stream;

import static ai.labs.eddi.engine.memory.MemoryKeys.EXPRESSIONS_PARSED;
import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.FAIL;
import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.SUCCESS;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * @author ginccc
 */
public class InputMatcherTest extends BaseMatcherTest {
    private static final String KEY_EXPRESSIONS = "expressions";
    private final String inputExpressions = "someExpression(someValue),someOtherExpression(SomeOtherValue),someThirdExpression(someNotNeededValue)";
    private final String expressionsValue = "someExpression(someValue),someOtherExpression(SomeOtherValue)";
    private Expressions expectedExpressions;
    private IExpressionProvider expressionProvider;

    @BeforeEach
    public void setUp() {
        expressionProvider = mock(IExpressionProvider.class);
        expectedExpressions = new Expressions(new Expression("someExpression", new Value("someValue")),
                new Expression("someOtherExpression", new Value("someOtherValue")));
        when(expressionProvider.parseExpressions(eq(expressionsValue))).thenAnswer(invocation -> expectedExpressions);
        matcher = new InputMatcher(expressionProvider);
    }

    @Test
    public void setValues_expressions() {
        // setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_EXPRESSIONS, expressionsValue);

        // test
        matcher.setConfigs(values);

        // assert
        Assertions.assertEquals(expectedExpressions, ((InputMatcher) matcher).getExpressions());
    }

    @Test
    public void validateConfiguration_missingExpressionsKey_isRejected() {
        // setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_OCCURRENCE, ConversationStepOccurrence.lastStep.toString());
        matcher.setConfigs(values);

        // test
        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class, () -> matcher.validateConfiguration());

        // assert
        Assertions.assertTrue(exception.getMessage().contains(InputMatcher.ID), exception.getMessage());
        Assertions.assertTrue(exception.getMessage().contains(KEY_EXPRESSIONS), exception.getMessage());
    }

    @Test
    public void validateConfiguration_blankExpressionsValue_isRejected() {
        // setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_EXPRESSIONS, "   ");
        matcher.setConfigs(values);

        // test / assert
        Assertions.assertThrows(IllegalArgumentException.class, () -> matcher.validateConfiguration());
    }

    @Test
    public void validateConfiguration_withExpressions_passes() {
        // setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_EXPRESSIONS, expressionsValue);
        matcher.setConfigs(values);

        // test / assert
        matcher.validateConfiguration();
    }

    @Test
    public void execute_occurrence_currentStep() throws Exception {
        // setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_EXPRESSIONS, expressionsValue);
        values.put(KEY_OCCURRENCE, ConversationStepOccurrence.currentStep.toString());
        matcher.setConfigs(values);
        IConversationMemory memory = mock(IConversationMemory.class);
        IWritableConversationStep currentConversationStep = mock(IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenAnswer(invocation -> currentConversationStep);
        Expressions expectedInputExpressions = new Expressions(new Expression("someExpression", new Value("someValue")),
                new Expression("someOtherExpression", new Value("someOtherValue")),
                new Expression("someThirdExpression", new Value("someNotNeededValue")));
        setupConversationStepGetLatestDataAndExpressionProviderParseExpressionsStubs(currentConversationStep, expectedInputExpressions);

        // test
        ExecutionState actualExecutionState = matcher.execute(memory, new LinkedList<>());

        // assert
        Assertions.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
        verify(memory).getCurrentStep();
        verify(currentConversationStep).getLatestData(EXPRESSIONS_PARSED);
    }

    @Test
    public void execute_occurrence_lastStep() throws Exception {
        // setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_EXPRESSIONS, expressionsValue);
        values.put(KEY_OCCURRENCE, ConversationStepOccurrence.lastStep.toString());
        matcher.setConfigs(values);
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationStep previousConversationStep = mock(IConversationStep.class);
        when(memory.getPreviousSteps())
                .thenAnswer(invocation -> new ConversationMemory.ConversationStepStack(Collections.singletonList(previousConversationStep)));
        Expressions expectedInputExpressions = new Expressions(new Expression("someExpression", new Value("someValue")),
                new Expression("someOtherExpression", new Value("someOtherValue")),
                new Expression("someThirdExpression", new Value("someNotNeededValue")));
        setupConversationStepGetLatestDataAndExpressionProviderParseExpressionsStubs(previousConversationStep, expectedInputExpressions);

        // test
        ExecutionState actualExecutionState = matcher.execute(memory, new LinkedList<>());

        // assert
        Assertions.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
        verify(memory).getPreviousSteps();
        verify(previousConversationStep).getLatestData(EXPRESSIONS_PARSED);
    }

    @Test
    public void execute_occurrence_anyStep() throws Exception {
        // setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_EXPRESSIONS, expressionsValue);
        values.put(KEY_OCCURRENCE, ConversationStepOccurrence.anyStep.toString());
        matcher.setConfigs(values);
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationStep previousConversationStep1 = mock(IConversationStep.class);
        IConversationStep previousConversationStep2 = mock(IConversationStep.class);
        setupIConversationMemoryGetAllStepsAndGetLatestDataStubs(previousConversationStep1, previousConversationStep2, memory);
        Expressions expectedInputExpressions = new Expressions(new Expression("someExpression", new Value("someValue")),
                new Expression("someOtherExpression", new Value("someOtherValue")),
                new Expression("someThirdExpression", new Value("someNotNeededValue")));
        setupExpressionProviderParseExpressionsStubs(expectedInputExpressions);

        // test
        ExecutionState actualExecutionState = matcher.execute(memory, new LinkedList<>());

        // assert
        Assertions.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
        verify(memory).getAllSteps();
        verify(previousConversationStep1).getLatestData(EXPRESSIONS_PARSED.key());
        verify(previousConversationStep2).getLatestData(EXPRESSIONS_PARSED.key());
    }

    @Test
    public void execute_occurrence_never() throws Exception {
        // setup
        Map<String, String> values = new HashMap<>();
        String expectedExpressionValue = "nonMatchingExpression(nonMatchingValue)";
        values.put(KEY_EXPRESSIONS, expectedExpressionValue);
        values.put(KEY_OCCURRENCE, ConversationStepOccurrence.never.toString());
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationStep previousConversationStep1 = mock(IConversationStep.class);
        IConversationStep previousConversationStep2 = mock(IConversationStep.class);
        when(expressionProvider.parseExpressions(eq(expectedExpressionValue)))
                .thenAnswer(invocation -> new Expressions(new Expression("nonMatchingExpression", new Value("nonMatchingValue"))));
        matcher.setConfigs(values);

        when(expressionProvider.parseExpressions(eq("someNonMatchingExpression")))
                .thenAnswer(invocation -> Collections.singletonList(new Expression("someNonMatchingExpression")));
        setupIConversationMemoryGetAllStepsAndGetLatestDataStubs(previousConversationStep1, previousConversationStep2, memory);
        Expressions expectedInputExpressions = new Expressions(new Expression("someExpression", new Value("someValue")),
                new Expression("someOtherExpression", new Value("someOtherValue")),
                new Expression("someThirdExpression", new Value("someNotNeededValue")));
        setupExpressionProviderParseExpressionsStubs(expectedInputExpressions);

        // test
        ExecutionState actualExecutionState = matcher.execute(memory, new LinkedList<>());

        // assert
        Assertions.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
        verify(memory).getAllSteps();
        verify(previousConversationStep1).getLatestData(EXPRESSIONS_PARSED.key());
        verify(previousConversationStep2).getLatestData(EXPRESSIONS_PARSED.key());
    }

    private void setupIConversationMemoryGetAllStepsAndGetLatestDataStubs(final IConversationMemory.IConversationStep previousConversationStep1,
                                                                          final IConversationMemory.IConversationStep previousConversationStep2,
                                                                          final IConversationMemory memory) {
        when(previousConversationStep1.getLatestData(eq(EXPRESSIONS_PARSED.key())))
                .thenAnswer(invocation -> new Data<>("expressions", "someNonMatchingExpression"));
        when(previousConversationStep2.getLatestData(eq(EXPRESSIONS_PARSED.key())))
                .thenAnswer(invocation -> new Data<>("expressions", inputExpressions));
        when(memory.getAllSteps()).thenAnswer(
                invocation -> new ConversationMemory.ConversationStepStack(Arrays.asList(previousConversationStep1, previousConversationStep2)));
    }

    private void setupConversationStepGetLatestDataAndExpressionProviderParseExpressionsStubs(final IConversationStep conversationStep,
                                                                                              final Expressions expectedInputExpressions) {
        when(conversationStep.getLatestData(eq(EXPRESSIONS_PARSED))).thenAnswer(invocation -> new Data<>("expressions", inputExpressions));
        when(expressionProvider.parseExpressions(eq(inputExpressions))).thenAnswer(invocation -> expectedInputExpressions);
    }

    private void setupExpressionProviderParseExpressionsStubs(final Expressions expectedInputExpressions) {
        when(expressionProvider.parseExpressions(eq(inputExpressions))).thenAnswer(invocation -> expectedInputExpressions);
        when(expressionProvider.parseExpressions(not(eq(inputExpressions)))).thenAnswer(invocation -> new Expressions());
    }

    /**
     * What a configured {@code inputmatcher} means, run against a real conversation
     * memory and the real expression parser. A comma list is an AND that must
     * appear as an ordered, adjacent sublist of the parsed input (AGENTS.md §5,
     * rule 3) — the tests above stub the parser and every lookup and assert SUCCESS
     * only, so they kept passing with the sublist check swapped for
     * {@code containsAll}.
     */
    @Nested
    class Semantics {

        static Stream<Arguments> rows() {
            return Stream.of(
                    row("a comma list matches an ordered, adjacent sublist", "greeting(hello),question(how)", "currentStep", SUCCESS,
                            "unused(x),greeting(hello),question(how),unused(y)"),
                    row("a comma list does not match when the expressions are not adjacent", "greeting(hello),question(how)", "currentStep",
                            FAIL, "greeting(hello),unused(x),question(how)"),
                    row("a comma list does not match in the reverse order", "question(how),greeting(hello)", "currentStep", FAIL,
                            "greeting(hello),question(how)"),
                    row("a single expression matches when present", "greeting(hello)", "currentStep", SUCCESS, "unused(x),greeting(hello)"),
                    row("a single expression fails when absent", "greeting(hello)", "currentStep", FAIL, "greeting(bye)"),
                    row("'empty' matches a step without parsed input", "empty", "currentStep", SUCCESS, (String) null),
                    row("'empty' fails on a step with parsed input", "empty", "currentStep", FAIL, "greeting(hello)"),
                    row("lastStep fails on the first step of a conversation", "greeting(hello)", "lastStep", FAIL, "greeting(hello)"),
                    row("lastStep reads the previous step", "greeting(hello)", "lastStep", SUCCESS, "greeting(hello)", "unused(x)"),
                    row("lastStep ignores the current step", "greeting(hello)", "lastStep", FAIL, "unused(x)", "greeting(hello)"),
                    row("anyStep matches an earlier step", "greeting(hello)", "anyStep", SUCCESS, "greeting(hello)", "unused(x)"),
                    row("anyStep fails when no step matches", "greeting(hello)", "anyStep", FAIL, "unused(x)", "unused(y)"),
                    row("never fails when an earlier step matched", "greeting(hello)", "never", FAIL, "greeting(hello)", "unused(x)"),
                    row("never matches when no step matched", "greeting(hello)", "never", SUCCESS, "unused(x)", "unused(y)"));
        }

        /**
         * @param steps
         *            the parsed input of each step, oldest first; the last is the
         *            current step, and {@code null} stores no parsed input in that step
         */
        private static Arguments row(String description, String configuredExpressions, String occurrence, ExecutionState expected,
                                     String... steps) {
            return Arguments.of(description, configuredExpressions, occurrence, expected, Arrays.asList(steps));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("rows")
        void matches(String description, String configuredExpressions, String occurrence, ExecutionState expected, List<String> steps) {
            var memory = new ConversationMemory("conversation-1", "agent-1", 1, "user-1");
            for (int i = 0; i < steps.size(); i++) {
                if (i > 0) {
                    memory.startNextStep();
                }
                if (steps.get(i) != null) {
                    memory.getCurrentStep().storeData(new Data<>(EXPRESSIONS_PARSED.key(), steps.get(i)));
                }
            }
            var inputMatcher = new InputMatcher(new ExpressionProvider(new ExpressionFactory()));
            inputMatcher.setConfigs(Map.of(KEY_EXPRESSIONS, configuredExpressions, KEY_OCCURRENCE, occurrence));
            inputMatcher.validateConfiguration();

            Assertions.assertEquals(expected, inputMatcher.execute(memory, new LinkedList<>()));
        }
    }
}