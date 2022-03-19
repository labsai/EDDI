package ai.labs.eddi.modules.behavior.bootstrap;


import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.modules.behavior.impl.BehaviorRulesEvaluationTask;
import ai.labs.eddi.modules.behavior.impl.conditions.*;
import ai.labs.eddi.modules.nlp.InputParserTask;
import jakarta.annotation.PostConstruct;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
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
    protected void configure(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders,
                             Instance<ILifecycleTask> instance) {

        lifecycleTaskProviders.put(InputParserTask.ID, () -> instance.select(BehaviorRulesEvaluationTask.class).get());
    }

    @BehaviorConditions
    @ApplicationScoped
    Map<String, Provider<IBehaviorCondition>> produceNormalizerProvider(Instance<IBehaviorCondition> instance) {
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
