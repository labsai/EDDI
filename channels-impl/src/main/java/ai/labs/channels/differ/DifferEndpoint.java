package ai.labs.channels.differ;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import ai.labs.channels.differ.model.*;
import ai.labs.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.models.Context;
import ai.labs.models.InputData;
import ai.labs.rest.MockAsyncResponse;
import ai.labs.rest.rest.IRestBotManagement;
import ai.labs.runtime.SystemRuntime;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.utilities.RuntimeUtilities;
import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@Singleton
public class DifferEndpoint implements IDifferEndpoint {
    private static final String MESSAGE_CREATE_EXCHANGE = "message.create";
    private static final String MESSAGE_CREATE_ROUTING_KEY = "message.create";
    private static final String MESSAGE_CREATED_EXCHANGE = "message.created";
    private static final String MESSAGE_CREATED_QUEUE_NAME = "message.created.eddi";
    private static final String ACTION_CREATE_EXCHANGE = "message-actions";
    private static final String ACTION_CREATE_ROUTING_KEY = "message-actions.createMany";
    private static final String CONVERSATION_CREATED_EXCHANGE = "conversation.created";
    private static final String CONVERSATION_CREATED_QUEUE_NAME = "conversation.created.eddi";
    private static final String INPUT_TYPE = "text";
    private static final String CONVERSATION_CREATE_EXCHANGE = "conversation";
    private static final String CONVERSATION_CREATE_ROUTING_KEY = "conversation.create";
    private final IRestBotManagement restBotManagement;
    private final SystemRuntime.IRuntime runtime;
    private final IJsonSerialization jsonSerialization;
    private final Channel channel;
    private final CancelCallback cancelCallback = consumerTag -> {
    };
    private final Map<String, DifferEventDefinition> availableBotUserIds = new HashMap<>();
    private final ICache<String, ConversationInfo> conversationInfoCache;
    private final ICache<String, CommandInfo> ackAwaitingCommandsCache;

    @Inject
    public DifferEndpoint(Channel channel,
                          IRestBotManagement restBotManagement,
                          SystemRuntime.IRuntime runtime,
                          ICacheFactory cacheFactory,
                          IJsonSerialization jsonSerialization) {
        this.channel = channel;
        this.restBotManagement = restBotManagement;
        this.runtime = runtime;
        this.jsonSerialization = jsonSerialization;
        this.conversationInfoCache = cacheFactory.getCache("differ.conversation.participantIds");
        this.ackAwaitingCommandsCache = cacheFactory.getCache("differ.conversation.sendMessages");
    }

    @Override
    public void init() {
        try {
            channel.queueDeclare(CONVERSATION_CREATED_QUEUE_NAME, true, false, false, null);
            channel.queueBind(CONVERSATION_CREATED_QUEUE_NAME, CONVERSATION_CREATED_EXCHANGE, "");
            channel.basicConsume(CONVERSATION_CREATED_QUEUE_NAME, false, createConversationCreatedCallback(), cancelCallback);

            channel.queueDeclare(MESSAGE_CREATED_QUEUE_NAME, true, false, false, null);
            channel.queueBind(MESSAGE_CREATED_QUEUE_NAME, MESSAGE_CREATED_EXCHANGE, "");
            channel.basicConsume(MESSAGE_CREATED_QUEUE_NAME, false, createMessageCreatedCallback(), cancelCallback);
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
        }
    }

    private DeliverCallback createConversationCreatedCallback() {
        return (consumerTag, delivery) -> {
            final long deliveryTag = delivery.getEnvelope().getDeliveryTag();

            String receivedMessage = new String(delivery.getBody(), StandardCharsets.UTF_8);
            Event receivedEvent = jsonSerialization.deserialize(receivedMessage, Event.class);

            var payload = receivedEvent.getPayload();
            var conversationId = payload.getConversation().getId();
            var participantIds = payload.getParticipantIds();
            // in case there are multiple bots part of the conversation, we need to send this data to all of them
            var botUserParticipantIds = filterBotUserParticipantIds(participantIds);
            var conversationInfo = new ConversationInfo(conversationId, participantIds, botUserParticipantIds);
            conversationInfoCache.put(conversationId, conversationInfo);

            if (botUserParticipantIds.isEmpty()) {
                //No bot involved in this conversation
                //todo change to debug
                log.info("received but ignored amqp event, since non of the participants is a bot. ('{}')", receivedMessage);
                return;
            }
            log.info(" [x] Received and accepted amqp event: '" + receivedMessage + "'");

            botUserParticipantIds.forEach(botUserId -> {
                boolean success = startConversationWithUser(receivedEvent.getMessage().getSenderId(),
                        botUserId, payload, conversationInfo);
                sendAck(deliveryTag, success);
            });
        };
    }

