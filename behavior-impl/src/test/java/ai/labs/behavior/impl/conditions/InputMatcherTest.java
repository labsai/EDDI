package ai.labs.behavior.impl.conditions;

import ai.labs.behavior.impl.conditions.BaseMatcher.ConversationStepOccurrence;
import ai.labs.behavior.impl.conditions.IBehaviorCondition.ExecutionState;
import ai.labs.expressions.Expression;
import ai.labs.expressions.Expressions;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.expressions.value.Value;
import ai.labs.memory.ConversationMemory;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IConversationMemory.IConversationStep;
import ai.labs.memory.model.Data;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import static ai.labs.memory.IConversationMemory.IWritableConversationStep;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author ginccc
 */
public class InputMatcherTest extends BaseMatcherTest {
    private static final String KEY_EXPRESSIONS = "expressions";
    private final String inputExpressions = "someExpression(someValue),someOtherExpression(SomeOtherValue),someThirdExpression(someNotNeededValue)";
    private final String expressionsValue = "someExpression(someValue),someOtherExpression(SomeOtherValue)";
    private Expressions expectedExpressions;
    private IExpressionProvider expressionProvider;

    @Before
    public void setUp() {
        expressionProvider = mock(IExpressionProvider.class);
        expectedExpressions = new Expressions(
                new Expression("someExpression", new Value("someValue")),
                new Expression("someOtherExpression", new Value("someOtherValue")));
        when(expressionProvider.parseExpressions(eq(expressionsValue))).thenAnswer(invocation -> expectedExpressions);
        matcher = new InputMatcher(expressionProvider);
    }

    @Test
    public void setValues_expressions() {
        //setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_EXPRESSIONS, expressionsValue);

        //test
        matcher.setConfigs(values);

        //assert
        Assert.assertEquals(expectedExpressions, ((InputMatcher) matcher).getExpressions());
    }

    @Test
    public void execute_occurrence_currentStep() throws Exception {
        //setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_EXPRESSIONS, expressionsValue);
        values.put(KEY_OCCURRENCE, ConversationStepOccurrence.currentStep.toString());
        matcher.setConfigs(values);
        IConversationMemory memory = mock(IConversationMemory.class);
        IWritableConversationStep currentConversationStep = mock(IWritableConversationStep.class);
        when(currentConversationStep.getLatestData(eq(KEY_EXPRESSIONS))).thenAnswer(invocation ->
                new Data<>("expressions", inputExpressions));
        when(memory.getCurrentStep()).thenAnswer(invocation -> currentConversationStep);
        Expressions expectedInputExpressions = new Expressions(
                new Expression("someExpression", new Value("someValue")),
                new Expression("someOtherExpression", new Value("someOtherValue")),
                new Expression("someThirdExpression", new Value("someNotNeededValue")));
        when(expressionProvider.parseExpressions(eq(inputExpressions))).thenAnswer(invocation -> expectedInputExpressions);

        //test
        ExecutionState actualExecutionState = matcher.execute(memory, new LinkedList<>());

        //assert
        Assert.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
        verify(memory).getCurrentStep();
        verify(currentConversationStep).getLatestData(KEY_EXPRESSIONS);
    }

    @Test
    public void execute_occurrence_lastStep() throws Exception {
        //setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_EXPRESSIONS, expressionsValue);
        values.put(KEY_OCCURRENCE, ConversationStepOccurrence.lastStep.toString());
        matcher.setConfigs(values);
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationStep previousConversationStep = mock(IConversationStep.class);
        when(previousConversationStep.getLatestData(eq(KEY_EXPRESSIONS))).thenAnswer(invocation ->
                new Data<>("expressions", inputExpressions));
        when(memory.getPreviousSteps()).thenAnswer(invocation ->
                new ConversationMemory.ConversationStepStack(Collections.singletonList(previousConversationStep)));
        Expressions expectedInputExpressions = new Expressions(
                new Expression("someExpression", new Value("someValue")),
                new Expression("someOtherExpression", new Value("someOtherValue")),
                new Expression("someThirdExpression", new Value("someNotNeededValue")));
        when(expressionProvider.parseExpressions(eq(inputExpressions))).thenAnswer(invocation -> expectedInputExpressions);

        //test
        ExecutionState actualExecutionState = matcher.execute(memory, new LinkedList<>());

        //assert
        Assert.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
        verify(memory).getPreviousSteps();
        verify(previousConversationStep).getLatestData(KEY_EXPRESSIONS);
    }

