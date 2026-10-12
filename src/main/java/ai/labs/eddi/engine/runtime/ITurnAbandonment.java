/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

/**
 * Stops a conversation turn that is running on this node when nobody wants its
 * outcome any more and no caller asked for it — the agent-timeout watchdog, or
 * a coordinator that lost the turn's ownership (a cluster lease that expired or
 * moved to another node).
 * <p>
 * The turn is not interrupted. Its memory's cooperative-cancel flag is raised,
 * which the pipeline checks before every lifecycle task and once more before it
 * commits long-term side effects, and which no lower layer can clear (unlike a
 * thread interrupt, which a client library can swallow). A turn that ends after
 * the signal persists nothing of its own outcome and is recorded as
 * {@code EXECUTION_INTERRUPTED}.
 * <p>
 * Inject it rather than the conversation service: implemented by
 * {@code ConversationService}, which owns the registry of in-flight turns, but
 * callers outside {@code engine.internal} (the coordinators in
 * {@code engine.runtime.internal}) need only this. Idempotent and thread-safe.
 */
public interface ITurnAbandonment {

    /**
     * Abandons the turn of {@code conversationId} that is running on this node, if
     * there is one.
     *
     * @return {@code true} when a running turn was signalled, {@code false} when
     *         none of that conversation is in flight here
     */
    boolean abandonInFlightTurn(String conversationId);
}
