package ai.labs.eddi.engine.runtime.bootstrap;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@ApplicationScoped
public class ExecutorInit {
    @Produces
    @ApplicationScoped
    public ScheduledExecutorService getScheduledExecutorService(
            @ConfigProperty(name = "systemRuntime.threadPoolSize") Integer threadPoolSize) {

        return Executors.newScheduledThreadPool(threadPoolSize);
    }
}
