package ai.labs.runtime;

import java.util.Map;
import java.util.concurrent.*;

/**
 * @author ginccc
 */
public final class SystemRuntime {
    private static IRuntime runtime;

    public static IRuntime getRuntime() {
        return runtime;
    }

    static void setRuntime(IRuntime runtime) {
        SystemRuntime.runtime = runtime;
    }

    public interface IRuntime {
        void init();

        String getVersion();

        String getConfigDir();

        String getLogDir();

        ExecutorService getExecutorService();

        void logVersion();

        <T> ScheduledFuture<T> submitScheduledCallable(Callable<T> callable,
                                                       long delay, TimeUnit timeUnit,
                                                       Map<Object, Object> threadBindings);

        <T> Future<T> submitCallable(Callable<T> callable, Map<Object, Object> threadBindings);

        <T> Future<T> submitCallable(Callable<T> callable,
                                     IFinishedExecution<T> callableCompleted,
                                     Map<Object, Object> threadBindings);

        interface IFinishedExecution<T> {
            void onComplete(T result);

            void onFailure(Throwable t);
        }
    }
}

