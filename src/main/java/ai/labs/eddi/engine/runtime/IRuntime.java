package ai.labs.eddi.engine.runtime;

import java.util.Map;
import java.util.concurrent.*;

/**
 * @author ginccc
 */
public interface IRuntime {
    void init();

    String getVersion();

    ExecutorService getExecutorService();

    void logVersion();

    <T> Future<T> submitScheduledCallable(Callable<T> callable,
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

