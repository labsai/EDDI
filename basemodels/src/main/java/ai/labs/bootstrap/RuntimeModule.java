package ai.labs.bootstrap;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.inject.Produces;
import javax.inject.Singleton;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * @author ginccc
 */
public class RuntimeModule {
    @ScheduledExecutor
    @Produces
    @Singleton
    ScheduledThreadPoolExecutor provideExecutor(@ConfigProperty(name = "threads.corePoolSize") int corePoolSize) {
        try {
            return new ScheduledThreadPoolExecutor(corePoolSize);
        } catch (Exception e) {
            throw new RuntimeException(e.getLocalizedMessage(), e);
        }
    }

    @Produces
    @Singleton
    ExecutorService provideScheduledThreadPoolExecutor(@ScheduledExecutor ScheduledThreadPoolExecutor scheduledThreadPoolExecutor) {
        try {
            return scheduledThreadPoolExecutor;
        } catch (Exception e) {
            throw new RuntimeException(e.getLocalizedMessage(), e);
        }
    }
}

