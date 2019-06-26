package ai.labs.lifecycle;

public class ConversationStopException extends Exception {
    public ConversationStopException() {
        super("Conversation Stopped by user action");
    }
}
