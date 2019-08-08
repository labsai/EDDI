package ai.labs.channels.differ;

import ai.labs.channels.differ.model.CommandInfo;
import ai.labs.channels.differ.model.commands.Command;
import ai.labs.channels.differ.model.commands.CreateActionsCommand;
import ai.labs.channels.differ.model.commands.CreateMessageCommand;
import ai.labs.channels.differ.model.events.Event;
import ai.labs.memory.model.ConversationOutput;
import ai.labs.output.model.QuickReply;
import lombok.extern.slf4j.Slf4j;

import javax.ws.rs.core.MediaType;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static ai.labs.channels.differ.model.commands.CreateActionsCommand.CREATE_ACTIONS_EXCHANGE;
import static ai.labs.channels.differ.model.commands.CreateActionsCommand.CREATE_ACTIONS_ROUTING_KEY;
import static ai.labs.channels.differ.model.commands.CreateConversationCommand.CONVERSATION_EXCHANGE;
import static ai.labs.channels.differ.model.commands.CreateMessageCommand.CREATE_MESSAGE_ROUTING_KEY;
import static ai.labs.channels.differ.model.commands.CreateMessageCommand.Payload;
import static ai.labs.channels.differ.utilities.DifferUtilities.*;

@Slf4j
public class DifferOutputTransformer implements IDifferOutputTransformer {
    static final String INPUT_TYPE_TEXT = "text";

    @Override
    public List<CommandInfo> convertBotOutputToMessageCreateCommands(
            List<ConversationOutput> conversationOutputs, String botUserId, String conversationId, long timeOfLastMessageReceived) {

        List<String> outputParts = getOutputParts(conversationOutputs);
        List<CommandInfo> commandInfos = IntStream
                .range(0, outputParts.size())
                .mapToObj(sequenceNumber -> {
                    var outputPart = outputParts.get(sequenceNumber);

                    var eventPart = new Event.Part(outputPart, MediaType.TEXT_PLAIN, "text");
                    return new CommandInfo(CONVERSATION_EXCHANGE, CREATE_MESSAGE_ROUTING_KEY,
                            createCreateMessageCommand(conversationId, botUserId, List.of(eventPart)),
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

            var lastCommandInfo = (CreateMessageCommand) commandInfos.get(commandInfos.size() - 1).getCommand();
            var messageId = lastCommandInfo.getPayload().getId();
            var createActionCommand = createCreateActionCommand(conversationId, messageId, botUserId, quickReplies);
            commandInfos.add(new CommandInfo(CREATE_ACTIONS_EXCHANGE, CREATE_ACTIONS_ROUTING_KEY,
                    createActionCommand, 0, timeOfLastMessageReceived, commandInfos.size() + 1));
        }

        return commandInfos;
    }

    private static CreateMessageCommand createCreateMessageCommand(String conversationId, String botUserId, List<Event.Part> parts) {
        return new CreateMessageCommand(new Command.AuthContext(botUserId),
                new Payload(conversationId, botUserId, INPUT_TYPE_TEXT, parts));
    }

    private static CreateActionsCommand createCreateActionCommand(
            String conversationId, String messageId, String botUserId, List<QuickReply> quickReplies) {

        return new CreateActionsCommand(new Command.AuthContext(botUserId), getCurrentTime(),
                new CreateActionsCommand.Payload(convertQuickRepliesToActions(conversationId, messageId, quickReplies)));
    }

    private static List<CreateActionsCommand.Payload.Action> convertQuickRepliesToActions(
            String conversationId, String messageId, List<QuickReply> quickReplies) {

        return quickReplies.stream().map(quickReply -> {
                    Boolean isDefault = quickReply.isDefault();
            return new CreateActionsCommand.Payload.Action(conversationId, messageId, isDefault, quickReply.getValue());
                }
        ).collect(Collectors.toList());
    }
}
