package ai.labs.output.impl;

import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.lifecycle.PackageConfigurationException;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.IDataFactory;
import ai.labs.output.IOutputFilter;
import ai.labs.output.IQuickReply;
import ai.labs.output.model.OutputEntry;
import ai.labs.resources.rest.output.model.OutputConfiguration;
import ai.labs.resources.rest.output.model.OutputConfigurationSet;
import ai.labs.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.runtime.service.ServiceException;

import javax.inject.Inject;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
public class OutputGenerationTask implements ILifecycleTask {
    private static final String ACTION_KEY = "action";
    private OutputGeneration outputGeneration;
    private IResourceClientLibrary resourceClientLibrary;
    private final IDataFactory dataFactory;
    private final IExpressionProvider expressionProvider;

    @Inject
    public OutputGenerationTask(IResourceClientLibrary resourceClientLibrary,
                                IDataFactory dataFactory,
                                IExpressionProvider expressionProvider) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.dataFactory = dataFactory;
        this.expressionProvider = expressionProvider;
        this.outputGeneration = new OutputGeneration();

    }

    @Override
    public String getId() {
        return "ai.labs.output.OutputGeneration";
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
        List<IOutputFilter> outputFilters = actions.stream().map(action ->
                new OutputFilter(action, countActionOccurrence(memory.getPreviousSteps(), action))).
                collect(Collectors.toCollection(LinkedList::new));

        Map<String, List<OutputEntry>> outputs = outputGeneration.getOutputs(outputFilters);
        outputs.forEach((action, outputEntries) -> {
            outputEntries.forEach(outputEntry -> {
                outputEntry.getOutputs().forEach(output -> {
                    List<String> possibleValues = output.getValueAlternatives();
                    String randomValue = randomValue(possibleValues);
                    String outputKey = "output:".concat(output.getType().toString()).concat(":").concat(action);
                    IData<String> outputData = dataFactory.createData(outputKey, randomValue, possibleValues);
                    outputData.setPublic(true);
                    memory.getCurrentStep().storeData(outputData);
                });

                List<IQuickReply> quickReplies = convertQuickReplies(outputEntry.getQuickReplies());
                if (!quickReplies.isEmpty()) {
                    String outputQuickReplyKey = "quickReply:".concat(outputEntry.getAction());
                    IData outputQuickReplies = dataFactory.createData(outputQuickReplyKey, quickReplies);
                    outputQuickReplies.setPublic(true);
                    memory.getCurrentStep().storeData(outputQuickReplies);
                }

            });
            String outputTextKey = "output:" + action;
            dataFactory.createData(outputTextKey, outputEntries);
        });
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

    private String randomValue(List<String> possibleValues) {
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

    private List<OutputEntry.OutputValue> convertOutputTypesConfig(List<OutputConfiguration.OutputType> configOutputs) {
        return configOutputs.stream().map(configOutput -> {
            OutputEntry.OutputValue outputValue = new OutputEntry.OutputValue();
            outputValue.setType(OutputEntry.OutputValue.Type.valueOf(configOutput.getType()));
            outputValue.setValueAlternatives(configOutput.getValueAlternatives());
            return outputValue;
        }).collect(Collectors.toCollection(LinkedList::new));
    }

    private List<OutputEntry.QuickReply> convertQuickRepliesConfig(List<OutputConfiguration.QuickReply> configQuickReplies) {
        return configQuickReplies.stream().map(configQuickReply -> {
            OutputEntry.QuickReply quickReply = new OutputEntry.QuickReply();
            quickReply.setValue(configQuickReply.getValue());
            quickReply.setExpressions(expressionProvider.parseExpressions(configQuickReply.getExpressions()));
            return quickReply;
        }).collect(Collectors.toCollection(LinkedList::new));
    }

    private List<IQuickReply> convertQuickReplies(List<OutputEntry.QuickReply> outputQuickReplies) {
        return outputQuickReplies.stream().map(quickReply ->
                new QuickReply(quickReply.getValue(),
                        expressionProvider.toString(quickReply.getExpressions()))
        ).collect(Collectors.toList());
    }
}
