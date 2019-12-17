package ai.labs.core.bootstrap;

import ai.labs.core.rest.internal.*;
import ai.labs.core.rest.utilities.ConversationSetup;
import ai.labs.core.rest.utilities.IConversationSetup;
import ai.labs.rest.restinterfaces.*;
import ai.labs.runtime.DatabaseLogs;
import ai.labs.runtime.IConversationCoordinator;
import ai.labs.runtime.IDatabaseLogs;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.runtime.internal.ConversationCoordinator;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import io.github.mweirauch.micrometer.jvm.extras.ProcessMemoryMetrics;
import io.github.mweirauch.micrometer.jvm.extras.ProcessThreadMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.*;
import io.micrometer.core.instrument.binder.logging.LogbackMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;

public class CoreModule extends AbstractBaseModule {
    public CoreModule(InputStream... inputStream) {
        super(inputStream);
    }

    @Override
    protected void configure() {
        registerConfigFiles(configFiles);

        bind(ILogoutEndpoint.class).to(LogoutEndpoint.class);
        bind(IRestBotEngine.class).to(RestBotEngine.class).in(Scopes.SINGLETON);
        bind(IRestBotAdministration.class).to(RestBotAdministration.class).in(Scopes.SINGLETON);
        bind(IRestBotManagement.class).to(RestBotManagement.class).in(Scopes.SINGLETON);
        bind(IRestHealthCheck.class).to(RestHealthCheck.class).in(Scopes.SINGLETON);
        bind(IRestLogs.class).to(RestLogs.class).in(Scopes.SINGLETON);
        bind(IRestPrometheusMonitoring.class).to(RestPrometheusMonitoring.class).in(Scopes.SINGLETON);
        bind(IDatabaseLogs.class).to(DatabaseLogs.class).in(Scopes.SINGLETON);
        bind(IConversationCoordinator.class).to(ConversationCoordinator.class).in(Scopes.SINGLETON);
        bind(IContextLogger.class).to(ContextLogger.class).in(Scopes.SINGLETON);
        bind(IConversationSetup.class).to(ConversationSetup.class).in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    public MeterRegistry providePrometheusMeterRegistry(PrometheusMeterRegistry prometheusMeterRegistry) {
        return prometheusMeterRegistry;
    }

    @Provides
    @Singleton
    public PrometheusMeterRegistry providePrometheusMeterRegistry(ExecutorService executorService,
                                                                  @Named("systemRuntime.projectName") String projectName) {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new LogbackMetrics().bindTo(registry);
        new ClassLoaderMetrics().bindTo(registry);
        new ExecutorServiceMetrics(executorService,
                projectName + "-ExecutorService",
                () -> Tags.of(projectName, "ExecutorService").iterator()).bindTo(registry);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new ProcessMemoryMetrics().bindTo(registry);
        new ProcessThreadMetrics().bindTo(registry);
        new FileDescriptorMetrics().bindTo(registry);
        new DiskSpaceMetrics(new File("/")).bindTo(registry);
        new UptimeMetrics().bindTo(registry);

        registry.config().commonTags("instance", projectName);
        registry.config().commonTags("application", projectName);
        registry.config().commonTags("service", projectName);

        return registry;
    }
}
