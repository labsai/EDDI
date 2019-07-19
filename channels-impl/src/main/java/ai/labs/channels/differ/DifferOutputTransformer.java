package ai.labs.channels.differ;

import ai.labs.channels.differ.model.*;
import ai.labs.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.output.model.QuickReply;
import lombok.extern.slf4j.Slf4j;

import javax.ws.rs.core.MediaType;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    @Override
    public List<CommandInfo> convertBotOutputToMessageCreateCommands(
            SimpleConversationMemorySnapshot memorySnapshot, String botUserId, String conversationId) {

        List<CommandInfo> commandInfos = getOutputParts(memorySnapshot).stream().map(
                outputPart -> {
                    var eventPart = new Event.Part(generateUUID(), outputPart, MediaType.TEXT_PLAIN, "text");
                    return new CommandInfo(CONVERSATION_EXCHANGE, MESSAGE_CREATE_ROUTING_KEY,
                            createMessageCreateCommand(conversationId, botUserId, List.of(eventPart)),
                            calculateTypingDelay(outputPart));
                }).collect(Collectors.toList());

        var quickReplies = getQuickReplies(memorySnapshot);
        if (!quickReplies.isEmpty()) {
            if (commandInfos.isEmpty()) {
                log.error("Skipped Creating Actions (Quick Replies), since there was no message to attach it to. " +
                        "(conversationId={},botUserId={})", conversationId, botUserId);
                return commandInfos;
            }

            var lastCommandInfo = (MessageCreateCommand) commandInfos.get(commandInfos.size() - 1).getCommand();
            var messageId = lastCommandInfo.getPayload().getId();
            var actionCreateCommand = createActionCreateCommand(conversationId, messageId, botUserId, quickReplies);
            commandInfos.add(new CommandInfo(
                    ACTION_CREATE_EXCHANGE, ACTION_CREATE_ROUTING_KEY, actionCreateCommand, 0));
        }

        return commandInfos;
    }

    private static List<String> getOutputParts(SimpleConversationMemorySnapshot memorySnapshot) {
        var outputParts = new LinkedList<String>();
        List<List> outputBubbles = memorySnapshot.getConversationOutputs().stream().
                filter(conversationOutput -> conversationOutput.get("output") != null).
                map(conversationOutput -> (List) conversationOutput.get("output")).collect(Collectors.toList());

        if (outputBubbles.isEmpty()) {
            return Collections.emptyList();
        } else {
            outputBubbles.forEach(outputs ->
            {
                for (Object output : outputs) {
                    if (output instanceof String) {
                        outputParts.add(output.toString());
                    } else if (output instanceof Map) {
                        Map<String, Object> outputMap = (Map<String, Object>) output;
                        String type = outputMap.get("type").toString();
                        if (INPUT_TYPE_TEXT.equals(type)) {
                            outputParts.add(outputMap.get(INPUT_TYPE_TEXT).toString());
                        }
                    }
                }
            });
        }

        return outputParts;
    }

    private static MessageCreateCommand createMessageCreateCommand(String conversationId, String botUserId, List<Event.Part> parts) {
        return new MessageCreateCommand(
                new Command.AuthContext(botUserId), generateUUID(), MESSAGE_CREATE, getCurrentTime(),
                new MessageCreateCommand.Payload(generateUUID(), conversationId, botUserId, INPUT_TYPE_TEXT, parts));
    }

    private static List<QuickReply> getQuickReplies(SimpleConversationMemorySnapshot memorySnapshot) {
        var quickReplyActions = new LinkedList<QuickReply>();
        memorySnapshot.getConversationOutputs().stream().
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
                                    isDefault != null ? Boolean.valueOf(isDefault.toString()) : false);
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
