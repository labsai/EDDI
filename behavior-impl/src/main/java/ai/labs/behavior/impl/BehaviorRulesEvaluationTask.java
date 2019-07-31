package ai.labs.behavior.impl;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.lifecycle.PackageConfigurationException;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.model.Data;
import ai.labs.resources.rest.config.behavior.model.BehaviorConfiguration;
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
    private static final String KEY_ACTIONS = "actions";
    private static final String KEY_BEHAVIOR_RULES_SUCCESS = "behavior_rules:success";
    private static final String KEY_BEHAVIOR_RULES_DROPPED_SUCCESS = "behavior_rules:droppedSuccess";
    private static final String KEY_BEHAVIOR_RULES_FAIL = "behavior_rules:fail";
    private static final String BEHAVIOR_CONFIG_URI = "uri";
    private static final String BEHAVIOR_CONFIG_APPEND_ACTIONS = "appendActions";
    private final IResourceClientLibrary resourceClientLibrary;
    private final IJsonSerialization jsonSerialization;
    private final IBehaviorDeserialization behaviorSerialization;

    private BehaviorRulesEvaluator evaluator;
    private boolean appendActions = true;

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
            addResultsToConversationMemory(memory, KEY_BEHAVIOR_RULES_SUCCESS, results.getSuccessRules());
            addResultsToConversationMemory(memory, KEY_BEHAVIOR_RULES_DROPPED_SUCCESS, results.getDroppedSuccessRules());
            addResultsToConversationMemory(memory, KEY_BEHAVIOR_RULES_FAIL, results.getFailRules());

            addActionsToConversationMemory(memory, results.getSuccessRules());

        } catch (BehaviorRulesEvaluator.BehaviorRuleExecutionException e) {
            String msg = "Error while evaluating behavior rules!";
            log.error(msg, e);
            throw new LifecycleException(msg, e);
        } catch (InterruptedException e) {
            log.warn(e.getLocalizedMessage(), e);
        }
    }

    private void addResultsToConversationMemory(IConversationMemory memory, String key, List<BehaviorRule> rules) {
        if (!rules.isEmpty()) {
            var allCurrentBehaviorRuleNames = rules.stream().map(BehaviorRule::getName).collect(Collectors.toList());
            saveResults(memory, allCurrentBehaviorRuleNames, key, false, false);
        }
    }

    private void addActionsToConversationMemory(IConversationMemory memory, List<BehaviorRule> successRules) {
        if (!successRules.isEmpty()) {
            List<String> allCurrentActions = new LinkedList<>();
            successRules.forEach(successRule -> successRule.getActions().stream().
                    filter(action -> !allCurrentActions.contains(action)).forEach(allCurrentActions::add));

            saveResults(memory, allCurrentActions, KEY_ACTIONS, true, true);
        }
    }

    private void saveResults(IConversationMemory memory, List<String> allCurrent, String key,
                             boolean addResultsToConversationMemory, boolean makePublic) {
        var currentStep = memory.getCurrentStep();

        List<String> results = new LinkedList<>();
        if (appendActions || allCurrent.isEmpty()) {
            IData<List<String>> latestResults = currentStep.getLatestData(key);
            if (latestResults != null && latestResults.getResult() != null) {
                results.addAll(latestResults.getResult());
            }
        }

        results.addAll(allCurrent.stream().
                filter(result -> !results.contains(result)).collect(Collectors.toList()));

        Data resultsData = new Data<>(key, results);
        resultsData.setPublic(makePublic);
        currentStep.storeData(resultsData);
        if (addResultsToConversationMemory) {
            currentStep.resetConversationOutput(key);
            currentStep.addConversationOutputList(key, results);
        }
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
            Object appendActionsObj = configuration.get(BEHAVIOR_CONFIG_APPEND_ACTIONS);
            if (appendActionsObj != null) {
                appendActions = Boolean.parseBoolean(appendActionsObj.toString());
            }

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

        ConfigValue configValue =
                new ConfigValue("Resource URI", FieldType.URI, false, null);
        extensionDescriptor.getConfigs().put(BEHAVIOR_CONFIG_URI, configValue);

        ConfigValue appendActionsConfig =
                new ConfigValue("Append Actions", FieldType.BOOLEAN, false, true);
        extensionDescriptor.getConfigs().put(BEHAVIOR_CONFIG_APPEND_ACTIONS, appendActionsConfig);

        return extensionDescriptor;
    }
}
