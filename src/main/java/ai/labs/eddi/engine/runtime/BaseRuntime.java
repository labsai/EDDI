package ai.labs.eddi.engine.runtime;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.*;

/**
 * @author ginccc
 */
@ApplicationScoped
public class BaseRuntime implements IRuntime {
    private final String projectVersion;

    @Inject
    ManagedExecutor executorService;
    private final String projectName;

    private boolean isInit = false;

    private final Logger log = Logger.getLogger(BaseRuntime.class);

    @Inject
    public BaseRuntime(@ConfigProperty(name = "systemRuntime.projectName") String projectName,
                       @ConfigProperty(name = "systemRuntime.projectVersion") String projectVersion) {


        this.projectName = projectName;
        this.projectVersion = projectVersion;

        init();
    }

    public void init() {
        if (!isInit) {
            if (projectName == null || projectName.isEmpty()) {
                log.error("ProjectName should be defined in systemRuntime.properties as 'systemRuntime.projectName'");
            } else {
                initProjectName(projectName);
            }

            logVersion();
            isInit = true;
        } else {
            log.warn("SystemRuntime has already been initialized!");
        }
    }

    private void initProjectName(String projectName) {
        System.setProperty("systemRuntime.projectName", lowerCaseFirstLetter(projectName));
    }

    @Override
    public void logVersion() {
        log.info(projectName + " v" + getVersion());
    }

    @Override
    public String getVersion() {
        return projectVersion;
    }

    private static String lowerCaseFirstLetter(String value) {
        char[] chars = value.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    // TODO: find another way to schedule
    @Override
    public <T> Future<T> submitScheduledCallable(final Callable<T> callable,
                                                          long delay, TimeUnit timeUnit,
                                                          final Map<Object, Object> threadBindings) {

        long multipliedDelay = delay;
        switch (timeUnit) {
            case NANOSECONDS:
                multipliedDelay = delay/1000000;
                break;
            case MICROSECONDS:
                multipliedDelay = delay/1000;
                break;
            case MILLISECONDS:
                break;
            case SECONDS:
                multipliedDelay = delay * 1000;
                break;
            case MINUTES:
                multipliedDelay = delay * 60 * 1000;
                break;
            case HOURS:
                multipliedDelay = delay * 60 * 60 * 1000;
                break;
            case DAYS:
                multipliedDelay = delay * 24 * 60 * 60 * 1000;
                break;
        }
        final long finalMultipliedDelay = multipliedDelay;

        return executorService.submit(() -> {
            try {
                Thread.sleep(finalMultipliedDelay);
                if (threadBindings != null) {
                    ThreadContext.setResources(threadBindings);
                }
                return callable.call();
            } catch (Throwable t) {
                log.error(t.getLocalizedMessage(), t);
                return null;
            } finally {
                ThreadContext.remove();
            }
        });

    }

    @Override
    public <T> Future<T> submitCallable(final Callable<T> callable, final Map<Object, Object> threadBindings) {
        return submitCallable(callable, new IgnoredCallableResult<>(), threadBindings);
    }

    @Override
    public <T> Future<T> submitCallable(final Callable<T> callable,
                                        final IFinishedExecution<T> callback,
                                        final Map<Object, Object> threadBindings) {

        return getExecutorService().submit(() -> {
            try {
                if (threadBindings != null) {
                    ThreadContext.setResources(threadBindings);
                }

                final T result = callable.call();
                callback.onComplete(result);
                return result;
            } catch (Throwable t) {
                log.error(t.getLocalizedMessage(), t);
                callback.onFailure(t);
                return null;
            } finally {
                ThreadContext.remove();
            }
        });
    }

    private static class IgnoredCallableResult<T> implements IFinishedExecution<T> {
        @Override
        public void onComplete(T result) {
            //ignored result
        }

        @Override
        public void onFailure(Throwable t) {
            //ignored result
        }
    }
}
