package ai.labs.eddi.modules.behavior.impl;

import ai.labs.eddi.configs.behavior.model.BehaviorConfiguration;
import ai.labs.eddi.datastore.serialization.DeserializationException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.PackageConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.models.ExtensionDescriptor;
import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
@ApplicationScoped
public class BehaviorRulesEvaluationTask implements ILifecycleTask {
    public static final String ID = "ai.labs.behavior";
    private static final String KEY_ACTIONS = "actions";
    public static final String BEHAVIOR_RULES_TYPE = "behavior_rules";
    private static final String KEY_BEHAVIOR_RULES_SUCCESS = BEHAVIOR_RULES_TYPE + ":success";
    private static final String KEY_BEHAVIOR_RULES_DROPPED_SUCCESS = BEHAVIOR_RULES_TYPE + ":droppedSuccess";
    private static final String KEY_BEHAVIOR_RULES_FAIL = BEHAVIOR_RULES_TYPE + ":fail";
    private static final String KEY_EXPRESSIONS = "expressions";
    private static final String BEHAVIOR_CONFIG_URI = "uri";
    private static final String BEHAVIOR_CONFIG_APPEND_ACTIONS = "appendActions";
    private static final String BEHAVIOR_CONFIG_EXPRESSIONS_AS_ACTIONS = "expressionsAsActions";
    private final IResourceClientLibrary resourceClientLibrary;
    private final IJsonSerialization jsonSerialization;
    private final IBehaviorDeserialization behaviorSerialization;
    private final IExpressionProvider expressionProvider;

    private final static boolean appendActionsDefault = true;
    private final static boolean expressionsAsActionsDefault = false;

    private static final Logger log = Logger.getLogger(BehaviorRulesEvaluationTask.class);

    @Inject
    public BehaviorRulesEvaluationTask(IResourceClientLibrary resourceClientLibrary,
                                       IJsonSerialization jsonSerialization,
                                       IBehaviorDeserialization behaviorSerialization,
                                       IExpressionProvider expressionProvider) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.jsonSerialization = jsonSerialization;
        this.behaviorSerialization = behaviorSerialization;
        this.expressionProvider = expressionProvider;
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
    public void execute(IConversationMemory memory, Object component) throws LifecycleException {
        try {
            final var evaluator = (BehaviorRulesEvaluator) component;
            var results = evaluator.evaluate(memory);
            var appendActions = evaluator.isAppendActions();
            addResultsToConversationMemory(memory, KEY_BEHAVIOR_RULES_SUCCESS, results.getSuccessRules(), appendActions);
            addResultsToConversationMemory(memory, KEY_BEHAVIOR_RULES_DROPPED_SUCCESS, results.getDroppedSuccessRules(), appendActions);
            addResultsToConversationMemory(memory, KEY_BEHAVIOR_RULES_FAIL, results.getFailRules(), appendActions);

            addActionsToConversationMemory(memory, results.getSuccessRules(),
                    appendActions, evaluator.isExpressionsAsActions());

        } catch (BehaviorRulesEvaluator.BehaviorRuleExecutionException e) {
            String msg = "Error while evaluating behavior rules!";
            log.error(msg, e);
            throw new LifecycleException(msg, e);
        } catch (InterruptedException e) {
            log.warn(e.getLocalizedMessage(), e);
        }
    }

    private void addResultsToConversationMemory(IConversationMemory memory, String key,
                                                List<BehaviorRule> rules, boolean appendActions) {

        if (!rules.isEmpty()) {
            var allCurrentBehaviorRuleNames = rules.stream().
                    map(BehaviorRule::getName).collect(Collectors.toList());
            saveResults(memory, allCurrentBehaviorRuleNames, key,
                    false, false, appendActions);
        }
    }

