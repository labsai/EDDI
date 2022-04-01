package ai.labs.eddi.modules.behavior.impl;

import ai.labs.eddi.configs.behavior.model.BehaviorConfiguration;
import ai.labs.eddi.datastore.serialization.DeserializationException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.LifecycleException;
import ai.labs.eddi.engine.lifecycle.PackageConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.models.ExtensionDescriptor;
import org.jboss.logging.Logger;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ai.labs.eddi.models.ExtensionDescriptor.ConfigValue;
import static ai.labs.eddi.models.ExtensionDescriptor.FieldType;

/**
 * @author ginccc
 */
@RequestScoped
public class BehaviorRulesEvaluationTask implements ILifecycleTask {
    public static final String ID = "ai.labs.behavior";
    private static final String KEY_ACTIONS = "actions";
    public static final String BEHAVIOR_RULES_TYPE = "behavior_rules";
    private static final String KEY_BEHAVIOR_RULES_SUCCESS = BEHAVIOR_RULES_TYPE + ":success";
    private static final String KEY_BEHAVIOR_RULES_DROPPED_SUCCESS = BEHAVIOR_RULES_TYPE + ":droppedSuccess";
    private static final String KEY_BEHAVIOR_RULES_FAIL = BEHAVIOR_RULES_TYPE + ":fail";
    private static final String BEHAVIOR_CONFIG_URI = "uri";
    private static final String BEHAVIOR_CONFIG_APPEND_ACTIONS = "appendActions";
    private final IResourceClientLibrary resourceClientLibrary;
    private final IJsonSerialization jsonSerialization;
    private final IBehaviorDeserialization behaviorSerialization;

    private BehaviorRulesEvaluator evaluator;
    private boolean appendActions = true;

    @Inject
    Logger log;

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
    public String getType() {
        return BEHAVIOR_RULES_TYPE;
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
