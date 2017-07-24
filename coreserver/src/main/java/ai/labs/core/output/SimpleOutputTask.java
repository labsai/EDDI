package ai.labs.core.output;

import ai.labs.lifecycle.AbstractLifecycleTask;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.lifecycle.PackageConfigurationException;
import ai.labs.memory.Data;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.resources.rest.output.model.OutputConfiguration;
import ai.labs.resources.rest.output.model.OutputConfigurationSet;
import ai.labs.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.runtime.service.ServiceException;
import ai.labs.utilities.RuntimeUtilities;

import javax.inject.Inject;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class SimpleOutputTask extends AbstractLifecycleTask implements ILifecycleTask {
    private static final String SEPARATOR = " ";
    private static final String ACTION_KEY = "action";
    private SimpleOutput simpleOutput;
    private IResourceClientLibrary resourceClientLibrary;

    @Inject
    public SimpleOutputTask(IResourceClientLibrary resourceClientLibrary) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.simpleOutput = new SimpleOutput();

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
        IData latestData = memory.getCurrentStep().getLatestData(ACTION_KEY);
        if (latestData == null) {
            return;
        }
        List<String> actions = (List<String>) latestData.getResult();
        for (String action : actions) {
            int occurrence = countActionOccurrence(memory.getPreviousSteps(), action);
            outputFilters.add(new OutputFilter(action, occurrence));
        }

        List<List<OutputEntry>> possibleOutputs = simpleOutput.getOutputs(outputFilters);

        if (possibleOutputs.isEmpty()) {
            return;
        }

        for (List<OutputEntry> possibleOutput : possibleOutputs) {
            String key = "output:action:" + possibleOutput.get(0).getKey();
            memory.getCurrentStep().storeData(new Data(key, null, simpleOutput.convert(possibleOutput)));
        }

        List<IData> allOutputParts = memory.getCurrentStep().getAllData("output:action");
        StringBuilder finalOutput = new StringBuilder();
        for (IData outputPart : allOutputParts) {
            finalOutput.append(outputPart.getResult()).append(SEPARATOR);
        }

        if (!allOutputParts.isEmpty()) {
            finalOutput.delete(finalOutput.length() - SEPARATOR.length(), finalOutput.length());
        }

        Data finalOutputData = new Data("output:final", finalOutput.toString());
        finalOutputData.setPublic(true);
        memory.getCurrentStep().storeData(finalOutputData);
    }

    private int countActionOccurrence(IConversationMemory.IConversationStepStack conversationStepStack,
                                      String action) {
        int count = 0;
        for (int i = 0; i < conversationStepStack.size(); i++) {
            IConversationMemory.IConversationStep conversationStep = conversationStepStack.get(i);
            IData actionsData = conversationStep.getLatestData(ACTION_KEY);
            if (actionsData != null) {
                List<String> actions = (List<String>) actionsData.getResult();
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
                int occurrence = outputConfiguration.getOccurrence();
                outputConfiguration.getOutputValues().stream().filter(
                        text -> !RuntimeUtilities.isNullOrEmpty(text)).forEachOrdered(
                        text -> simpleOutput.addOutputEntry(new OutputEntry(key, text, occurrence)));
            }
        } catch (ServiceException e) {
            String message = "Error while fetching OutputConfigurationSet!\n" + e.getLocalizedMessage();
            throw new PackageConfigurationException(message, e);
        }
    }
}
