/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import java.util.concurrent.Callable;

/**
 * A conversation task that wants to be told when the coordinator DROPPED it
 * without ever calling it.
 *
 * <p>
 * A queued turn normally ends in exactly one place: the {@code finally} of its
 * own body. That is what releases the in-flight metrics reference and completes
 * the caller's response handler. A task the coordinator discards — because
 * handing it to the runtime was rejected and there is no caller left to
 * propagate to (see {@code InMemoryConversationCoordinator#submitNext}) — never
 * reaches that {@code finally}: its body is never invoked at all. Without this
 * hook the reference leaks for the JVM's lifetime and the HTTP caller waits for
 * a response that can never arrive.
 * </p>
 *
 * <p>
 * Implementations must be cheap, must not throw, and must not block: the hook
 * is invoked from a completion callback thread while the coordinator is
 * draining a conversation queue. Coordinators invoke it at most once per task,
 * and only when the task is guaranteed never to run.
 * </p>
 *
 * @author ginccc
 */
public interface IDiscardableTask extends Callable<Void> {

    /**
     * Invoked instead of {@link #call()} when the task was dropped before it ever
     * ran.
     *
     * @param cause
     *            why the task could not be scheduled
     */
    void onDiscarded(Throwable cause);
}
