/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.shared;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Tells whoever is listening on the current thread that
 * {@link RetryConfiguration#executeWithRetry} is about to repeat a failed
 * attempt — the one event of a same-model retry that leaves no other trace.
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
 * nothing bound, or outside a model call, {@link #retrying} does nothing. A
 * listener must not throw; if it does, the exception is swallowed: observing a
 * retry must never change it.
 */
public final class RetryObserver {

    private static final ThreadLocal<Consumer<FailureClass>> LISTENER = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> IN_MODEL_CALL = new ThreadLocal<>();

    private RetryObserver() {
    }

    /** Restores the listener that was bound before {@link #bind}. */
    public interface Binding extends AutoCloseable {
        @Override
        void close();
    }

    /** Binds {@code listener} to the current thread until the binding is closed. */
    public static Binding bind(Consumer<FailureClass> listener) {
        Consumer<FailureClass> previous = LISTENER.get();
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

    /** A failed attempt of class {@code failureClass} is about to be retried. */
    public static void retrying(FailureClass failureClass) {
        Consumer<FailureClass> listener = LISTENER.get();
        if (listener == null || !Boolean.TRUE.equals(IN_MODEL_CALL.get())) {
            return;
        }
        try {
            listener.accept(failureClass == null ? FailureClass.UNKNOWN : failureClass);
        } catch (RuntimeException ignored) {
            // Observing a retry must never change it.
        }
    }

    /**
     * {@code work} with the current thread's listener bound while it runs — for
     * work handed to another thread.
     */
    public static <T> Callable<T> propagate(Callable<T> work) {
        Consumer<FailureClass> listener = LISTENER.get();
        if (listener == null) {
            return work;
        }
        return () -> {
            try (Binding ignored = bind(listener)) {
                return work.call();
            }
        };
    }
}
