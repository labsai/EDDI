package ai.labs.behavior.impl;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.lifecycle.PackageConfigurationException;
import ai.labs.memory.Data;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.resources.rest.behavior.model.BehaviorConfiguration;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor.ConfigValue;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor.FieldType;
import ai.labs.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.runtime.service.ServiceException;
import ai.labs.serialization.DeserializationException;
import ai.labs.serialization.IJsonSerialization;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
@Slf4j
public class BehaviorRulesEvaluationTask implements ILifecycleTask {
    public static final String ID = "ai.labs.behavior";
    private BehaviorRulesEvaluator evaluator;
    private static final String BEHAVIOR_CONFIG_URI = "uri";
    private final IResourceClientLibrary resourceClientLibrary;
    private final IJsonSerialization jsonSerialization;
    private final IBehaviorDeserialization behaviorSerialization;

    @Inject
    public BehaviorRulesEvaluationTask(IResourceClientLibrary resourceClientLibrary,
                                       IJsonSerialization jsonSerialization,
                                       IBehaviorDeserialization behaviorSerialization) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.jsonSerialization = jsonSerialization;
        this.behaviorSerialization = behaviorSerialization;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Object getComponent() {
        return evaluator;
    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        BehaviorSetResult results;
        try {
            results = evaluator.evaluate(memory);
            storeResultIfNotEmpty(memory, "behavior_rules:success", results.getSuccessRules());
            storeResultIfNotEmpty(memory, "behavior_rules:droppedSuccess", results.getDroppedSuccessRules());
            storeResultIfNotEmpty(memory, "behavior_rules:fail", results.getFailRules());

            if (!results.getSuccessRules().isEmpty()) {
                addActionsToConversationMemory(memory, results.getSuccessRules());
            }
        } catch (BehaviorRulesEvaluator.BehaviorRuleExecutionException e) {
            String msg = "Error while evaluating behavior rules!";
            log.error(msg, e);
            throw new LifecycleException(msg, e);
        } catch (InterruptedException e) {
            log.warn(e.getLocalizedMessage(), e);
        }
    }

    private void addActionsToConversationMemory(IConversationMemory memory, List<BehaviorRule> successRules) {
        List<String> allCurrentActions = new LinkedList<>();
        successRules.forEach(successRule -> successRule.getActions().stream().
                filter(action -> !allCurrentActions.contains(action)).forEach(allCurrentActions::add));

        IData<List<String>> latestActions = memory.getCurrentStep().getLatestData("actions");
        List<String> actions = new LinkedList<>();
        if (latestActions != null && latestActions.getResult() != null) {
            actions.addAll(latestActions.getResult());
        }

        actions.addAll(allCurrentActions.stream().
                filter(action -> !actions.contains(action)).collect(Collectors.toList()));

        Data actionsData = new Data<>("actions", actions);
        actionsData.setPublic(true);
        memory.getCurrentStep().storeData(actionsData);
    }

    private void storeResultIfNotEmpty(IConversationMemory memory, String key, List<BehaviorRule> result) {
        if (!result.isEmpty()) {
            memory.getCurrentStep().storeData(new Data<>(key, convert(result)), false);
        }
    }

    private List<String> convert(List<BehaviorRule> behaviorRules) {
        return behaviorRules.stream().map(BehaviorRule::getName).collect(Collectors.toCollection(LinkedList::new));
    }

    @Override
    public void configure(Map<String, Object> configuration) throws PackageConfigurationException {
        Object uriObj = configuration.get(BEHAVIOR_CONFIG_URI);
        URI uri = URI.create(uriObj.toString());


        try {
            BehaviorConfiguration behaviorConfiguration = resourceClientLibrary.getResource(uri, BehaviorConfiguration.class);
            String behaviorConfigJson = jsonSerialization.serialize(behaviorConfiguration);
            BehaviorSet behaviorSet = behaviorSerialization.deserialize(behaviorConfigJson);

            evaluator = new BehaviorRulesEvaluator(behaviorSet);

        } catch (IOException | DeserializationException e) {
            String message = "Error while configuring BehaviorRuleLifecycleTask!";
            log.debug(message, e);
            throw new PackageConfigurationException(message, e);
        } catch (ServiceException e) {
            String message = "Error while fetching BehaviorRuleConfigurationSet!\n" + e.getLocalizedMessage();
            log.debug(message, e);
            throw new PackageConfigurationException(message, e);
        }
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(ID);
        extensionDescriptor.setDisplayName("Behavior Rules");

        ConfigValue configValue = new ConfigValue("Resource URI", FieldType.URI, false, null);
        extensionDescriptor.getConfigs().put(BEHAVIOR_CONFIG_URI, configValue);
        return extensionDescriptor;
    }
}
