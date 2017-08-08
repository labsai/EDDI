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
            ret.put("action1", Collections.singletonList(outputEntry));
            return ret;
        });
        IConversationMemory conversationMemory = mock(IConversationMemory.class);
        IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(conversationMemory.getCurrentStep()).thenAnswer(invocation -> currentStep);
        when(currentStep.getLatestData(eq("action"))).thenAnswer(invocation ->
                new Data<>("action1", Arrays.asList("someAction1", "someOtherAction1")));
        IConversationMemory.IConversationStepStack conversationStepStack = mock(IConversationMemory.IConversationStepStack.class);
        when(conversationMemory.getPreviousSteps()).then(invocation -> conversationStepStack);
        IConversationMemory.IConversationStep conversationStep = mock(IConversationMemory.IConversationStep.class);
        when(conversationStepStack.get(anyInt())).then(invocation -> conversationStep);
        when(conversationStep.getLatestData(eq("action"))).then(invocation ->
                new Data<>("action2", Arrays.asList("someAction2", "someOtherAction2")));
        IData<String> expectedOutputData = new Data<>("output:text:action1", "Answer Alternative 1",
                Arrays.asList("someAction2", "someOtherAction2"));
        when(dataFactory.createData(eq("output:text:action1"), anyString(),
                eq(Arrays.asList("Answer Alternative 1", "Answer Alternative 2")))).thenAnswer(invocation -> expectedOutputData);
        List<QuickReply> quickReplies = Arrays.asList(new QuickReply("Some Quick Reply", "some(Expression)"),
                new QuickReply("Some Other Quick Reply", "someOther(Expression)"));
        IData<List<QuickReply>> expectedQuickReplyData = new Data<>("quickReply:action1", quickReplies);
        when(dataFactory.createData(eq("quickReply:action1"), anyListOf(QuickReply.class))).
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
        outputs.add(new OutputValue(OutputValue.Type.text, Arrays.asList("Answer Alternative 1", "Answer Alternative 2")));
        List<QuickReply> quickReplies = new LinkedList<>();
        quickReplies.add(new QuickReply("Some Quick Reply", "some(Expression)"));
        quickReplies.add(new QuickReply("Some Other Quick Reply", "someOther(Expression)"));
        return new OutputEntry("action1", 0, outputs, quickReplies);
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
        outputConfiguration.setAction("action1");
        outputConfiguration.setOccurred(0);
        LinkedList<OutputConfiguration.OutputType> outputs = new LinkedList<>();
        OutputConfiguration.OutputType outputType = new OutputConfiguration.OutputType();
        outputType.setType(OutputValue.Type.text.toString());
        LinkedList<String> valueAlternatives = new LinkedList<>();
        valueAlternatives.add("Answer Alternative 1");
        valueAlternatives.add("Answer Alternative 2");
        outputType.setValueAlternatives(valueAlternatives);
        outputs.add(outputType);
        outputConfiguration.setOutputs(outputs);
        LinkedList<OutputConfiguration.QuickReply> quickReplies = new LinkedList<>();
        quickReplies.add(createConfigQuickReply("Some Quick Reply", "some(Expression)"));
        quickReplies.add(createConfigQuickReply("Some Other Quick Reply", "someOther(Expression)"));
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