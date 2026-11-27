/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Tells whoever is listening on the current thread which failed attempts
 * {@link RetryConfiguration#executeWithRetry} repeated, and whether the retry
 * loop then succeeded — the one event of a same-model retry that leaves no
 * other trace.
 * <p>
 * The retry loop is generic and knows nothing of the conversation, so the LLM
 * task binds a listener for the duration of its execution
 * ({@code LlmFailureRecords}) and carries it onto the threads a cascade step
 * runs on ({@link #propagate}). A retry is reported only <b>inside a model
 * call</b> — {@link #modelCall}, opened by {@code AgentExecutionHelper} around
 * the task's own chat calls. The side calls an LLM task makes on the same
 * thread — a cascade judge, a summarisation, a tool-response truncation, an MCP
 * call or a whole sub-agent conversation started from a tool — use the same
 * retry loop outside one, and are not attributed to the task's model. With
 * nothing bound, or outside a model call, {@link #retrying} does nothing.
 * <p>
 * Whether a retry recovered is known only when its loop ends: a retry is
 * announced before the next attempt runs, and that attempt may fail too. So the
 * retries of one loop ({@link #loop}) are held until the loop is closed and
 * then handed to the listener in one call, with the loop's outcome. A listener
 * must not throw; if it does, the exception is swallowed: observing a retry
 * must never change it.
 */
public final class RetryObserver {

    private static final ThreadLocal<Listener> LISTENER = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> IN_MODEL_CALL = new ThreadLocal<>();
    private static final ThreadLocal<Loop> LOOP = new ThreadLocal<>();

    private RetryObserver() {
    }

    /** Receives the retries of one retry loop once it has ended. */
    @FunctionalInterface
    public interface Listener {
        /**
         * @param retries
         *            the failed attempts the loop repeated, in order (never empty)
         * @param recovered
         *            {@code true} when a later attempt of the loop succeeded
         */
        void retried(List<FailureClass> retries, boolean recovered);
    }

    /** Restores the listener that was bound before {@link #bind}. */
    public interface Binding extends AutoCloseable {
        @Override
        void close();
    }

    /** Binds {@code listener} to the current thread until the binding is closed. */
    public static Binding bind(Listener listener) {
        Listener previous = LISTENER.get();
        LISTENER.set(listener);
        return () -> {
            if (previous == null) {
                LISTENER.remove();
            } else {
                LISTENER.set(previous);
            }
        };
    }

    /**
     * Marks the current thread as inside one of the task's own model calls until
     * the binding is closed.
     */
    public static Binding modelCall() {
        Boolean previous = IN_MODEL_CALL.get();
        IN_MODEL_CALL.set(Boolean.TRUE);
        return () -> {
            if (previous == null) {
                IN_MODEL_CALL.remove();
            } else {
                IN_MODEL_CALL.set(previous);
            }
        };
    }

    /**
     * Opens one retry loop on the current thread: the retries reported until it is
     * closed belong to it. Mark the loop {@link Loop#succeeded()} when an attempt
     * succeeded; closing it hands its retries to the listener. Loops nest.
     */
    public static Loop loop() {
        Loop loop = new Loop(LOOP.get());
        LOOP.set(loop);
        return loop;
    }

    /**
     * A failed attempt of class {@code failureClass} is about to be retried.
     * Outside an open {@link #loop} the retry is handed over at once, as not
     * recovered.
     */
    public static void retrying(FailureClass failureClass) {
        if (LISTENER.get() == null || !Boolean.TRUE.equals(IN_MODEL_CALL.get())) {
            return;
        }
        FailureClass cls = failureClass == null ? FailureClass.UNKNOWN : failureClass;
        Loop loop = LOOP.get();
        if (loop != null) {
            loop.retries.add(cls);
        } else {
            deliver(List.of(cls), false);
        }
    }

    /**
     * {@code work} with the current thread's listener bound while it runs — for
     * work handed to another thread.
     */
    public static <T> Callable<T> propagate(Callable<T> work) {
        Listener listener = LISTENER.get();
        if (listener == null) {
            return work;
        }
        return () -> {
            try (Binding ignored = bind(listener)) {
                return work.call();
            }
        };
    }

    private static void deliver(List<FailureClass> retries, boolean recovered) {
        Listener listener = LISTENER.get();
        if (listener == null || retries.isEmpty()) {
            return;
        }
        try {
            listener.retried(List.copyOf(retries), recovered);
        } catch (RuntimeException ignored) {
            // Observing a retry must never change it.
        }
    }

    /** One retry loop, opened by {@link #loop}. */
    public static final class Loop implements AutoCloseable {

        private final Loop previous;
        private final List<FailureClass> retries = new ArrayList<>();
        private boolean succeeded;

        private Loop(Loop previous) {
            this.previous = previous;
        }

        /** An attempt of this loop succeeded: its retries recovered. */
        public void succeeded() {
            succeeded = true;
        }

        /** Ends the loop and hands its retries, with its outcome, to the listener. */
        @Override
        public void close() {
            if (previous == null) {
                LOOP.remove();
            } else {
                LOOP.set(previous);
            }
            deliver(retries, succeeded);
        }
    }
}
