package ai.labs.behavior.bootstrap;

import ai.labs.behavior.impl.BehaviorDeserialization;
import ai.labs.behavior.impl.BehaviorRulesEvaluationTask;
import ai.labs.behavior.impl.IBehaviorDeserialization;
import ai.labs.behavior.impl.conditions.*;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;
import com.google.inject.multibindings.MapBinder;

/**
 * @author ginccc
 */
public class BehaviorModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IBehaviorDeserialization.class).to(BehaviorDeserialization.class).in(Scopes.SINGLETON);
        MapBinder<String, ILifecycleTask> lifecycleTaskPlugins
                = MapBinder.newMapBinder(binder(), String.class, ILifecycleTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.behavior").to(BehaviorRulesEvaluationTask.class);

        MapBinder<String, IBehaviorCondition> behaviorConditionPlugins
                = MapBinder.newMapBinder(binder(), String.class, IBehaviorCondition.class);
        behaviorConditionPlugins.addBinding("ai.labs.behavior.conditions.inputmatcher").
                to(InputMatcher.class);
        behaviorConditionPlugins.addBinding("ai.labs.behavior.conditions.actionmatcher").
                to(ActionMatcher.class);
        behaviorConditionPlugins.addBinding("ai.labs.behavior.conditions.contextmatcher").
                to(ContextMatcher.class);
        behaviorConditionPlugins.addBinding("ai.labs.behavior.conditions.dynamicvaluematcher").
                to(DynamicValueMatcher.class);
        behaviorConditionPlugins.addBinding("ai.labs.behavior.conditions.connector").
                to(Connector.class);
        behaviorConditionPlugins.addBinding("ai.labs.behavior.conditions.dependency").
                to(Dependency.class);
        behaviorConditionPlugins.addBinding("ai.labs.behavior.conditions.negation").
                to(Negation.class);
        behaviorConditionPlugins.addBinding("ai.labs.behavior.conditions.occurrence").
                to(Occurrence.class);
        behaviorConditionPlugins.addBinding("ai.labs.behavior.conditions.sizematcher").
                to(SizeMatcher.class);
    }
}
