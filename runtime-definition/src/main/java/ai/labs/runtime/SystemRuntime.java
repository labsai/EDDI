package ai.labs.runtime;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

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

