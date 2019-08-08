package ai.labs.channels.differ.utilities;

import ai.labs.memory.model.ConversationOutput;
import ai.labs.output.model.QuickReply;

import java.util.*;
import java.util.stream.Collectors;

import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;

public class DifferUtilities {
    private static final int DELAY_PER_WORD = 110;
    private static final String KEY_OUTPUT = "output";
    private static final String KEY_TYPE = "type";
    private static final String INPUT_TYPE_TEXT = "text";

    public static Date getCurrentTime() {
        return new Date(System.currentTimeMillis());
    }

    public static String generateUUID() {
        return UUID.randomUUID().toString();
    }

    /**
     * @param text words or sentences articulated by the bot
     * @return delay in Millis
     */
    public static long calculateTypingDelay(String text) {
        var delay = text.split(" ").length * DELAY_PER_WORD;

        return Math.min(Math.max(300, delay), 1000);
    }

    public static long calculateSentAt(long receivedEventCreatedAt) {
        long currentSystemTime = System.currentTimeMillis();
        return receivedEventCreatedAt > currentSystemTime ? receivedEventCreatedAt + 1 : currentSystemTime;
    }

    public static List<String> getOutputParts(List<ConversationOutput> conversationOutputs) {
        List<Object> outputBubbles = conversationOutputs.stream().
                filter(conversationOutput -> conversationOutput.get(KEY_OUTPUT) != null).
                flatMap(conversationOutput -> ((List<Object>) conversationOutput.get(KEY_OUTPUT)).stream()).
                collect(Collectors.toList());

        return outputBubbles.stream().map(output -> {
            if (output instanceof Map) {
                Map<String, Object> outputMap = (Map<String, Object>) output;
                String type = outputMap.get(KEY_TYPE).toString();
                if (INPUT_TYPE_TEXT.equals(type)) {
                    return outputMap.get(INPUT_TYPE_TEXT).toString();
                }
            }

            return output.toString();
        }).collect(Collectors.toList());
    }

    public static List<QuickReply> getQuickReplies(List<ConversationOutput> conversationOutputs) {
        var quickReplyActions = new LinkedList<QuickReply>();
        conversationOutputs.stream().
                filter(conversationOutput -> !isNullOrEmpty(conversationOutput.get("quickReplies"))).
                map(conversationOutput -> {
                    List quickRepliesObj = (List) conversationOutput.get("quickReplies");
                    if (quickRepliesObj.get(0) instanceof QuickReply) {
                        return quickRepliesObj;
                    } else {
                        return ((List<Map>) quickRepliesObj).stream().map(map -> {
                            Object isDefault = map.get("isDefault");
                            return new QuickReply(
                                    map.get("value").toString(),
                                    map.get("expressions").toString(),
                                    isDefault != null && Boolean.parseBoolean(isDefault.toString()));
                        }).collect(Collectors.toList());
                    }
                }).forEach(quickReplyActions::addAll);

        return quickReplyActions;
    }
}
