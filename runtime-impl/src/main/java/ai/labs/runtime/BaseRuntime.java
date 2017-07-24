package ai.labs.runtime;

import ai.labs.utilities.FileUtilities;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.File;
import java.util.Map;
import java.util.concurrent.*;

/**
 * @author ginccc
 */
public class BaseRuntime implements SystemRuntime.IRuntime {
    private final String projectVersion;
    private final String CONFIG_DIR;
    private final String LOG_DIR = FileUtilities.buildPath(System.getProperty("user.dir"), "logs");
    private final String RESOURCE_DIR = FileUtilities.buildPath(System.getProperty("user.dir"), "resources");
    private final String WEB_DIR = FileUtilities.buildPath(System.getProperty("user.dir"), "web");

    private final ExecutorService executorService;
    private final String projectName;

    private boolean isInit = false;

    private Logger log;

    @Inject
    public BaseRuntime(ExecutorService executorService,
                       @Named("systemRuntime.projectName") String projectName,
                       @Named("systemRuntime.projectVersion") String projectVersion,
                       @Named("systemRuntime.configDir") String configDir) {
        this.executorService = executorService;
        this.projectName = projectName;
        this.projectVersion = projectVersion;
        CONFIG_DIR = FileUtilities.buildPath(System.getProperty("user.dir"), configDir);
    }

    public void init() {
        if (!isInit) {
            if (projectName == null || projectName.isEmpty()) {
                throw new RuntimeException("ProjectName cannot be null nor empty.");
            }
            initProjectName(projectName);
            initLogging();
            logVersion();
            initExecutorServiceShutdownHook();
            SystemRuntime.setRuntime(this);
            isInit = true;
        } else {
            throw new RuntimeException("SystemRuntime has already been initialized!");
        }
    }

    private void initProjectName(String projectName) {
        System.setProperty("systemRuntime.projectName", lowerCaseFirstLetter(projectName));
    }

    @Override
    public void logVersion() {
        log.info(projectName + " v" + getVersion());
    }

    protected void initLogging() {
        System.setProperty("systemRuntime.logDir", getLogDir());
        Configurator.initialize("Logging", null, new File(getConfigDir() + lowerCaseFirstLetter(projectName) + ".log4j.xml").toURI());
        log = LoggerFactory.getLogger(BaseRuntime.class);
    }

    @Override
    public String getVersion() {
        return projectVersion;
    }

    @Override
    public String getConfigDir() {
        return CONFIG_DIR;
    }

    @Override
    public String getLogDir() {
        return LOG_DIR;
    }

    @Override
    public String getResourceDir() {
        return RESOURCE_DIR;
    }

    @Override
    public String getWebDir() {
        return WEB_DIR;
    }

    private static String lowerCaseFirstLetter(String value) {
        char chars[] = value.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    @Override
    public <T> Future<T> submitCallable(final Callable<T> callable, final Map<Object, Object> threadBindings) {
        return submitCallable(callable, new IgnoredCallableResult<>(), threadBindings);
    }

    @Override
    public <T> Future<T> submitCallable(final Callable<T> callable, final IFinishedExecution<T> callback, final Map<Object, Object> threadBindings) {
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
                throw new Exception("Error while executing callable.", t);
            } finally {
                ThreadContext.remove();
            }
        });
    }

    @Override
    public void submitRunable(final Runnable runnable, final IFinishedExecution<?> callback, final Map<Object, Object> threadBindings) {
        getExecutorService().submit(() -> {
            try {
                if (threadBindings != null) {
                    ThreadContext.setResources(threadBindings);
                }

                runnable.run();
                callback.onComplete(null);
            } catch (Throwable t) {
                log.error(t.getLocalizedMessage(), t);
                callback.onFailure(t);
                throw new RuntimeException("Error while executing callable.", t);
            } finally {
                ThreadContext.remove();
            }
        });
    }

    private void initExecutorServiceShutdownHook() {
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
