package ai.labs.callback.model;

import ai.labs.memory.model.ConversationMemorySnapshot;
import lombok.Getter;
import lombok.Setter;

/**
 * Created by rpi on 31.08.2017.
 */
@Getter
@Setter

public class ConversationDataResponseHolder {
    private ConversationMemorySnapshot conversationMemorySnapshot;
}

