package ai.labs.output.impl;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.lifecycle.PackageConfigurationException;
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
import ai.labs.runtime.service.ServiceException;
import ai.labs.utilities.StringUtilities;

import javax.inject.Inject;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author ginccc
 */
public class OutputGenerationTask implements ILifecycleTask {
    private static final String ACTION_KEY = "action";
    private final IResourceClientLibrary resourceClientLibrary;
    private final IDataFactory dataFactory;
    private final IOutputGeneration outputGeneration;

    @Inject
    public OutputGenerationTask(IResourceClientLibrary resourceClientLibrary,
                                IDataFactory dataFactory,
                                IOutputGeneration outputGeneration) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.dataFactory = dataFactory;
        this.outputGeneration = outputGeneration;

    }

    @Override
    public String getId() {
        return "ai.labs.output";
    }

    @Override
    public Object getComponent() {
        return outputGeneration;
    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        IData<List<String>> latestData = memory.getCurrentStep().getLatestData(ACTION_KEY);
        if (latestData == null) {
            return;
        }
        List<String> actions = latestData.getResult();
        List<IOutputFilter> outputFilters = createOutputFilters(memory, actions);

        Map<String, List<OutputEntry>> outputs = outputGeneration.getOutputs(outputFilters);
        outputs.forEach((action, outputEntries) ->
                outputEntries.forEach(outputEntry -> {
                    List<OutputValue> outputValues = outputEntry.getOutputs();
                    selectAndStoreOutput(memory, action, outputValues);
                    storeQuickReplies(memory, outputEntry);
                }));
    }

    private void selectAndStoreOutput(IConversationMemory memory, String action, List<OutputValue> outputValues) {
        IntStream.range(0, outputValues.size()).forEach(index -> {
            OutputValue outputValue = outputValues.get(index);
            List<String> possibleValueAlternatives = outputValue.getValueAlternatives();
            String randomValue = chooseRandomly(possibleValueAlternatives);
            String outputKey = createOutputKey(action, outputValues, outputValue, index);
            IData<String> outputData = dataFactory.createData(outputKey, randomValue, possibleValueAlternatives);
            outputData.setPublic(true);
            memory.getCurrentStep().storeData(outputData);
        });
    }

    private void storeQuickReplies(IConversationMemory memory, OutputEntry outputEntry) {
        List<QuickReply> quickReplies = convertQuickReplies(outputEntry.getQuickReplies());
        if (!quickReplies.isEmpty()) {
            String outputQuickReplyKey = StringUtilities.
                    joinStrings(":", "quickReply", outputEntry.getAction());
            IData outputQuickReplies = dataFactory.createData(outputQuickReplyKey, quickReplies);
            outputQuickReplies.setPublic(true);
            memory.getCurrentStep().storeData(outputQuickReplies);
        }
    }

    private LinkedList<IOutputFilter> createOutputFilters(IConversationMemory memory, List<String> actions) {
        return actions.stream().map(action ->
                new OutputFilter(action, countActionOccurrence(memory.getPreviousSteps(), action))).
                collect(Collectors.toCollection(LinkedList::new));
    }

    @Override
    public void configure(Map<String, Object> configuration) throws PackageConfigurationException {
        Object uriObj = configuration.get("uri");
        URI uri = URI.create(uriObj.toString());

        try {
            OutputConfigurationSet outputConfigurationSet = resourceClientLibrary.getResource(uri, OutputConfigurationSet.class);

            outputConfigurationSet.getOutputSet().forEach(outputConfig -> outputGeneration.addOutputEntry(
                    new OutputEntry(outputConfig.getAction(),
                            outputConfig.getOccurred(),
                            convertOutputTypesConfig(outputConfig.getOutputs()),
                            convertQuickRepliesConfig(outputConfig.getQuickReplies()))));
        } catch (ServiceException e) {
            String message = "Error while fetching OutputConfigurationSet!\n" + e.getLocalizedMessage();
            throw new PackageConfigurationException(message, e);
        }
    }

    private String createOutputKey(String action, List<OutputValue> outputValues, OutputValue outputValue, int idx) {
        if (outputValues.size() > 1) {
            return StringUtilities.joinStrings(":", "output", outputValue.getType(), action, idx);
        } else {
            return StringUtilities.joinStrings(":", "output", outputValue.getType(), action);
        }
    }

    private String chooseRandomly(List<String> possibleValues) {
        return possibleValues.get(new Random().nextInt(possibleValues.size()));
    }

    private int countActionOccurrence(IConversationMemory.IConversationStepStack conversationStepStack,
                                      String action) {
        int count = 0;
        for (int i = 0; i < conversationStepStack.size(); i++) {
            IConversationMemory.IConversationStep conversationStep = conversationStepStack.get(i);
            IData<List<String>> actionsData = conversationStep.getLatestData(ACTION_KEY);
            if (actionsData != null) {
                List<String> actions = actionsData.getResult();
                if (actions.contains(action)) {
                    count++;
                }
            }
        }

        return count;
    }

    private List<OutputValue> convertOutputTypesConfig(List<OutputConfiguration.OutputType> configOutputs) {
        return configOutputs.stream().map(configOutput -> {
            OutputValue outputValue = new OutputValue();
            outputValue.setType(OutputValue.Type.valueOf(configOutput.getType()));
            outputValue.setValueAlternatives(configOutput.getValueAlternatives());
            return outputValue;
        }).collect(Collectors.toCollection(LinkedList::new));
    }

    private List<QuickReply> convertQuickRepliesConfig(List<OutputConfiguration.QuickReply> configQuickReplies) {
        return configQuickReplies.stream().map(configQuickReply -> {
            QuickReply quickReply = new QuickReply();
            quickReply.setValue(configQuickReply.getValue());
            quickReply.setExpressions(configQuickReply.getExpressions());
            return quickReply;
        }).collect(Collectors.toCollection(LinkedList::new));
    }

    private List<QuickReply> convertQuickReplies(List<QuickReply> outputQuickReplies) {
        return outputQuickReplies.stream().map(quickReply ->
                new QuickReply(quickReply.getValue(), quickReply.getExpressions())
        ).collect(Collectors.toList());
    }
}
