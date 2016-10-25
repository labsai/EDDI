package io.sls.core.behavior;

import io.sls.core.lifecycle.AbstractLifecycleTask;
import io.sls.core.lifecycle.ILifecycleTask;
import io.sls.core.lifecycle.LifecycleException;
import io.sls.core.lifecycle.PackageConfigurationException;
import io.sls.core.runtime.client.configuration.IResourceClientLibrary;
import io.sls.core.runtime.service.ServiceException;
import io.sls.memory.IConversationMemory;
import io.sls.memory.impl.Data;
import io.sls.resources.rest.behavior.model.BehaviorConfiguration;
import io.sls.serialization.DeserializationException;
import io.sls.serialization.JSONSerialization;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
@Singleton
@Slf4j
public class BehaviorRulesEvaluationTask extends AbstractLifecycleTask implements ILifecycleTask {
    private BehaviorRulesEvaluator evaluator;
    private static final String BEHAVIOR_CONFIG = "uri";
    private final IResourceClientLibrary resourceClientLibrary;

    @Inject
    public BehaviorRulesEvaluationTask(IResourceClientLibrary resourceClientLibrary) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.evaluator = new BehaviorRulesEvaluator();
    }

    @Override
    public String getId() {
        return evaluator.getClass().toString();
    }

    @Override
    public Object getComponent() {
        return evaluator;
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
        BehaviorSetResult results;
        try {
            results = evaluator.evaluate(memory);
            storeResultIfNotEmpty(memory, "behavior_rules:success", results.getSuccessRules());
            storeResultIfNotEmpty(memory, "behavior_rules:droppedSuccess", results.getDroppedSuccessRules());
            //storeResultIfNotEmpty(memory, "behavior_rules:fail", results.getFailRules()); //not ideal for auto testing

            if (!results.getSuccessRules().isEmpty()) {
                addActionsToConversationMemory(memory, results.getSuccessRules());
            }
        } catch (BehaviorRulesEvaluator.BehaviorRuleExecutionException e) {
            String msg = "Error while evaluating behavior rules!";
            log.error(msg, e);
            throw new LifecycleException(msg, e);
        }
    }

    private void addActionsToConversationMemory(IConversationMemory memory, List<BehaviorRule> successRules) {
        List<String> allActions = new LinkedList<String>();
        for (BehaviorRule successRule : successRules) {
            allActions.addAll(successRule.getActions());
        }

        Data actions = new Data("actions", allActions);
        actions.setPublic(true);
        memory.getCurrentStep().storeData(actions);
    }

    private void storeResultIfNotEmpty(IConversationMemory memory, String key, List<BehaviorRule> result) {
        if (!result.isEmpty()) {
            memory.getCurrentStep().storeData(new Data(key, convert(result)));
        }
    }

    private List<String> convert(List<BehaviorRule> behaviorRules) {
        List<String> ret = new LinkedList<String>();

        for (BehaviorRule behaviorRule : behaviorRules) {
            ret.add(behaviorRule.getName());
        }

        return ret;
    }

    @Override
    public void configure(Map<String, Object> configuration) throws PackageConfigurationException {
        Object uriObj = configuration.get(BEHAVIOR_CONFIG);
        URI uri = URI.create(uriObj.toString());


        try {
            BehaviorConfiguration behaviorConfiguration = resourceClientLibrary.getResource(uri, BehaviorConfiguration.class);
            String behaviorConfigJson = JSONSerialization.serialize(behaviorConfiguration);
            BehaviorSet behaviorSet = BehaviorSerialization.deserialize(behaviorConfigJson);

            evaluator.setBehaviorSet(behaviorSet);

        } catch (IOException | DeserializationException e) {
            String message = "Error while configuring BehaviorRuleLifecycleTask!";
            throw new PackageConfigurationException(message, e);
        } catch (ServiceException e) {
            String message = "Error while fetching BehaviorRuleConfigurationSet!\n" + e.getLocalizedMessage();
            throw new PackageConfigurationException(message, e);
        }
    }
}
