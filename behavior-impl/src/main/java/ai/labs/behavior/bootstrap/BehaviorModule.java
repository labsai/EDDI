package ai.labs.behavior.bootstrap;

import ai.labs.behavior.impl.BehaviorDeserialization;
import ai.labs.behavior.impl.BehaviorRulesEvaluationTask;
import ai.labs.behavior.impl.IBehaviorDeserialization;
import ai.labs.behavior.impl.extensions.*;
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


        MapBinder<String, IBehaviorExtension> behaviorExtensionPlugins
                = MapBinder.newMapBinder(binder(), String.class, IBehaviorExtension.class);
        behaviorExtensionPlugins.addBinding("ai.labs.behavior.extension.inputmatcher").
                to(InputMatcher.class);
        behaviorExtensionPlugins.addBinding("ai.labs.behavior.extension.contextmatcher").
                to(ContextMatcher.class);
        behaviorExtensionPlugins.addBinding("ai.labs.behavior.extension.connector").
                to(Connector.class);
        behaviorExtensionPlugins.addBinding("ai.labs.behavior.extension.dependency").
                to(Dependency.class);
        behaviorExtensionPlugins.addBinding("ai.labs.behavior.extension.negation").
                to(Negation.class);
        behaviorExtensionPlugins.addBinding("ai.labs.behavior.extension.occurrence").
                to(Occurrence.class);
        behaviorExtensionPlugins.addBinding("ai.labs.behavior.extension.resultsize").
                to(ResultSize.class);
    }
}