    @Test
    public void execute_occurrence_anyStep() throws Exception {
        //setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_EXPRESSIONS, expressionsValue);
        values.put(KEY_OCCURRENCE, ConversationStepOccurrence.anyStep.toString());
        matcher.setConfigs(values);
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationStep previousConversationStep1 = mock(IConversationStep.class);
        IConversationStep previousConversationStep2 = mock(IConversationStep.class);
        when(previousConversationStep1.getLatestData(eq(KEY_EXPRESSIONS))).thenAnswer(invocation ->
                new Data<>("expressions", "someNonMatchingExpression"));
        when(previousConversationStep2.getLatestData(eq(KEY_EXPRESSIONS))).thenAnswer(invocation ->
                new Data<>("expressions", inputExpressions));
        when(memory.getAllSteps()).thenAnswer(invocation ->
                new ConversationMemory.ConversationStepStack(Arrays.asList(previousConversationStep1,
                        previousConversationStep2)));
        Expressions expectedInputExpressions = new Expressions(
                new Expression("someExpression", new Value("someValue")),
                new Expression("someOtherExpression", new Value("someOtherValue")),
                new Expression("someThirdExpression", new Value("someNotNeededValue")));
        when(expressionProvider.parseExpressions(eq(inputExpressions))).thenAnswer(invocation -> expectedInputExpressions);
        when(expressionProvider.parseExpressions(not(eq(inputExpressions)))).thenAnswer(invocation -> new Expressions());

        //test
        ExecutionState actualExecutionState = matcher.execute(memory, new LinkedList<>());

        //assert
        Assert.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
        verify(memory).getAllSteps();
        verify(previousConversationStep1).getLatestData(KEY_EXPRESSIONS);
        verify(previousConversationStep2).getLatestData(KEY_EXPRESSIONS);
    }

    @Test
    public void execute_occurrence_never() throws Exception {
        //setup
        Map<String, String> values = new HashMap<>();
        String expectedExpressionValue = "nonMatchingExpression(nonMatchingValue)";
        values.put(KEY_EXPRESSIONS, expectedExpressionValue);
        values.put(KEY_OCCURRENCE, ConversationStepOccurrence.never.toString());
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationStep previousConversationStep1 = mock(IConversationStep.class);
        IConversationStep previousConversationStep2 = mock(IConversationStep.class);
        when(expressionProvider.parseExpressions(eq(expectedExpressionValue))).
                thenAnswer(invocation ->
                        new Expressions(
                                new Expression("nonMatchingExpression",
                                        new Value("nonMatchingValue"))));
        matcher.setConfigs(values);

        when(expressionProvider.parseExpressions(eq("someNonMatchingExpression"))).
                thenAnswer(invocation ->
                        Collections.singletonList(new Expression("someNonMatchingExpression")));
        when(previousConversationStep1.getLatestData(eq(KEY_EXPRESSIONS))).thenAnswer(invocation ->
                new Data<>("expressions", "someNonMatchingExpression"));
        when(previousConversationStep2.getLatestData(eq(KEY_EXPRESSIONS))).thenAnswer(invocation ->
                new Data<>("expressions", inputExpressions));
        when(memory.getAllSteps()).thenAnswer(invocation ->
                new ConversationMemory.ConversationStepStack(Arrays.asList(previousConversationStep1,
                        previousConversationStep2)));
        Expressions expectedInputExpressions = new Expressions(
                new Expression("someExpression", new Value("someValue")),
                new Expression("someOtherExpression", new Value("someOtherValue")),
                new Expression("someThirdExpression", new Value("someNotNeededValue")));
        when(expressionProvider.parseExpressions(eq(inputExpressions))).thenAnswer(invocation -> expectedInputExpressions);
        when(expressionProvider.parseExpressions(not(eq(inputExpressions)))).thenAnswer(invocation -> new Expressions());

        //test
        ExecutionState actualExecutionState = matcher.execute(memory, new LinkedList<>());

        //assert
        Assert.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
        verify(memory).getAllSteps();
        verify(previousConversationStep1).getLatestData(KEY_EXPRESSIONS);
        verify(previousConversationStep2).getLatestData(KEY_EXPRESSIONS);
    }
}