    private DeliverCallback createMessageCreatedCallback() {
        return (consumerTag, delivery) -> {
            final long deliveryTag = delivery.getEnvelope().getDeliveryTag();

            String receivedMessage = new String(delivery.getBody(), StandardCharsets.UTF_8);
            Event receivedEvent = jsonSerialization.deserialize(receivedMessage, Event.class);

            executeConfirmationSeekingCommands(receivedEvent.getEventId());

            if (availableBotUserIds.containsKey(receivedEvent.getMessage().getSenderId())) {
                //this message has been created by a bot user, likely us, therefore skip processing
                return;
            }

            var conversationId = receivedEvent.getPayload().getConversation().getId();
            var conversationInfo = getConversationInfo(conversationId);

            if (!RuntimeUtilities.isNullOrEmpty(conversationInfo)) {
                log.info(" [x] Received and accepted amqp event: '" + receivedMessage + "'");

                conversationInfo.getBotParticipantIds().forEach(botUserId ->
                {
                    if (!isGroupChat(conversationInfo.getAllParticipantIds())) {
                        processUserMessage(deliveryTag, receivedEvent.getMessage().getSenderId(), botUserId, receivedEvent);
                    } else {
                        //todo change to debug
                        log.info(" [x] Ignoring message (id={}) because it is from a group chat", receivedEvent.getEventId());
                    }
                });
            } else {
                //todo change to debug
                //No bot involved in this conversation
                log.info(" [x] received but ignored amqp event, " +
                        "since non of the participants in this conversation is a bot. ('{}')", receivedMessage);
            }
        };
    }

    private void executeConfirmationSeekingCommands(String eventId) {
        var commandInfo = ackAwaitingCommandsCache.get(eventId);
        if (commandInfo != null) {
            runtime.submitCallable(() -> {
                try {
                    var command = commandInfo.getCommand();
                    command.setSentAt(getCurrentTime());
                    String eventBody = jsonSerialization.serialize(command);
                    channel.basicPublish(
                            commandInfo.getExchange(), commandInfo.getRoutingKey(), null, eventBody.getBytes());

                    ackAwaitingCommandsCache.remove(eventId);
                } catch (IOException e) {
                    log.error(e.getLocalizedMessage(), e);
                }

                return null;
            }, null);
        }
    }

    private static boolean isGroupChat(List<String> participantIds) {
        return participantIds.size() > 2;
    }

    private List<String> filterBotUserParticipantIds(List<String> allParticipantIds) {
        getChannelEventDefinition("");

        List<String> ret = new LinkedList<>();
        allParticipantIds.forEach(participantId -> {
            var differEventDefinition = availableBotUserIds.get(participantId);
            if (differEventDefinition != null && !ret.contains(participantId)) {
                ret.add(participantId);
            }
        });

        return ret;
    }

    private ConversationInfo getConversationInfo(String conversationId) {
        return conversationInfoCache.get(conversationId);
    }

    //todo
    private DifferEventDefinition getChannelEventDefinition(String userId) {
        DifferEventDefinition differEventDefinition = new DifferEventDefinition();
        differEventDefinition.setBotIntent("test");
        differEventDefinition.getDifferBotUserId().add("379db2b2-ea74-4812-8d36-d6a988bdc311");
        if (availableBotUserIds.isEmpty()) {
            availableBotUserIds.put("379db2b2-ea74-4812-8d36-d6a988bdc311", differEventDefinition);
        }
        return differEventDefinition;
    }

    private boolean startConversationWithUser(String userId, String botUserId, Event.Payload payload, ConversationInfo conversationInfo) {
        var channelEventDefinition = getChannelEventDefinition(userId);
        var intent = channelEventDefinition.getBotIntent();

        String conversationId = null;
        if (payload != null) {
            Event.Conversation conversation = payload.getConversation();
            if (conversation != null) {
                conversationId = conversation.getId();
                if (RuntimeUtilities.isNullOrEmpty(conversationId)) {
                    log.error("conversationId was Null. Skipped execution.");
                    return false;
                }
            }
        }

        //todo send conversionInfo via sayWithContext
        var memorySnapshot = restBotManagement.
                loadConversationMemory(intent, conversationId,
                        false, true, null);
        return sendBotOutputToConversation(memorySnapshot, botUserId, conversationId);
    }

    private void processUserMessage(long deliveryTag, String userId, String botUserId, Event event) {
        var channelEventDefinition = getChannelEventDefinition(userId);
        var intent = channelEventDefinition.getBotIntent();
        var input = event.getMessage().getParts().get(0).getBody();

        Map<String, Context> context = new HashMap<>();
        var payload = event.getPayload();
        String conversationId = null;
        if (payload != null) {
            Event.Conversation conversation = payload.getConversation();
            if (conversation != null) {
                conversationId = conversation.getId();
                if (RuntimeUtilities.isNullOrEmpty(conversationId)) {
                    log.error("conversationId was Null. Stopped execution.");
                    return;
                }
            }
            context.put("payload", new Context(Context.ContextType.object, payload));
        }

        var inputData = new InputData();
        inputData.setInput(input);
        inputData.setContext(context);
        var _conversationId = conversationId;
        restBotManagement.sayWithinContext(intent, conversationId, false, true, null,
                inputData,
                new MockAsyncResponse() {
                    @Override
                    public boolean resume(Object response) {
                        var memorySnapshot = (SimpleConversationMemorySnapshot) response;
                        sendOutputAndAck(memorySnapshot, botUserId, _conversationId, deliveryTag);
                        return true;
                    }

                    @Override
                    public boolean resume(Throwable response) {
                        negativeDeliveryAck(deliveryTag);
                        return false;
                    }
                });
    }

