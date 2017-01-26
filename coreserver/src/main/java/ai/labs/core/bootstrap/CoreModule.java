package ai.labs.core.bootstrap;

import ai.labs.core.CoreRuntime;
import ai.labs.core.behavior.BehaviorRulesEvaluationTask;
import ai.labs.core.behavior.BehaviorSerialization;
import ai.labs.core.behavior.IBehaviorSerialization;
import ai.labs.core.normalizing.NormalizeInputTask;
import ai.labs.core.output.SimpleOutputTask;
import ai.labs.core.rest.internal.RestBotAdministration;
import ai.labs.core.rest.internal.RestBotEngine;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.parser.InputParserTask;
import ai.labs.rest.rest.IRestBotAdministration;
import ai.labs.rest.rest.IRestBotEngine;
import ai.labs.runtime.IBotFactory;
import ai.labs.runtime.IPackageFactory;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.runtime.client.bots.BotStoreClientLibrary;
import ai.labs.runtime.client.bots.IBotStoreClientLibrary;
import ai.labs.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.runtime.client.configuration.ResourceClientLibrary;
import ai.labs.runtime.client.packages.IPackageStoreClientLibrary;
import ai.labs.runtime.client.packages.PackageStoreClientLibrary;
import ai.labs.runtime.internal.BotFactory;
import ai.labs.runtime.internal.PackageFactory;
import ai.labs.runtime.service.BotStoreService;
import ai.labs.runtime.service.IBotStoreService;
import ai.labs.runtime.service.IPackageStoreService;
import ai.labs.runtime.service.PackageStoreService;
import com.google.inject.Scopes;
import com.google.inject.multibindings.MapBinder;

import java.io.InputStream;

public class CoreModule extends AbstractBaseModule {
    public CoreModule(InputStream... inputStream) {
        super(inputStream);
    }

    @Override
    protected void configure() {
        registerConfigFiles(configFiles);

        bind(IResourceClientLibrary.class).to(ResourceClientLibrary.class).in(Scopes.SINGLETON);
        bind(IBotStoreClientLibrary.class).to(BotStoreClientLibrary.class).in(Scopes.SINGLETON);
        bind(IPackageStoreClientLibrary.class).to(PackageStoreClientLibrary.class).in(Scopes.SINGLETON);
        bind(IPackageStoreService.class).to(PackageStoreService.class).in(Scopes.SINGLETON);
        bind(IBotStoreService.class).to(BotStoreService.class).in(Scopes.SINGLETON);
        bind(IBehaviorSerialization.class).to(BehaviorSerialization.class).in(Scopes.SINGLETON);

        bind(IBotFactory.class).to(BotFactory.class).in(Scopes.SINGLETON);
        bind(IPackageFactory.class).to(PackageFactory.class).in(Scopes.SINGLETON);

        bind(IRestBotEngine.class).to(RestBotEngine.class);
        bind(IRestBotAdministration.class).to(RestBotAdministration.class);
        /*bind(IRestBotUI.class).to(RestBotUI.class);
        bind(IRestBotmarklet.class).to(RestBotmarklet.class);*/

        MapBinder<String, ILifecycleTask> lifecycleTaskPlugins
                = MapBinder.newMapBinder(binder(), String.class, ILifecycleTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.normalizer").to(NormalizeInputTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.parser").to(InputParserTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.behavior").to(BehaviorRulesEvaluationTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.output").to(SimpleOutputTask.class);


        bind(CoreRuntime.class).asEagerSingleton();
    }
}
