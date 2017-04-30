package ai.labs.callback.model;

import ai.labs.memory.IData;

import java.util.List;

/**
 * Created by rpi on 08.02.2017.
 */
public class ConversationDataResponse {

    private long errorcode;
    private List<IData> conversationMemory;

    public long getErrorcode() {
        return errorcode;
    }

    public void setErrorcode(long errorcode) {
        this.errorcode = errorcode;
    }

    public List<IData> getConversationMemory() {
        return conversationMemory;
    }

    public void setConversationMemory(List<IData> conversationMemory) {
        this.conversationMemory = conversationMemory;
    }
}
