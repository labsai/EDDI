/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;

import java.util.Map;

/**
 * Asks the model that produced a rejected answer for a new one. Supplied by the
 * LLM task for the execution branch that produced the answer (plain chat, the
 * tool loop's final call, the winning cascade step, a resumed tool loop), so a
 * reprompt runs on the same model, inside the same token and cost accounting,
 * and never re-runs a tool.
 */
@FunctionalInterface
public interface Reprompter {

    /**
     * One more model call: the original request, the rejected answer as the
     * assistant's turn, then {@code correctiveMessage} as a user turn.
     *
     * @return the new answer, or null when no further attempt may start (the turn
     *         is out of time)
     * @throws LifecycleException
     *             when the model call fails — propagated unchanged, so the task's
     *             {@code onError} policy applies
     */
    Attempt reprompt(String rejectedReply, String correctiveMessage) throws LifecycleException;

    /**
     * A reprompt's answer, its response metadata ({@code tokenUsage}) and what the
     * call cost at the model's configured prices (0 when unpriced) — reported to
     * the spend checks, so the next paid call of the task is checked with it.
     */
    record Attempt(String text, Map<String, Object> metadata, double costUsd) {

        /** An answer whose cost is unknown (0). */
        public Attempt(String text, Map<String, Object> metadata) {
            this(text, metadata, 0.0);
        }
    }
}
