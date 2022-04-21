package ai.labs.eddi.modules.behavior.impl.conditions;

import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStep;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.modules.behavior.impl.conditions.BaseMatcher.ConversationStepOccurrence;
import ai.labs.eddi.modules.behavior.impl.conditions.IBehaviorCondition.ExecutionState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

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

    @Test
    public void setValues_actions() {
        //setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_ACTIONS, actionsValue);

        //test
        matcher.setConfigs(values);

        //assert
        Assertions.assertEquals(expectedActions, ((ActionMatcher) matcher).getActions());
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
        Assertions.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
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
        Assertions.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
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
        Assertions.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
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
        Assertions.assertEquals(ExecutionState.SUCCESS, actualExecutionState);
        verify(memory).getAllSteps();
        verify(previousConversationStep1).getLatestData(KEY_ACTIONS);
        verify(previousConversationStep2).getLatestData(KEY_ACTIONS);
    }
}