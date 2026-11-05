/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.model.ReviewerEditRequest;
import ai.labs.eddi.engine.memory.IConversationMemory;

/**
 * A lifecycle task whose answer an approver may replace when approving a RULE
 * pause ({@code hitlConfig.review.allowAnswerEdits}). Implemented by the LLM
 * task.
 *
 * @since 6.7.0
 */
public interface IAnswerEditingTask {

    /**
     * Screens the edit (when the request asks for it) and stores it in place of the
     * draft: the answer's output item and data keys, nothing after it. Never runs
     * {@code postResponse} or a later task, and writes no property, context or
     * action.
     *
     * @return what happened; a refused edit wrote nothing
     */
    ReviewerEditRequest.Outcome replaceAnswer(IConversationMemory memory, Object component, ReviewerEditRequest request)
            throws LifecycleException;
}