    private void sendOutputAndAck(SimpleConversationMemorySnapshot memorySnapshot, String botUserId, String conversationId, long deliveryTag) {
        boolean success = sendBotOutputToConversation(memorySnapshot, botUserId, conversationId);
        sendAck(deliveryTag, success);
    }

    private void sendAck(long deliveryTag, boolean success) {
        if (success) {
            positiveDeliveryAck(deliveryTag);
        } else {
            negativeDeliveryAck(deliveryTag);
        }
    }

    private void positiveDeliveryAck(long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
        }
    }

    private void negativeDeliveryAck(long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
        }
    }

    private boolean sendBotOutputToConversation(SimpleConversationMemorySnapshot memorySnapshot, String botUserId, String conversationId) {
        List<Event.Part> parts = convertBotOutputToMessageParts(memorySnapshot);

        List<MessageCreateCommand> messageCreateCommands =
                List.of(new MessageCreateCommand(generateUUID(), conversationId, INPUT_TYPE, parts, botUserId));

        //todo
        ActionsCreateCommand actionsCreateCommand = new ActionsCreateCommand();

        return publishCommands(messageCreateCommands, actionsCreateCommand);
    }

    private List<Event.Part> convertBotOutputToMessageParts(SimpleConversationMemorySnapshot memorySnapshot) {
        var bodyParts = new LinkedList<String>();
        memorySnapshot.getConversationOutputs().stream().
                map(conversationOutput -> (List) conversationOutput.get("output")).
                forEach(outputs ->
                {
                    for (Object output : outputs) {
                        if (output instanceof String) {
                            bodyParts.add(output.toString());
                        } else if (output instanceof Map) {
                            Map<String, Object> outputMap = (Map<String, Object>) output;
                            String type = outputMap.get("type").toString();
                            if (INPUT_TYPE.equals(type)) {
                                bodyParts.add(outputMap.get(INPUT_TYPE).toString());
                            }
                        }
                    }
                });

        return bodyParts.stream().map(
                bodyPart -> new Event.Part(generateUUID(), bodyPart, MediaType.TEXT_PLAIN, INPUT_TYPE)).
                collect(Collectors.toList());
    }

    private boolean publishConversationCreateCommand(ConversationCreateCommand conversationCreateCommand) {
        try {
            String eventBody = jsonSerialization.serialize(conversationCreateCommand);
            channel.basicPublish(
                    CONVERSATION_CREATE_EXCHANGE,
                    CONVERSATION_CREATE_ROUTING_KEY,
                    null, eventBody.getBytes());
            return true;
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
            return false;
        }
    }

    private boolean publishCommands(List<MessageCreateCommand> messageCreateCommands,
                                    ActionsCreateCommand actionsCreateCommand) {
        try {
            if (!RuntimeUtilities.isNullOrEmpty(messageCreateCommands)) {
                var firstMessageCreateCommand = messageCreateCommands.get(0);

                String referenceEventId = firstMessageCreateCommand.getId();
                for (int i = 1; i < messageCreateCommands.size(); i++) {
                    var messageCreateCommand = messageCreateCommands.get(i);
                    storeCommandToBeExecuteOnEventReceived(referenceEventId, messageCreateCommand);
                    referenceEventId = messageCreateCommand.getId();
                }

                if (actionsCreateCommand != null) {
                    storeCommandToBeExecuteOnEventReceived(referenceEventId, actionsCreateCommand);
                }

                firstMessageCreateCommand.setSentAt(getCurrentTime());
                String eventBody = jsonSerialization.serialize(firstMessageCreateCommand);
                channel.basicPublish(
                        MESSAGE_CREATE_EXCHANGE,
                        MESSAGE_CREATE_ROUTING_KEY,
                        null, eventBody.getBytes());
            }

            return true;
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
            return false;
        }

    }

    private void storeCommandToBeExecuteOnEventReceived(String id, MessageCreateCommand messageCreateCommand) {
        storeFutureCommand(id, MESSAGE_CREATE_EXCHANGE, MESSAGE_CREATE_ROUTING_KEY, messageCreateCommand);
    }

    private void storeCommandToBeExecuteOnEventReceived(String id, ActionsCreateCommand actionsCreateCommand) {
        storeFutureCommand(id, ACTION_CREATE_EXCHANGE, ACTION_CREATE_ROUTING_KEY, actionsCreateCommand);
    }

    private void storeFutureCommand(String eventId, String exchange, String routingKey, ICommand command) {
        ackAwaitingCommandsCache.put(eventId, new CommandInfo(exchange, routingKey, command));
    }

    private static Date getCurrentTime() {
        return new Date(System.currentTimeMillis());
    }

    private static String generateUUID() {
        return UUID.randomUUID().toString();
    }
}
