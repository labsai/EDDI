package ai.labs.callback.model;

import ai.labs.memory.model.ConversationMemorySnapshot;
import lombok.Getter;
import lombok.Setter;

/**
 * Created by rpi on 08.02.2017.
 */
@Getter
@Setter
public class ConversationDataRequest {
    private ConversationMemorySnapshot conversationMemorySnapshot;
}
