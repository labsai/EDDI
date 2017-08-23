package ai.labs.output.impl;

import ai.labs.memory.Data;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.IDataFactory;
import ai.labs.output.IOutputFilter;
import ai.labs.output.IOutputGeneration;
import ai.labs.output.model.OutputEntry;
import ai.labs.output.model.OutputValue;
import ai.labs.output.model.QuickReply;
import ai.labs.resources.rest.output.model.OutputConfiguration;
import ai.labs.resources.rest.output.model.OutputConfigurationSet;
import ai.labs.runtime.client.configuration.IResourceClientLibrary;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.util.*;

import static org.mockito.Matchers.anyListOf;
import static org.mockito.Mockito.*;

/**
 * @author ginccc
 */
public class OutputGenerationTaskTest {
    private static final String ACTION_1 = "action1";
    private static final String ACTION_2 = "action2";
    private static final String ACTION = "action";
    private static final String SOME_ACTION_1 = "someAction1";
    private static final String SOME_ACTION_2 = "someAction2";
    private static final String SOME_OTHER_ACTION_1 = "someOtherAction1";
    private static final String SOME_OTHER_ACTION_2 = "someOtherAction2";
    private static final String ANSWER_ALTERNATIVE_1 = "Answer Alternative 1";
    private static final String ANSWER_ALTERNATIVE_2 = "Answer Alternative 2";
    private static final String SOME_QUICK_REPLY = "Some Quick Reply";
    private static final String SOME_OTHER_QUICK_REPLY = "Some Other Quick Reply";
    private static final String SOME_EXPRESSION = "some(Expression)";
    private static final String SOME_OTHER_EXPRESSION = "someOther(Expression)";
    private static final String OUTPUT_TEXT = "output:text:";
    private static final String QUICK_REPLIES = "quickReplies:";
    private OutputGenerationTask outputGenerationTask;
    private IResourceClientLibrary resourceClientLibrary;
    private IOutputGeneration outputGeneration;
    private IDataFactory dataFactory;

    @Before
    public void setUp() throws Exception {
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        dataFactory = mock(IDataFactory.class);
        outputGeneration = mock(IOutputGeneration.class);
        outputGenerationTask = new OutputGenerationTask(resourceClientLibrary, dataFactory, outputGeneration);
    }

    @Test
    public void executeTask() throws Exception {
        //setup
        when(outputGeneration.getOutputs(anyListOf(IOutputFilter.class))).thenAnswer(invocation -> {
            Map<String, List<OutputEntry>> ret = new LinkedHashMap<>();
            OutputEntry outputEntry = createOutputEntry();
            ret.put(ACTION_1, Collections.singletonList(outputEntry));
            return ret;
        });
        IConversationMemory conversationMemory = mock(IConversationMemory.class);
        IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(conversationMemory.getCurrentStep()).thenAnswer(invocation -> currentStep);
        when(currentStep.getLatestData(eq(ACTION))).thenAnswer(invocation ->
                new Data<>(ACTION_1, Arrays.asList(SOME_ACTION_1, SOME_OTHER_ACTION_1)));
        IConversationMemory.IConversationStepStack conversationStepStack = mock(IConversationMemory.IConversationStepStack.class);
        when(conversationMemory.getPreviousSteps()).then(invocation -> conversationStepStack);
        IConversationMemory.IConversationStep conversationStep = mock(IConversationMemory.IConversationStep.class);
        when(conversationStepStack.get(anyInt())).then(invocation -> conversationStep);
        when(conversationStep.getLatestData(eq(ACTION))).then(invocation ->
                new Data<>(ACTION_2, Arrays.asList(SOME_ACTION_2, SOME_OTHER_ACTION_2)));
        IData<String> expectedOutputData = new Data<>(OUTPUT_TEXT + ACTION_1, ANSWER_ALTERNATIVE_1,
                Arrays.asList(SOME_ACTION_2, SOME_OTHER_ACTION_2));
        when(dataFactory.createData(eq(OUTPUT_TEXT + ACTION_1), anyString(),
                eq(Arrays.asList(ANSWER_ALTERNATIVE_1, ANSWER_ALTERNATIVE_2)))).thenAnswer(invocation -> expectedOutputData);
        List<QuickReply> quickReplies = Arrays.asList(new QuickReply(SOME_QUICK_REPLY, SOME_EXPRESSION),
                new QuickReply(SOME_OTHER_QUICK_REPLY, SOME_OTHER_EXPRESSION));
        IData<List<QuickReply>> expectedQuickReplyData = new Data<>(QUICK_REPLIES + ACTION_1, quickReplies);
        when(dataFactory.createData(eq(QUICK_REPLIES + ACTION_1), anyListOf(QuickReply.class))).
                thenAnswer(invocation -> expectedQuickReplyData);

        //test
        outputGenerationTask.executeTask(conversationMemory);

        //assert
        verify(conversationMemory, times(3)).getCurrentStep();
        verify(currentStep).storeData(expectedOutputData);
        verify(currentStep).storeData(expectedQuickReplyData);
    }

