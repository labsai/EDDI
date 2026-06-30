/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.model;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe control token shared between an execution loop and external
 * callers (cancel/pause). Created at execution start, removed in the finally
 * block.
 * <p>
 * <strong>In-flight scope only</strong>: does NOT survive a pause gap.
 * Cancel-of-paused uses persisted state, not this token.
 */
public class DiscussionControlToken {

    private final AtomicReference<ControlSignal> signal = new AtomicReference<>(ControlSignal.CONTINUE);

    /** For IMMEDIATE cancel — optional handle to the currently-blocking future. */
    private volatile CompletableFuture<?> activeFuture;

    /** Set the control signal. Thread-safe, idempotent. */
    public void setSignal(ControlSignal signal) {
        this.signal.set(signal);
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

    /** Convenience: is the PAUSE signal active? */
    public boolean isPaused() {
        return signal.get() == ControlSignal.PAUSE;
    }

    /** Convenience: should the loop stop (cancel or pause)? */
    public boolean shouldStop() {
        return isCancelled() || isPaused();
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
