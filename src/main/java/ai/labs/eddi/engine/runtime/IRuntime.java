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

    /**
     * Marks a submission as a conversation turn: a callback of this type tells
     * {@link #submitCallable(Callable, IFinishedExecution, Map)} to run the work on
     * a virtual thread instead of the bounded worker pool. A turn's outer task
     * blocks for the whole pipeline, LLM call included, so on a pooled platform
     * thread every in-flight turn holds one of the pool's ~200 threads and the pool
     * — shared with blocking REST endpoints — becomes the node's turn ceiling.
     * <p>
     * A marker on the callback rather than a new method or a wrapped callable: the
     * coordinators already pass a callback, mocks of this interface keep working,
     * and the submitted callable keeps its identity. Bounding how many turns run at
     * once is the admission limit's job
     * ({@code eddi.conversation.max-in-flight-turns}), not the executor's.
     */
    interface ITurnExecution<T> extends IFinishedExecution<T> {
    }
}
