package ai.labs.templateengine;

import ai.labs.memory.IConversationMemory;

import java.util.Map;

public interface IMemoryTemplateConverter {
    Map<String, Object> convertMemoryForTemplating(IConversationMemory memory);
}
