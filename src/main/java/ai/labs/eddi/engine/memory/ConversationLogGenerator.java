package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationLog;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ConversationLogGenerator {
    private static final String OUTPUT_KEY_INPUT = "input";
    private static final String KEY_ROLE_USER = "user";
    private static final String OUTPUT_ROLE_OUTPUT = "output";
    private static final String KEY_ROLE_ASSISTANT = "assistant";
    private static final String KEY_TEXT = "text";

    private IConversationMemory conversationMemory;
    private ConversationMemorySnapshot memorySnapshot;

    public ConversationLogGenerator(IConversationMemory conversationMemory) {
        this.conversationMemory = conversationMemory;
    }

    public ConversationLogGenerator(ConversationMemorySnapshot memorySnapshot) {
        this.memorySnapshot = memorySnapshot;
    }

    public ConversationLog generate() {
        if(conversationMemory == null && memorySnapshot == null) {
            throw new IllegalStateException("ConversationMemory was null. " +
                    "You need to either set IConversationMemory or ConversationMemorySnapshot");
        }

        var conversationLog = new ConversationLog();
        var conversationOutputs = conversationMemory != null ?
                conversationMemory.getConversationOutputs() : memorySnapshot.getConversationOutputs();

        for (var conversationOutput : conversationOutputs) {
            var input = conversationOutput.get(OUTPUT_KEY_INPUT, String.class);
            if (input != null) {
                conversationLog.getMessages().add(new ConversationLog.ConversationPart(KEY_ROLE_USER, input));
            }

            var outputList = (List<Map<String, Object>>) conversationOutput.get(OUTPUT_ROLE_OUTPUT);
            if (outputList != null) {
                var output = outputList.
                        stream().toList().stream().
                        map(item -> item.get(KEY_TEXT).toString()).collect(Collectors.joining(" "));
                conversationLog.getMessages().add(new ConversationLog.ConversationPart(KEY_ROLE_ASSISTANT, output));
            }
        }

        return conversationLog;
    }
}
