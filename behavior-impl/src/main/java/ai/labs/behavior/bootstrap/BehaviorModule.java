package ai.labs.behavior.bootstrap;

import ai.labs.behavior.impl.BehaviorRulesEvaluationTask;
import ai.labs.behavior.impl.conditions.*;
import ai.labs.lifecycle.ILifecycleTask;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.inject.Provider;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author ginccc
 */
@ApplicationScoped
public class BehaviorModule {
    @PostConstruct
    @Inject
    protected void configure(Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders,
                             Instance<ILifecycleTask> instance) {

        lifecycleTaskProviders.put("ai.labs.behavior", () -> instance.select(BehaviorRulesEvaluationTask.class).get());
    }

    @BehaviorConditions
    @Produces
    @ApplicationScoped
    Map<String, Provider<IBehaviorCondition>> produceBehaviorConditionProvider(Instance<IBehaviorCondition> instance) {
        Map<String, Provider<IBehaviorCondition>> map = new LinkedHashMap<>();

        map.put("ai.labs.behavior.conditions.inputmatcher", () ->
                instance.select(InputMatcher.class).get());
        map.put("ai.labs.behavior.conditions.actionmatcher", () ->
                instance.select(ActionMatcher.class).get());
        map.put("ai.labs.behavior.conditions.contextmatcher", () ->
                instance.select(ContextMatcher.class).get());
        map.put("ai.labs.behavior.conditions.dynamicvaluematcher", () ->
                instance.select(DynamicValueMatcher.class).get());
        map.put("ai.labs.behavior.conditions.connector", () ->
                instance.select(Connector.class).get());
        map.put("ai.labs.behavior.conditions.dependency", () ->
                instance.select(Dependency.class).get());
        map.put("ai.labs.behavior.conditions.negation", () ->
                instance.select(Negation.class).get());
        map.put("ai.labs.behavior.conditions.occurrence", () ->
                instance.select(Occurrence.class).get());
        map.put("ai.labs.behavior.conditions.sizematcher", () ->
                instance.select(SizeMatcher.class).get());

        return map;
    }
}
