package ai.labs.lifecycle;

import ai.labs.lifecycle.model.Context;
import ai.labs.memory.IConversationMemory;

import java.util.List;

/**
 * @author ginccc
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

    void say(final String message, List<Context> contexts) throws LifecycleException, ConversationNotReadyException;

    interface IConversationOutputRenderer {
        void renderOutput(IConversationMemory.IConversationStep conversationStep);
    }

    class ConversationNotReadyException extends Exception {
        public ConversationNotReadyException(String message) {
            super(message);
        }
    }
}