    private void addActionsToConversationMemory(IConversationMemory memory, List<BehaviorRule> successRules,
                                                boolean appendActions, boolean expressionsAsActions) {
        List<String> allCurrentActions = new LinkedList<>();
        successRules.forEach(successRule -> successRule.getActions().stream().
                filter(action -> !allCurrentActions.contains(action)).forEach(allCurrentActions::add));

        if (expressionsAsActions) {
            allCurrentActions.addAll(extractExpressionsAsActions(memory));
        }

        if (!allCurrentActions.isEmpty()) {
            saveResults(memory, allCurrentActions, KEY_ACTIONS, true, true, appendActions);
        }
    }

    private List<String> extractExpressionsAsActions(IConversationMemory memory) {
        IData<String> data = memory.getCurrentStep().getLatestData(KEY_EXPRESSIONS);

        Expressions inputExpressions = new Expressions();
        if (data != null && data.getResult() != null) {
            inputExpressions = expressionProvider.parseExpressions(data.getResult());
        }

        return inputExpressions.stream().map(Expression::getExpressionName).collect(Collectors.toList());
    }

    private void saveResults(IConversationMemory memory, List<String> allCurrentActions, String key,
                             boolean addResultsToConversationMemory, boolean makePublic, boolean appendActions) {
        var currentStep = memory.getCurrentStep();

        List<String> results = new LinkedList<>();
        if (appendActions || allCurrentActions.isEmpty()) {
            IData<List<String>> latestResults = currentStep.getLatestData(key);
            if (latestResults != null && latestResults.getResult() != null) {
                results.addAll(latestResults.getResult());
            }
        }

        results.addAll(allCurrentActions.stream().filter(result -> !results.contains(result)).toList());

        var resultsData = new Data<>(key, results);
        resultsData.setPublic(makePublic);
        currentStep.storeData(resultsData);
        if (addResultsToConversationMemory) {
            currentStep.resetConversationOutput(key);
            currentStep.addConversationOutputList(key, results);
        }
    }

    @Override
    public Object configure(Map<String, Object> configuration, Map<String, Object> extensions)
            throws PackageConfigurationException {

        Object uriObj = configuration.get(BEHAVIOR_CONFIG_URI);
        URI uri = URI.create(uriObj.toString());

        try {
            var appendActions = appendActionsDefault;
            var expressionsAsActions = expressionsAsActionsDefault;

            var behaviorConfiguration = resourceClientLibrary.getResource(uri, BehaviorConfiguration.class);
            var behaviorConfigJson = jsonSerialization.serialize(behaviorConfiguration);
            var behaviorSet = behaviorSerialization.deserialize(behaviorConfigJson);

            Object appendActionsObj = configuration.getOrDefault(
                    BEHAVIOR_CONFIG_APPEND_ACTIONS, behaviorConfiguration.getAppendActions());
            if (appendActionsObj != null) {
                appendActions = Boolean.parseBoolean(appendActionsObj.toString());
            }

            Object expressionsAsActionsObj = configuration.getOrDefault(
                    BEHAVIOR_CONFIG_EXPRESSIONS_AS_ACTIONS, behaviorConfiguration.getExpressionsAsActions());
            if (expressionsAsActionsObj != null) {
                expressionsAsActions = Boolean.parseBoolean(expressionsAsActionsObj.toString());
            }

            return new BehaviorRulesEvaluator(behaviorSet, appendActions, expressionsAsActions);
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
        var extensionDescriptor = new ExtensionDescriptor(ID);
        extensionDescriptor.setDisplayName("Behavior Rules");

        var configValue =
                new ConfigValue("Resource URI", FieldType.URI, false, null);
        extensionDescriptor.getConfigs().put(BEHAVIOR_CONFIG_URI, configValue);

        var appendActionsConfig =
                new ConfigValue("Append Actions", FieldType.BOOLEAN, true, true);
        extensionDescriptor.getConfigs().put(BEHAVIOR_CONFIG_APPEND_ACTIONS, appendActionsConfig);

        var expressionsAsActionsConfig =
                new ConfigValue("Expressions as Actions", FieldType.BOOLEAN, true, false);
        extensionDescriptor.getConfigs().put(BEHAVIOR_CONFIG_EXPRESSIONS_AS_ACTIONS, expressionsAsActionsConfig);

        return extensionDescriptor;
    }
}
