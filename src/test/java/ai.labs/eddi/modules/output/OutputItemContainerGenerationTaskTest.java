package ai.labs.eddi.modules.output;

import ai.labs.eddi.configs.output.model.OutputConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.output.impl.OutputGeneration;
import ai.labs.eddi.modules.output.impl.OutputGenerationTask;
import ai.labs.eddi.modules.output.model.OutputEntry;
import ai.labs.eddi.modules.output.model.OutputItem;
import ai.labs.eddi.modules.output.model.OutputValue;
import ai.labs.eddi.modules.output.model.QuickReply;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.*;

import static java.util.Arrays.asList;
import static org.mockito.Mockito.*;

/**
 * @author ginccc
 */

public class OutputItemContainerGenerationTaskTest {
    private static final String ACTION_1 = "action1";
    private static final String ACTION_2 = "action2";
    private static final String ACTION = "actions";
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
    public static final String OUTPUT_TYPE_TEXT = "text";
    private static OutputGenerationTask outputGenerationTask;
    private static IResourceClientLibrary resourceClientLibrary;
    private static IOutputGeneration outputGeneration;
    private static IDataFactory dataFactory;

    @BeforeEach
    public void setUp() {
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        dataFactory = mock(IDataFactory.class);
        outputGeneration = mock(IOutputGeneration.class);
        ObjectMapper objectMapper = new ObjectMapper();
        outputGenerationTask = new OutputGenerationTask(resourceClientLibrary, dataFactory, objectMapper);
    }

    @Test
    public void executeTask() {
        //setup
        when(outputGeneration.getOutputs(anyList())).thenAnswer(invocation -> {
            Map<String, List<OutputEntry>> ret = new LinkedHashMap<>();
            OutputEntry outputEntry = createOutputEntry();
            ret.put(ACTION_1, Collections.singletonList(outputEntry));
            return ret;
        });
        var conversationMemory = mock(IConversationMemory.class);
        var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(conversationMemory.getCurrentStep()).thenAnswer(invocation -> currentStep);
        when(currentStep.getLatestData(eq(ACTION))).thenAnswer(invocation ->
                new Data<>(ACTION_1, asList(SOME_ACTION_1, SOME_OTHER_ACTION_1)));
        var conversationStepStack = mock(IConversationMemory.IConversationStepStack.class);
        when(conversationMemory.getPreviousSteps()).then(invocation -> conversationStepStack);
        var conversationStep = mock(IConversationMemory.IConversationStep.class);
        when(conversationStepStack.get(anyInt())).then(invocation -> conversationStep);
        when(conversationStep.getLatestData(eq(ACTION))).then(invocation ->
                new Data<>(ACTION_2, asList(SOME_ACTION_2, SOME_OTHER_ACTION_2)));

        var expectedOutputData = new Data<>(OUTPUT_TEXT + ACTION_1, new TextOutputItem(ANSWER_ALTERNATIVE_1),
                asList(new TextOutputItem(ANSWER_ALTERNATIVE_1), new TextOutputItem(ANSWER_ALTERNATIVE_2)));

        when(dataFactory.createData(eq(OUTPUT_TEXT + ACTION_1), any(TextOutputItem.class),
                eq(asList(new TextOutputItem(ANSWER_ALTERNATIVE_1), new TextOutputItem(ANSWER_ALTERNATIVE_2)))))
                .thenAnswer(invocation -> expectedOutputData);

        List<QuickReply> quickReplies = asList(new QuickReply(SOME_QUICK_REPLY, SOME_EXPRESSION, false),
                new QuickReply(SOME_OTHER_QUICK_REPLY, SOME_OTHER_EXPRESSION, false));
        IData<List<QuickReply>> expectedQuickReplyData = new Data<>(QUICK_REPLIES + ACTION_1, quickReplies);
        when(dataFactory.createData(eq(QUICK_REPLIES + ACTION_1), anyList())).
                thenAnswer(invocation -> expectedQuickReplyData);
        when(conversationMemory.getConversationProperties()).
                thenAnswer(invocation -> new ConversationProperties(conversationMemory));

        //test
        outputGenerationTask.execute(conversationMemory, outputGeneration);

        //assert
        verify(conversationMemory, times(1)).getCurrentStep();
        verify(conversationMemory, times(1)).getConversationProperties();
        verify(currentStep, times(2)).storeData(any());
        verify(currentStep, times(2)).addConversationOutputList(anyString(), anyList());
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
        var outputGeneration = (OutputGeneration) outputGenerationTask.configure(configuration, null);

        //assert
        var outputEntry = outputGeneration.getOutputMapper().get(ACTION_1).get(0);
        Assertions.assertEquals(1, outputGeneration.getOutputMapper().keySet().size());
        Assertions.assertEquals(2, outputEntry.getOutputs().get(0).getValueAlternatives().size());
        Assertions.assertEquals(2, outputEntry.getQuickReplies().size());
    }

    private OutputEntry createOutputEntry() {
        List<OutputValue> outputs = new LinkedList<>();
        outputs.add(
                new OutputValue(
                        asList(
                                new TextOutputItem(ANSWER_ALTERNATIVE_1),
                                new TextOutputItem(ANSWER_ALTERNATIVE_2))));
        List<QuickReply> quickReplies = new LinkedList<>();
        quickReplies.add(new QuickReply(SOME_QUICK_REPLY, SOME_EXPRESSION, false));
        quickReplies.add(new QuickReply(SOME_OTHER_QUICK_REPLY, SOME_OTHER_EXPRESSION, false));
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
        LinkedList<OutputConfiguration.Output> outputs = new LinkedList<>();
        OutputConfiguration.Output output = new OutputConfiguration.Output();
        LinkedList<OutputItem> valueAlternatives = new LinkedList<>();
        valueAlternatives.add(new TextOutputItem(ANSWER_ALTERNATIVE_1));
        valueAlternatives.add(new TextOutputItem(ANSWER_ALTERNATIVE_2));
        output.setValueAlternatives(valueAlternatives);
        outputs.add(output);
        outputConfiguration.setOutputs(outputs);
        LinkedList<QuickReply> quickReplies = new LinkedList<>();
        quickReplies.add(createConfigQuickReply(SOME_QUICK_REPLY, SOME_EXPRESSION));
        quickReplies.add(createConfigQuickReply(SOME_OTHER_QUICK_REPLY, SOME_OTHER_EXPRESSION));
        outputConfiguration.setQuickReplies(quickReplies);
        return outputConfiguration;
    }

    private QuickReply createConfigQuickReply(String value, String expression) {
        QuickReply quickReply = new QuickReply();
        quickReply.setValue(value);
        quickReply.setExpressions(expression);
        return quickReply;
    }
}
