package ai.labs.behavior.impl.extensions;

import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IMemoryItemConverter;
import ai.labs.memory.model.ConversationOutput;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

import static ai.labs.behavior.impl.extensions.IBehaviorExtension.ExecutionState;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author ginccc
 */
public class DynamicValueMatcherTest {
    private IConversationMemory conversationMemory;
    private IConversationMemory.IWritableConversationStep currentStep;
    private IMemoryItemConverter memoryItemConverter;

    @Before
    public void setUp() {
        memoryItemConverter = mock(IMemoryItemConverter.class);
        when(memoryItemConverter.convert(any())).thenAnswer(invocation -> new LinkedHashMap<>(createConversationOutput()));
        conversationMemory = mock(IConversationMemory.class);
        currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(currentStep.getConversationOutput()).then(invocation -> createConversationOutput());
        when(conversationMemory.getCurrentStep()).then(invocation -> currentStep);
    }

    @Test
    public void dynamicValueNothingDefined() {
        //setup
        DynamicValueMatcher dynamicValueMatcher = new DynamicValueMatcher(memoryItemConverter);

        //test
        dynamicValueMatcher.execute(conversationMemory, new LinkedList<>());

        //assert
        Assert.assertEquals(ExecutionState.FAIL, dynamicValueMatcher.getExecutionState());
    }

    @Test
    public void dynamicValueNothingEquals() {
        //setup
        DynamicValueMatcher dynamicValueMatcher = new DynamicValueMatcher(memoryItemConverter);
        dynamicValueMatcher.setValues(createValues(true, false));

        //test
        dynamicValueMatcher.execute(conversationMemory, new LinkedList<>());

        //assert
        Assert.assertEquals(ExecutionState.SUCCESS, dynamicValueMatcher.getExecutionState());
    }

    @Test
    public void dynamicValueNothingContains() {
        //setup
        DynamicValueMatcher dynamicValueMatcher = new DynamicValueMatcher(memoryItemConverter);
        dynamicValueMatcher.setValues(createValues(false, true));

        //test
        dynamicValueMatcher.execute(conversationMemory, new LinkedList<>());

        //assert
        Assert.assertEquals(ExecutionState.SUCCESS, dynamicValueMatcher.getExecutionState());
    }

    private Map<String, String> createValues(boolean isEquals, boolean isContains) {
        Map<String, String> ret = new HashMap<>();
        ret.put("valuePath", "properties.username");
        if (isEquals) {
            ret.put("equals", "John");
        }
        if (isContains) {
            ret.put("contains", "Jo");
        }
        return ret;
    }

    private ConversationOutput createConversationOutput() {
        ConversationOutput conversationOutput = new ConversationOutput();
        var usernameMap = new HashMap();
        usernameMap.put("username", "John");
        conversationOutput.put("properties", usernameMap);
        return conversationOutput;
    }
}
