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
import ai.labs.utilities.RuntimeUtilities;

import javax.inject.Inject;
import java.net.URI;
import java.util.*;

/**
 * @author ginccc
 */
public class SimpleOutputTask implements ILifecycleTask {
    private static final String SEPARATOR = " ";
    private static final String ACTION_KEY = "action";
    private SimpleOutput simpleOutput;
    private IResourceClientLibrary resourceClientLibrary;
    private final IDataFactory dataFactory;
    private final IExpressionProvider expressionProvider;

    @Inject
    public SimpleOutputTask(IResourceClientLibrary resourceClientLibrary,
                            IDataFactory dataFactory,
                            IExpressionProvider expressionProvider) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.dataFactory = dataFactory;
        this.expressionProvider = expressionProvider;
        this.simpleOutput = new SimpleOutput();

    }

    @Override
    public void init() {
        // not implemented
    }

    @Override
    public String getId() {
        return "ai.labs.output.SimpleOutput";
    }

    @Override
    public Object getComponent() {
        return simpleOutput;
    }

    @Override
    public List<String> getComponentDependencies() {
        return Collections.emptyList();
    }

    @Override
    public List<String> getOutputDependencies() {
        return Collections.emptyList();
    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        List<IOutputFilter> outputFilters = new LinkedList<>();
        IData<List<String>> latestData = memory.getCurrentStep().getLatestData(ACTION_KEY);
        if (latestData == null) {
            return;
        }
        List<String> actions = latestData.getResult();
        for (String action : actions) {
            int occurrence = countActionOccurrence(memory.getPreviousSteps(), action);
            outputFilters.add(new OutputFilter(action, occurrence));
        }

        List<List<OutputEntry>> possibleOutputs = simpleOutput.getOutputs(outputFilters);

        if (possibleOutputs.isEmpty()) {
            return;
        }

        for (List<OutputEntry> possibleOutput : possibleOutputs) {
            String outputTextKey = "output:action:" + possibleOutput.get(0).getKey();
            IData outputText = dataFactory.createData(outputTextKey, null,
                    simpleOutput.convertOutputText(possibleOutput));
            memory.getCurrentStep().storeData(outputText);

            String outputQuickReplyKey = "output:quickreply:" + possibleOutput.get(0).getKey();
            List<IQuickReply> quickReplies = convertQuickReplies(possibleOutput);
            if (!quickReplies.isEmpty()) {
                IData outputQuickReplies = dataFactory.createData(outputQuickReplyKey, quickReplies);
                outputQuickReplies.setPublic(true);
                memory.getCurrentStep().storeData(outputQuickReplies);
            }
        }

        List<IData<String>> allOutputParts = memory.getCurrentStep().getAllData("output:action");
        StringBuilder finalOutput = new StringBuilder();
        for (IData outputPart : allOutputParts) {
            finalOutput.append(outputPart.getResult()).append(SEPARATOR);
        }

        if (!allOutputParts.isEmpty()) {
            finalOutput.delete(finalOutput.length() - SEPARATOR.length(), finalOutput.length());
        }

        IData finalOutputData = dataFactory.createData("output:final", finalOutput.toString());
        finalOutputData.setPublic(true);
        memory.getCurrentStep().storeData(finalOutputData);
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

    @Override
    public void configure(Map<String, Object> configuration) throws PackageConfigurationException {
        Object uriObj = configuration.get("uri");
        URI uri = URI.create(uriObj.toString());

        try {
            OutputConfigurationSet outputConfigurationSet = resourceClientLibrary.getResource(uri, OutputConfigurationSet.class);

            for (OutputConfiguration outputConfiguration : outputConfigurationSet.getOutputs()) {
                String key = outputConfiguration.getKey();
                List<OutputConfiguration.QuickReply> configQuickReplies = outputConfiguration.getQuickReplies();
                List<OutputEntry.QuickReply> quickReplies = convert(configQuickReplies);

                int occurrence = outputConfiguration.getOccurrence();
                outputConfiguration.getOutputValues().stream().filter(
                        text -> !RuntimeUtilities.isNullOrEmpty(text)).forEachOrdered(
                        text -> simpleOutput.addOutputEntry(new OutputEntry(key, text, quickReplies, occurrence)));
            }
        } catch (ServiceException e) {
            String message = "Error while fetching OutputConfigurationSet!\n" + e.getLocalizedMessage();
            throw new PackageConfigurationException(message, e);
        }
    }

    private List<OutputEntry.QuickReply> convert(List<OutputConfiguration.QuickReply> configQuickReplies) {
        List<OutputEntry.QuickReply> quickReplies = new ArrayList<>();

        for (OutputConfiguration.QuickReply configQuickReply : configQuickReplies) {
            OutputEntry.QuickReply quickReply = new OutputEntry.QuickReply();
            quickReply.setValue(configQuickReply.getValue());
            quickReply.setExpressions(expressionProvider.parseExpressions(configQuickReply.getExpressions()));
            quickReplies.add(quickReply);
        }

        return quickReplies;
    }

    private List<IQuickReply> convertQuickReplies(List<OutputEntry> possibleOutput) {
        List<IQuickReply> ret = new LinkedList<>();
        for (OutputEntry outputEntry : possibleOutput) {
            for (OutputEntry.QuickReply quickReply : outputEntry.getQuickReplies()) {
                ret.add(new QuickReply(quickReply.getValue(), expressionProvider.toString(quickReply.getExpressions())));
            }
        }

        return ret;
    }

}
