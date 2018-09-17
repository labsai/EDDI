package ai.labs.memory;

import java.util.Map;

public interface IMemoryItemConverter {
    Map<String, Object> convertMemoryItems(IConversationMemory memory);
}
