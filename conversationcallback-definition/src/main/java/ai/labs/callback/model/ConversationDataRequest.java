package ai.labs.callback.model;

import ai.labs.memory.IConversationMemory;

/**
 * Created by rpi on 08.02.2017.
 */
public class ConversationDataRequest {

    private IConversationMemory conversationMemory;

    public IConversationMemory getConversationMemory() {
        return conversationMemory;
    }

    public void setConversationMemory(IConversationMemory conversationMemory) {
        this.conversationMemory = conversationMemory;
    }
}
