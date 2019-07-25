package ai.labs.channels.differ;

import ai.labs.channels.differ.model.*;
import ai.labs.memory.model.ConversationOutput;
import ai.labs.output.model.QuickReply;
import lombok.extern.slf4j.Slf4j;

import javax.ws.rs.core.MediaType;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static ai.labs.channels.differ.utilities.DifferUtilities.*;
import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;

@Slf4j
public class DifferOutputTransformer implements IDifferOutputTransformer {
    private static final String CONVERSATION_EXCHANGE = "conversation";
    private static final String MESSAGE_CREATE = "message.create";
    private static final String MESSAGE_CREATE_ROUTING_KEY = "message.create";
    private static final String ACTION_CREATE_EXCHANGE = "message-actions";
    private static final String ACTION_CREATE_ROUTING_KEY = "message-actions.createMany";
    private static final String INPUT_TYPE_TEXT = "text";
    private static final String KEY_OUTPUT = "output";
    private static final String KEY_TYPE = "type";

    @Override
    public List<CommandInfo> convertBotOutputToMessageCreateCommands(
            List<ConversationOutput> conversationOutputs, String botUserId, String conversationId, long timeOfLastMessageReceived) {

        List<String> outputParts = getOutputParts(conversationOutputs);
        List<CommandInfo> commandInfos = IntStream
                .range(0, outputParts.size())
                .mapToObj(sequenceNumber -> {
                    var outputPart = outputParts.get(sequenceNumber);

                    var eventPart = new Event.Part(generateUUID(), outputPart, MediaType.TEXT_PLAIN, "text");
                    return new CommandInfo(CONVERSATION_EXCHANGE, MESSAGE_CREATE_ROUTING_KEY,
                            createMessageCreateCommand(conversationId, botUserId, List.of(eventPart)),
                            calculateTypingDelay(outputPart), timeOfLastMessageReceived, sequenceNumber + 1);
                })
                .collect(Collectors.toList());

        var quickReplies = getQuickReplies(conversationOutputs);
        if (!quickReplies.isEmpty()) {
            if (commandInfos.isEmpty()) {
                log.error("Skipped Creating Actions (Quick Replies), since there was no message to attach it to. " +
                        "(conversationId={},botUserId={})", conversationId, botUserId);
                return Collections.emptyList();
            }

            var lastCommandInfo = (MessageCreateCommand) commandInfos.get(commandInfos.size() - 1).getCommand();
            var messageId = lastCommandInfo.getPayload().getId();
            var actionCreateCommand = createActionCreateCommand(conversationId, messageId, botUserId, quickReplies);
            commandInfos.add(new CommandInfo(
                    ACTION_CREATE_EXCHANGE, ACTION_CREATE_ROUTING_KEY,
                    actionCreateCommand, 0, timeOfLastMessageReceived, commandInfos.size() + 1));
        }

        return commandInfos;
    }

    private static List<String> getOutputParts(List<ConversationOutput> conversationOutputs) {
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

    private static MessageCreateCommand createMessageCreateCommand(String conversationId, String botUserId, List<Event.Part> parts) {
        return new MessageCreateCommand(
                new Command.AuthContext(botUserId), generateUUID(), MESSAGE_CREATE,
                new MessageCreateCommand.Payload(generateUUID(), conversationId, botUserId, INPUT_TYPE_TEXT, parts));
    }

    private static List<QuickReply> getQuickReplies(List<ConversationOutput> conversationOutputs) {
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

    private static ActionsCreateCommand createActionCreateCommand(
            String conversationId, String messageId, String botUserId, List<QuickReply> quickReplies) {

        return new ActionsCreateCommand(new Command.AuthContext(botUserId), generateUUID(), ACTION_CREATE_ROUTING_KEY, getCurrentTime(),
                new ActionsCreateCommand.Payload(convertQuickRepliesToActions(conversationId, messageId, quickReplies)));
    }

    private static List<ActionsCreateCommand.Payload.Action> convertQuickRepliesToActions(
            String conversationId, String messageId, List<QuickReply> quickReplies) {

        return quickReplies.stream().map(quickReply -> {
                    Boolean isDefault = quickReply.isDefault();
                    return new ActionsCreateCommand.Payload.Action(
                            generateUUID(), conversationId, messageId, isDefault, quickReply.getValue());
                }
        ).collect(Collectors.toList());
    }
}
