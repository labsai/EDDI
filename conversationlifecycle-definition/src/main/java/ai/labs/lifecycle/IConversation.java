package ai.labs.lifecycle;

import io.sls.memory.IConversationMemory;

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
