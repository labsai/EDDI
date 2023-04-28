package ai.labs.eddi.modules.behavior.bootstrap;


import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.modules.behavior.impl.BehaviorRulesEvaluationTask;
import ai.labs.eddi.modules.behavior.impl.conditions.*;
import io.quarkus.runtime.Startup;
import org.jboss.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author ginccc
 */
@Startup(1000)
@ApplicationScoped
public class BehaviorModule {

    private static final Logger LOGGER = Logger.getLogger("Startup");
    private final Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders;
    private final Instance<ILifecycleTask> instance;

    public BehaviorModule(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders,
                          Instance<ILifecycleTask> instance) {
        this.lifecycleTaskProviders = lifecycleTaskProviders;
        this.instance = instance;
    }

    @PostConstruct
    @Inject
    protected void configure() {
        lifecycleTaskProviders.put(BehaviorRulesEvaluationTask.ID, () -> instance.select(BehaviorRulesEvaluationTask.class).get());
        LOGGER.debug("Added Behaviour Module, current size of lifecycle modules " + lifecycleTaskProviders.size());
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
