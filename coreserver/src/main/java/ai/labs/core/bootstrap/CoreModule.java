package ai.labs.core.bootstrap;

import ai.labs.core.rest.internal.*;
import ai.labs.rest.rest.*;
import ai.labs.runtime.DatabaseLogs;
import ai.labs.runtime.IConversationCoordinator;
import ai.labs.runtime.IDatabaseLogs;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.runtime.internal.ConversationCoordinator;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.*;
import io.micrometer.core.instrument.binder.logging.LogbackMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

import javax.inject.Singleton;
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
        bind(IRestBotEngine.class).to(RestBotEngine.class);
        bind(IRestBotAdministration.class).to(RestBotAdministration.class);
        bind(IRestBotManagement.class).to(RestBotManagement.class);
        bind(IRestHealthCheck.class).to(RestHealthCheck.class);
        bind(IRestLogs.class).to(RestLogs.class);
        bind(IRestPrometheusMonitoring.class).to(RestPrometheusMonitoring.class);
        bind(IDatabaseLogs.class).to(DatabaseLogs.class).in(Scopes.SINGLETON);
        bind(IConversationCoordinator.class).to(ConversationCoordinator.class).in(Scopes.SINGLETON);
        bind(IContextLogger.class).to(ContextLogger.class).in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    public PrometheusMeterRegistry providePrometheusMeterRegistry(ExecutorService executorService) {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new LogbackMetrics().bindTo(registry);
        new ClassLoaderMetrics().bindTo(registry);
        new ExecutorServiceMetrics(executorService,
                "EDDI-ExecutorService",
                () -> Tags.of("EDDI", "ExecutorService").iterator()).bindTo(registry);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);

        return registry;
    }
}
