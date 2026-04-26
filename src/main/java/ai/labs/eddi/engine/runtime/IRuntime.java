/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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

    ScheduledExecutorService getScheduledExecutorService();

    void logVersion();

    <T> Future<T> submitCallable(Callable<T> callable, Map<Object, Object> threadBindings);

    <T> Future<T> submitCallable(Callable<T> callable, IFinishedExecution<T> callableCompleted, Map<Object, Object> threadBindings);

    interface IFinishedExecution<T> {
        void onComplete(T result);

        void onFailure(Throwable t);
    }
}
