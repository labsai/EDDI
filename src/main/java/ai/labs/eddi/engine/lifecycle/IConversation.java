package ai.labs.eddi.engine.lifecycle;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.models.Context;

import java.util.Map;

/**
 * @author ginccc
 */
public interface IConversation {
    String CONVERSATION_START = "CONVERSATION_START";
    String CONVERSATION_END = "CONVERSATION_END";
    String STOP_CONVERSATION = "STOP_CONVERSATION";

    IConversationMemory getConversationMemory();

    void init(Map<String, Context> context) throws LifecycleException;

    boolean isEnded();

    void endConversation();

    void rerun(Map<String, Context> contexts) throws ConversationNotReadyException, LifecycleException;

    void say(final String message, Map<String, Context> contexts)
            throws LifecycleException, ConversationNotReadyException;

    interface IConversationOutputRenderer {
        void renderOutput(IConversationMemory conversationMemory);
    }

    class ConversationNotReadyException extends Exception {
        public ConversationNotReadyException(String message) {
            super(message);
        }
    }
}
