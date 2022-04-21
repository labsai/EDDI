package ai.labs.eddi.engine.memory;

import java.util.Map;

public interface IMemoryItemConverter {
    Map<String, Object> convert(IConversationMemory memory);
}
