package io.sls.core.bootstrap;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.parser.InputParserTask;
import com.google.inject.Scopes;
import com.google.inject.multibindings.MapBinder;
import io.sls.botmarklet.rest.IRestBotmarklet;
import io.sls.botmarklet.rest.impl.RestBotmarklet;
import io.sls.core.CoreRuntime;
import io.sls.core.behavior.BehaviorRulesEvaluationTask;
import io.sls.core.behavior.BehaviorSerialization;
import io.sls.core.behavior.IBehaviorSerialization;
import io.sls.core.normalizing.NormalizeInputTask;
import io.sls.core.output.SimpleOutputTask;
import io.sls.core.rest.IRestBotAdministration;
import io.sls.core.rest.IRestBotEngine;
import io.sls.core.rest.IRestBotUI;
import io.sls.core.sendmail.SendMailTask;
import io.sls.core.ui.rest.internal.RestBotAdministration;
import io.sls.core.ui.rest.internal.RestBotEngine;
import io.sls.core.ui.rest.internal.RestBotUI;
import io.sls.faces.html.IHtmlFaceStore;
import io.sls.faces.html.impl.HtmlFaceStore;
import io.sls.logging.client.rest.IRestClientLogging;
import io.sls.logging.client.rest.impl.RestClientLogging;
import io.sls.runtime.IBotFactory;
import io.sls.runtime.IPackageFactory;
import io.sls.runtime.bootstrap.AbstractBaseModule;
import io.sls.runtime.client.bots.BotStoreClientLibrary;
import io.sls.runtime.client.bots.IBotStoreClientLibrary;
import io.sls.runtime.client.configuration.IResourceClientLibrary;
import io.sls.runtime.client.configuration.ResourceClientLibrary;
import io.sls.runtime.client.packages.IPackageStoreClientLibrary;
import io.sls.runtime.client.packages.PackageStoreClientLibrary;
import io.sls.runtime.internal.BotFactory;
import io.sls.runtime.internal.PackageFactory;
import io.sls.runtime.service.BotStoreService;
import io.sls.runtime.service.IBotStoreService;
import io.sls.runtime.service.IPackageStoreService;
import io.sls.runtime.service.PackageStoreService;

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

        bind(IHtmlFaceStore.class).to(HtmlFaceStore.class).in(Scopes.SINGLETON);

        bind(IRestBotEngine.class).to(RestBotEngine.class);
        bind(IRestBotAdministration.class).to(RestBotAdministration.class);
        bind(IRestBotUI.class).to(RestBotUI.class);
        bind(IRestBotmarklet.class).to(RestBotmarklet.class);
        bind(IRestClientLogging.class).to(RestClientLogging.class);

        MapBinder<String, ILifecycleTask> lifecycleTaskPlugins
                = MapBinder.newMapBinder(binder(), String.class, ILifecycleTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.normalizer").to(NormalizeInputTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.parser").to(InputParserTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.behavior").to(BehaviorRulesEvaluationTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.output").to(SimpleOutputTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.sendmail").to(SendMailTask.class);


        bind(CoreRuntime.class).asEagerSingleton();
    }
}
