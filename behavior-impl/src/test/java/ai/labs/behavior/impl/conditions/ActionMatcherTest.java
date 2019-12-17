package ai.labs.behavior.impl.conditions;

import ai.labs.behavior.impl.conditions.BaseMatcher.ConversationStepOccurrence;
import ai.labs.behavior.impl.conditions.IBehaviorCondition.ExecutionState;
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
import java.util.List;
import java.util.Map;

import static ai.labs.memory.IConversationMemory.IWritableConversationStep;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author ginccc
 */
public class ActionMatcherTest extends BaseMatcherTest {
    private static final String KEY_ACTIONS = "actions";
    private final String actionsValue = "someAction,someOtherAction";
    private List<String> expectedActions;
    private List<String> inputActions;

    @Before
    public void setUp() {
        expectedActions = Arrays.asList("someAction", "someOtherAction");
        inputActions = Arrays.asList("someAction", "someOtherAction", "someNotNeededAction");
        matcher = new ActionMatcher();
    }

    @Test
    public void setValues_actions() {
        //setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_ACTIONS, actionsValue);

        //test
        matcher.setConfigs(values);

        //assert
        Assert.assertEquals(expectedActions, ((ActionMatcher) matcher).getActions());
    }

    @Test
    public void execute_occurrence_currentStep() throws Exception {
        //setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_ACTIONS, actionsValue);
        values.put(KEY_OCCURRENCE, ConversationStepOccurrence.currentStep.toString());
        matcher.setConfigs(values);
        IConversationMemory memory = mock(IConversationMemory.class);
        IWritableConversationStep currentConversationStep = mock(IWritableConversationStep.class);
        when(currentConversationStep.getLatestData(eq(KEY_ACTIONS))).thenAnswer(invocation ->
                new Data<>(KEY_ACTIONS, inputActions));
        when(memory.getCurrentStep()).thenAnswer(invocation -> currentConversationStep);

        //test
        ExecutionState actualExecutionState = matcher.execute(memory, new LinkedList<>());

        //assert
        Assert.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
        verify(memory).getCurrentStep();
        verify(currentConversationStep).getLatestData(KEY_ACTIONS);
    }

    @Test
    public void execute_occurrence_lastStep() throws Exception {
        //setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_ACTIONS, actionsValue);
        values.put(KEY_OCCURRENCE, ConversationStepOccurrence.lastStep.toString());
        matcher.setConfigs(values);
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationStep previousConversationStep = mock(IConversationStep.class);
        when(previousConversationStep.getLatestData(eq(KEY_ACTIONS))).thenAnswer(invocation ->
                new Data<>(KEY_ACTIONS, inputActions));
        when(memory.getPreviousSteps()).thenAnswer(invocation ->
                new ConversationMemory.ConversationStepStack(Collections.singletonList(previousConversationStep)));

        //test
        ExecutionState actualExecutionState = matcher.execute(memory, new LinkedList<>());

        //assert
        Assert.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
        verify(memory).getPreviousSteps();
        verify(previousConversationStep).getLatestData(KEY_ACTIONS);
    }

    @Test
    public void execute_occurrence_anyStep() throws Exception {
        //setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_ACTIONS, actionsValue);
        values.put(KEY_OCCURRENCE, ConversationStepOccurrence.anyStep.toString());
        matcher.setConfigs(values);
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationStep previousConversationStep1 = mock(IConversationStep.class);
        IConversationStep previousConversationStep2 = mock(IConversationStep.class);
        when(previousConversationStep1.getLatestData(eq(KEY_ACTIONS))).thenAnswer(invocation ->
                new Data<>(KEY_ACTIONS, Collections.singletonList("someNonMatchingAction")));
        when(previousConversationStep2.getLatestData(eq(KEY_ACTIONS))).thenAnswer(invocation ->
                new Data<>(KEY_ACTIONS, inputActions));
        when(memory.getAllSteps()).thenAnswer(invocation ->
                new ConversationMemory.ConversationStepStack(Arrays.asList(previousConversationStep1,
                        previousConversationStep2)));

        //test
        ExecutionState actualExecutionState = matcher.execute(memory, new LinkedList<>());

        //assert
        Assert.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
        verify(memory).getAllSteps();
        verify(previousConversationStep1).getLatestData(KEY_ACTIONS);
        verify(previousConversationStep2).getLatestData(KEY_ACTIONS);
    }

    @Test
    public void execute_occurrence_never() throws Exception {
        //setup
        Map<String, String> values = new HashMap<>();
        String expectedActionValue = "nonMatchingAction";
        values.put(KEY_ACTIONS, expectedActionValue);
        values.put(KEY_OCCURRENCE, ConversationStepOccurrence.never.toString());
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationStep previousConversationStep1 = mock(IConversationStep.class);
        IConversationStep previousConversationStep2 = mock(IConversationStep.class);
        matcher.setConfigs(values);

        when(previousConversationStep1.getLatestData(eq(KEY_ACTIONS))).thenAnswer(invocation ->
                new Data<>(KEY_ACTIONS, Collections.singletonList("someNonMatchingAction")));
        when(previousConversationStep2.getLatestData(eq(KEY_ACTIONS))).thenAnswer(invocation ->
                new Data<>(KEY_ACTIONS, inputActions));
        when(memory.getAllSteps()).thenAnswer(invocation ->
                new ConversationMemory.ConversationStepStack(Arrays.asList(previousConversationStep1,
                        previousConversationStep2)));

        //test
        ExecutionState actualExecutionState = matcher.execute(memory, new LinkedList<>());

        //assert
        Assert.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
        verify(memory).getAllSteps();
        verify(previousConversationStep1).getLatestData(KEY_ACTIONS);
        verify(previousConversationStep2).getLatestData(KEY_ACTIONS);
    }
}