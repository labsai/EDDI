package ai.labs.channels.differ;

import ai.labs.channels.differ.model.CommandInfo;
import ai.labs.memory.model.ConversationOutput;

import java.util.List;

public interface IDifferOutputTransformer {
    List<CommandInfo> convertBotOutputToMessageCreateCommands(
            List<ConversationOutput> conversationOutputs, String botUserId, String conversationId, long timeOfLastMessageReceived);
}