    @Test
    public void configure() throws Exception {
        //setup
        final HashMap<String, Object> configuration = new HashMap<>();
        final String uri = "eddi://ai.labs.output/outputstore/outputsets/00000000000000000?version=1";
        configuration.put("uri", uri);
        when(resourceClientLibrary.getResource(URI.create(uri), OutputConfigurationSet.class)).
                thenAnswer(invocation -> createOutputConfigurationSet());

        //test
        outputGenerationTask.configure(configuration);

        //assert
        verify(outputGeneration, times(3)).addOutputEntry(any(OutputEntry.class));
    }

    private OutputEntry createOutputEntry() {
        List<OutputValue> outputs = new LinkedList<>();
        outputs.add(new OutputValue(OutputValue.Type.text, Arrays.asList(ANSWER_ALTERNATIVE_1, ANSWER_ALTERNATIVE_2)));
        List<QuickReply> quickReplies = new LinkedList<>();
        quickReplies.add(new QuickReply(SOME_QUICK_REPLY, SOME_EXPRESSION));
        quickReplies.add(new QuickReply(SOME_OTHER_QUICK_REPLY, SOME_OTHER_EXPRESSION));
        return new OutputEntry(ACTION_1, 0, outputs, quickReplies);
    }

    private OutputConfigurationSet createOutputConfigurationSet() {
        OutputConfigurationSet configurationSet = new OutputConfigurationSet();
        configurationSet.getOutputSet().add(createOutputConfiguration());
        configurationSet.getOutputSet().add(createOutputConfiguration());
        configurationSet.getOutputSet().add(createOutputConfiguration());
        return configurationSet;
    }

    private OutputConfiguration createOutputConfiguration() {
        OutputConfiguration outputConfiguration = new OutputConfiguration();
        outputConfiguration.setAction(ACTION_1);
        outputConfiguration.setTimesOccurred(0);
        LinkedList<OutputConfiguration.OutputType> outputs = new LinkedList<>();
        OutputConfiguration.OutputType outputType = new OutputConfiguration.OutputType();
        outputType.setType(OutputValue.Type.text.toString());
        LinkedList<String> valueAlternatives = new LinkedList<>();
        valueAlternatives.add(ANSWER_ALTERNATIVE_1);
        valueAlternatives.add(ANSWER_ALTERNATIVE_2);
        outputType.setValueAlternatives(valueAlternatives);
        outputs.add(outputType);
        outputConfiguration.setOutputs(outputs);
        LinkedList<OutputConfiguration.QuickReply> quickReplies = new LinkedList<>();
        quickReplies.add(createConfigQuickReply(SOME_QUICK_REPLY, SOME_EXPRESSION));
        quickReplies.add(createConfigQuickReply(SOME_OTHER_QUICK_REPLY, SOME_OTHER_EXPRESSION));
        outputConfiguration.setQuickReplies(quickReplies);
        return outputConfiguration;
    }

    private OutputConfiguration.QuickReply createConfigQuickReply(String value, String expression) {
        OutputConfiguration.QuickReply quickReply = new OutputConfiguration.QuickReply();
        quickReply.setValue(value);
        quickReply.setExpressions(expression);
        return quickReply;
    }
}