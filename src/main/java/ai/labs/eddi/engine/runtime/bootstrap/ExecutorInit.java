package ai.labs.eddi.engine.runtime.bootstrap;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class ExecutorInit {
    private static final Logger log = Logger.getLogger(ExecutorInit.class);

    @Produces
    @ApplicationScoped
    public ScheduledExecutorService getScheduledExecutorService(
            @ConfigProperty(name = "systemRuntime.threadPoolSize") Integer threadPoolSize) {

        var scheduledExecutorService = Executors.newScheduledThreadPool(threadPoolSize);
        initExecutorServiceShutdownHook(scheduledExecutorService);

        return scheduledExecutorService;
    }

    private void initExecutorServiceShutdownHook(ScheduledExecutorService executorService) {
        Runtime.getRuntime().addShutdownHook(new Thread("ShutdownHook_ExecutorService") {
            @Override
            public void run() {
                executorService.shutdown(); // Disable new tasks from being submitted
                try {
                    // Wait a while for existing tasks to terminate
                    if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                        executorService.shutdownNow(); // Cancel currently executing tasks
                        // Wait a while for tasks to respond to being cancelled
                        if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                            log.error("Pool did not terminate");
                        }
                    }
                } catch (InterruptedException e) {
                    // (Re-)Cancel if current thread also interrupted
                    executorService.shutdownNow();
                    // Preserve interrupt status
                    Thread.currentThread().interrupt();
                    log.error(e.getLocalizedMessage(), e);
                }
            }
        });
    }
}

