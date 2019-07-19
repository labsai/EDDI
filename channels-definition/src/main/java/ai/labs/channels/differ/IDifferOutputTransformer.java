package ai.labs.channels.differ;

import ai.labs.channels.differ.model.CommandInfo;
import ai.labs.memory.model.SimpleConversationMemorySnapshot;

import java.util.List;

public interface IDifferOutputTransformer {
    List<CommandInfo> convertBotOutputToMessageCreateCommands(
            SimpleConversationMemorySnapshot memorySnapshot, String botUserId, String conversationId);
}
