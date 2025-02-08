package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationLog;
import ai.labs.eddi.engine.memory.model.ConversationLog.ConversationPart;
import ai.labs.eddi.engine.memory.model.ConversationLog.ConversationPart.Content;
import ai.labs.eddi.engine.memory.model.ConversationLog.ConversationPart.ContentType;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ai.labs.eddi.engine.memory.model.ConversationLog.ConversationPart.ContentType.*;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

public class ConversationLogGenerator {
    private static final String KEY_ROLE_USER = "user";
    private static final String KEY_ROLE_ASSISTANT = "assistant";
    private static final Object OUTPUT_KEY_CONTEXT = "context";
    private static final String OUTPUT_KEY_INPUT = "input";
    private static final String OUTPUT_KEY_OUTPUT = "output";
    private static final String KEY_INPUT_FILES = "inputFiles";
    private static final String KEY_TEXT = "text";
    private static final String KEY_TYPE = "type";
    private static final String KEY_URL = "url";

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
                var context = conversationOutput.get(OUTPUT_KEY_CONTEXT, Map.class);
                var contentList = new LinkedList<Content>();
                if (!isNullOrEmpty(context) &&
                        context.get(KEY_INPUT_FILES) instanceof List &&
                        ((List<?>) context.get(KEY_INPUT_FILES)).getFirst() instanceof Map) {

                    @SuppressWarnings("unchecked")
                    var inputFiles = (List<Map<String, String>>) context.get(KEY_INPUT_FILES);
                    if (inputFiles != null) {
                        inputFiles.forEach(file -> {
                            var contentType = getContentType(file.get(KEY_TYPE));
                            var fileUrl = file.get(KEY_URL);
                            contentList.add(new Content(contentType, fileUrl));
                        });
                    }
                }

                if (input != null) {
                    var inputText = new Content();
                    inputText.setType(text);
                    inputText.setValue(input);
                    var inputs = new ArrayList<>(contentList);
                    inputs.add(inputText);
                    conversationLog.getMessages().add(new ConversationPart(KEY_ROLE_USER, inputs));
                }

                var output = conversationOutput.get(OUTPUT_KEY_OUTPUT);
                if (output instanceof List<?> outputList) {
                    if (!outputList.isEmpty()) {
                        var outputContentList = new LinkedList<Content>();
                        var content = new Content();
                        outputContentList.add(content);
                        if (outputList.getFirst() instanceof Map) {
                            @SuppressWarnings("unchecked")
                            var mapList = (List<Map<String, Object>>) outputList;
                            var joinedOutput = mapList.stream()
                                    .map(item -> item.get(KEY_TEXT).toString())
                                    .collect(Collectors.joining(" "));
                            content.setType(text);
                            content.setValue(joinedOutput);
                            conversationLog.getMessages().add(
                                    new ConversationPart(KEY_ROLE_ASSISTANT, outputContentList));

                        } else if (outputList.getFirst() instanceof TextOutputItem) {
                            @SuppressWarnings("unchecked")
                            var textOutputList = (List<TextOutputItem>) outputList;
                            var joinedOutput = textOutputList.stream()
                                    .map(TextOutputItem::getText)
                                    .collect(Collectors.joining(" "));
                            content.setType(text);
                            content.setValue(joinedOutput);
                            conversationLog.getMessages().add(
                                    new ConversationPart(KEY_ROLE_ASSISTANT, outputContentList));
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

    private static ContentType getContentType(String type) {
        return switch (type) {
            case "pdf" -> pdf;
            case "image" -> image;
            case "video" -> video;
            case "audio" -> audio;
            case "text" -> text;
            default -> throw new IllegalArgumentException("Unknown content type: " + type);
        };
    }
}
