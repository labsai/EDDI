package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationLog;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ConversationLogGenerator {
    private static final String KEY_ROLE_USER = "user";
    private static final String KEY_ROLE_ASSISTANT = "assistant";
    private static final String OUTPUT_KEY_INPUT = "input";
    private static final String OUTPUT_KEY_OUTPUT = "output";
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
        return generate(-1, true);
    }

    public ConversationLog generate(int logSize) {
        return generate(logSize, true);
    }

    public ConversationLog generate(int logSize, boolean includeFirstBotMessage) {
        if (conversationMemory == null && memorySnapshot == null) {
            throw new IllegalStateException("ConversationMemory was null. " +
                    "You need to either set IConversationMemory or ConversationMemorySnapshot");
        }

        var conversationLog = new ConversationLog();
        if (logSize != 0) {
            var conversationOutputs = conversationMemory != null ?
                    conversationMemory.getConversationOutputs() : memorySnapshot.getConversationOutputs();

            var startIndex = 0;
            if (logSize > 0) {
                startIndex = conversationOutputs.size() > logSize ? conversationOutputs.size() - logSize : 0;
            }

            for (var index = startIndex; index < conversationOutputs.size(); index++) {
                var conversationOutput = conversationOutputs.get(index);
                var input = conversationOutput.get(OUTPUT_KEY_INPUT, String.class);
                if (input != null) {
                    conversationLog.getMessages().add(new ConversationLog.ConversationPart(KEY_ROLE_USER, input));
                }

                var output = conversationOutput.get(OUTPUT_KEY_OUTPUT);
                if (output instanceof List<?> outputList) {
                    if (!outputList.isEmpty()) {
                        if (outputList.getFirst() instanceof Map) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> mapList = (List<Map<String, Object>>) outputList;
                            var joinedOutput = mapList.stream()
                                    .map(item -> item.get(KEY_TEXT).toString())
                                    .collect(Collectors.joining(" "));
                            conversationLog.getMessages().add(new ConversationLog.ConversationPart(KEY_ROLE_ASSISTANT, joinedOutput));
                        } else if (outputList.getFirst() instanceof TextOutputItem) {
                            @SuppressWarnings("unchecked")
                            List<TextOutputItem> textOutputList = (List<TextOutputItem>) outputList;
                            var joinedOutput = textOutputList.stream()
                                    .map(TextOutputItem::getText)
                                    .collect(Collectors.joining(" "));
                            conversationLog.getMessages().add(new ConversationLog.ConversationPart(KEY_ROLE_ASSISTANT, joinedOutput));
                        }
                    }
                }
            }

            if (!includeFirstBotMessage && !conversationLog.getMessages().isEmpty()) {
                conversationLog.getMessages().removeFirst();
            }
        }

        return conversationLog;
    }
}
