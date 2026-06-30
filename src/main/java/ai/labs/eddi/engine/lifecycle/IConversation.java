/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.model.Context;

import java.util.Map;

/**
 * @author ginccc
 */
public interface IConversation {
    String CONVERSATION_START = "CONVERSATION_START";
    String CONVERSATION_END = "CONVERSATION_END";
    String STOP_CONVERSATION = "STOP_CONVERSATION";
    String PAUSE_CONVERSATION = "PAUSE_CONVERSATION";

    IConversationMemory getConversationMemory();

    void init(Map<String, Context> context) throws LifecycleException;

    boolean isEnded();

    void endConversation();

    void rerun(Map<String, Context> contexts) throws ConversationNotReadyException, LifecycleException;

    void say(final String message, Map<String, Context> contexts) throws LifecycleException, ConversationNotReadyException;

    /**
     * Resume a paused conversation after a human decision. Re-enters the pipeline
     * at the task that requested HITL approval.
     *
     * @param decision
     *            the human verdict (APPROVED / REJECTED)
     * @param contexts
     *            additional context variables for the resumed pipeline
     * @throws LifecycleException
     *             on pipeline errors
     * @throws ConversationNotReadyException
     *             if the conversation is not in a resumable state
     */
    void resume(HitlDecision decision, Map<String, Context> contexts) throws LifecycleException, ConversationNotReadyException;

    interface IConversationOutputRenderer {
        void renderOutput(IConversationMemory conversationMemory);
    }

    class ConversationNotReadyException extends Exception {
        public ConversationNotReadyException(String message) {
            super(message);
        }
    }
}
