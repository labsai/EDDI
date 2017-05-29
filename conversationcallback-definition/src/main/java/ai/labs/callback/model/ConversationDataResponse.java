package ai.labs.callback.model;

import ai.labs.memory.model.ConversationMemorySnapshot;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * Created by rpi on 08.02.2017.
 */
@Getter
@Setter
public class ConversationDataResponse {
    private long httpCode;
    private Map<String, String> header;
    private ConversationMemorySnapshot conversationMemorySnapshot;
}
