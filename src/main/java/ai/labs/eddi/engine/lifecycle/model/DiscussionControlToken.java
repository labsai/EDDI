/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.model;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Thread-safe control token shared between an execution loop and external
 * cancel callers. Created at execution start, removed in the finally block.
 * <p>
 * <strong>In-flight scope only</strong>: does NOT survive a pause gap.
 * Cancel-of-paused uses persisted state, not this token. HITL pauses are NOT
 * signalled through this token either — they are committed by the execution
 * loop itself at the phase/task gates.
 */
public class DiscussionControlToken {

    private final AtomicReference<ControlSignal> signal = new AtomicReference<>(ControlSignal.CONTINUE);

    /** For IMMEDIATE cancel — optional handle to the currently-blocking future. */
    private volatile CompletableFuture<?> activeFuture;

    /**
     * Who signalled the cancel, for the audit the running leg writes on its own
     * thread (which carries no identity of the canceller); null when unknown.
     */
    private volatile String signalledBy;

    /**
     * Called with the signal whenever a cancel lands — what reaches work the token
     * itself cannot stop: a member turn blocked in its response wait, a nested
     * discussion running under this one.
     */
    private final Set<Consumer<ControlSignal>> cancelHooks = ConcurrentHashMap.newKeySet();

    /** Set the control signal. Thread-safe, idempotent. */
    public void setSignal(ControlSignal signal) {
        this.signal.set(signal);
        fireCancelHooks(signal);
    }

    /**
     * Registers {@code hook} for every cancel signal from now on; when the token is
     * already cancelled it runs at once. Returns the handle that removes it.
     */
    public Runnable onCancel(Consumer<ControlSignal> hook) {
        cancelHooks.add(hook);
        var current = signal.get();
        if (isCancel(current)) {
            hook.accept(current);
        }
        return () -> cancelHooks.remove(hook);
    }

    private void fireCancelHooks(ControlSignal s) {
        if (!isCancel(s)) {
            return;
        }
        for (var hook : cancelHooks) {
            try {
                hook.accept(s);
            } catch (RuntimeException e) {
                // a hook must never keep the cancel from reaching the others
            }
        }
    }

    private static boolean isCancel(ControlSignal s) {
        return s == ControlSignal.CANCEL_GRACEFUL || s == ControlSignal.CANCEL_IMMEDIATE;
    }

    /**
     * Set the control signal and record who sent it.
     *
     * @param signalledBy
     *            the caller's user id, or null when there is none
     */
    public void setSignal(ControlSignal signal, String signalledBy) {
        if (signalledBy != null) {
            this.signalledBy = signalledBy;
        }
        this.signal.set(signal);
        fireCancelHooks(signal);
    }

    /** The caller that signalled the cancel, or null. */
    public String getSignalledBy() {
        return signalledBy;
    }

    /** Read the current signal. Thread-safe. */
    public ControlSignal getSignal() {
        return signal.get();
    }

    /** Convenience: is any cancel variant active? */
    public boolean isCancelled() {
        var s = signal.get();
        return s == ControlSignal.CANCEL_GRACEFUL || s == ControlSignal.CANCEL_IMMEDIATE;
    }

    public void setActiveFuture(CompletableFuture<?> f) {
        this.activeFuture = f;
    }

    public void cancelActiveFuture() {
        var f = activeFuture;
        if (f != null)
            f.cancel(true);
    }
}
