package ai.labs.eddi.engine.lifecycle.exceptions;

public class ConversationStopException extends Exception {
    public ConversationStopException() {
        super("Conversation stopped by user action");
    }
}
