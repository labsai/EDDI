/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionRequest;
import ai.labs.eddi.engine.memory.IConversationMemory;

/**
 * A lifecycle task that can revise an answer it gave earlier in the turn, when
 * a human rejected it with a note ({@code hitlConfig.onRejection: REVISE}).
 * Implemented by the LLM task.
 *
 * @since 6.6.0
 */
public interface IAnswerRevisingTask {

    /**
     * Revises the answer the request's candidate describes and stores the revision
     * in its place.
     *
     * @return {@code true} when the revision was stored; {@code false} when it
     *         could not be produced (the configuration changed, the spend limit is
     *         reached, the model failed) — nothing was written then, and the caller
     *         applies the rejection as before
     * @throws LifecycleException
     *             only for a failure after the revision was stored (a task that ran
     *             after it in the same configuration)
     */
    boolean reviseAnswer(IConversationMemory memory, Object component, HitlRevisionRequest request) throws LifecycleException;
}
