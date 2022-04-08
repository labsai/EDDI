package ai.labs.eddi.engine.runtime;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.Map;
import java.util.concurrent.*;

/**
 * @author ginccc
 */
@ApplicationScoped
public class BaseRuntime implements IRuntime {
    private final String projectVersion;

    private final ScheduledExecutorService executorService;
    private final String projectName;

    private boolean isInit = false;

    private final Logger log = Logger.getLogger(BaseRuntime.class);

    @Inject
    public BaseRuntime(@ConfigProperty(name = "systemRuntime.projectName") String projectName,
                       @ConfigProperty(name = "systemRuntime.projectVersion") String projectVersion,
                       ScheduledExecutorService scheduledExecutorService) {

        this.executorService = scheduledExecutorService;

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

    @Override
    public <T> ScheduledFuture<T> submitScheduledCallable(final Callable<T> callable,
                                                          long delay, TimeUnit timeUnit,
                                                          final Map<Object, Object> threadBindings) {
        return executorService.schedule(() -> {
            try {
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
        }, delay, timeUnit);
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
