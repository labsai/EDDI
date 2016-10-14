package io.sls.core.runtime;

import io.sls.core.lifecycle.LifecycleException;
import io.sls.memory.IConversationMemory;

/**
 * User: michael
 * Date: 07.01.2012
 * Time: 23:22:18
 */
public interface IConversation {
    IConversationMemory getConversationMemory();

    void init() throws LifecycleException;

    interface IConversationHistory {

        void undoLastStatement();

        void redoLastStatement();
    }

    boolean isInProgress();

    boolean isEnded();

    void endConversation();

    void say(final String message) throws LifecycleException, ConversationNotReadyException;

    interface IConversationOutputRenderer {
        void renderOutput(Object output);
    }

    class ConversationNotReadyException extends Exception {
        public ConversationNotReadyException(String message) {
            super(message);
        }
    }
}
