package ai.labs.channels.differ;

import ai.labs.channels.differ.model.DifferEventDefinition;
import ai.labs.channels.differ.model.Event;
import ai.labs.memory.model.ConversationOutput;
import ai.labs.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.models.Context;
import ai.labs.models.InputData;
import ai.labs.rest.MockAsyncResponse;
import ai.labs.rest.rest.IRestBotManagement;
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


@Slf4j
@Singleton
public class DifferEndpoint implements IDifferEndpoint {
    private static final String MESSAGE_CREATED_EXCHANGE = "message.created";
    private static final String MESSAGE_CREATED_QUEUE_NAME = "message.created.eddi";
    private static final String CONVERSATION_CREATED_EXCHANGE = "conversation.created";
    private static final String CONVERSATION_CREATED_QUEUE_NAME = "conversation.created.eddi";
    private static final String INPUT_TYPE = "text";
    private static final String EVENT_NAME_MESSAGE_CREATED = "message.created";
    private static final String EVENT_VERSION = "1.4";
    private final IRestBotManagement restBotManagement;
    private final IJsonSerialization jsonSerialization;
    private final Channel channel;
    private final CancelCallback cancelCallback = consumerTag -> {
    };
    private final Map<String, DifferEventDefinition> availableBotUserIds = new HashMap<>();

    @Inject
    public DifferEndpoint(Channel channel, IRestBotManagement restBotManagement, IJsonSerialization jsonSerialization) {
        this.channel = channel;
        this.restBotManagement = restBotManagement;
        this.jsonSerialization = jsonSerialization;
        init();
    }

    @Override
    public void init() {
        try {
            final DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                String receivedMessage = new String(delivery.getBody(), StandardCharsets.UTF_8);
                log.info(" [x] Received amqp msg: '" + receivedMessage + "'");
                log.info("deserializing event...");
                long start = System.currentTimeMillis();
                Event receivedEvent = jsonSerialization.deserialize(receivedMessage, Event.class);
                long end = System.currentTimeMillis();
                log.info("deserializing done. ({}ms)", (end - start));

                List<String> participantIds;
                var payload = receivedEvent.getPayload();
                var conversation = payload.getConversation();
                if (receivedEvent.getEventName().equals(CONVERSATION_CREATED_EXCHANGE)) {
                    participantIds = payload.getParticipantIds();
                } else {
                    participantIds = conversation.getParticipantIds();
                }
                var botUserParticipantIds = getBotUserParticipantIds(participantIds);

                if (botUserParticipantIds.isEmpty()) {
                    //No bot involved in this conversation
                    return;
                }

                botUserParticipantIds.forEach(
                        botUserId ->
                                processUserMessage(receivedEvent.getMessage().getSenderId(), botUserId, receivedEvent)
                );
            };

            channel.queueDeclare(MESSAGE_CREATED_QUEUE_NAME, true, false, false, null);
            channel.queueBind(MESSAGE_CREATED_QUEUE_NAME, MESSAGE_CREATED_EXCHANGE, "");
            channel.basicConsume(MESSAGE_CREATED_QUEUE_NAME, true, deliverCallback, cancelCallback);

            channel.queueDeclare(CONVERSATION_CREATED_QUEUE_NAME, true, false, false, null);
            channel.queueBind(CONVERSATION_CREATED_QUEUE_NAME, CONVERSATION_CREATED_EXCHANGE, "");
            channel.basicConsume(CONVERSATION_CREATED_QUEUE_NAME, true, deliverCallback, cancelCallback);
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
        }
    }

    private List<String> getBotUserParticipantIds(List<String> participantIds) {
        List<String> ret = new LinkedList<>();
        participantIds.forEach(participantId -> {
            DifferEventDefinition differEventDefinition = availableBotUserIds.get(participantId);
            if (differEventDefinition != null && !ret.contains(participantId)) {
                ret.add(participantId);
            }
        });

        return ret;
    }

    private DifferEventDefinition getChannelEventDefinition(String userId) {
        DifferEventDefinition differEventDefinition = new DifferEventDefinition();
        differEventDefinition.setBotIntent("test");
        differEventDefinition.getDifferBotUserId().add("379db2b2-ea74-4812-8d36-d6a988bdc311");
        if (availableBotUserIds.isEmpty()) {
            availableBotUserIds.put("379db2b2-ea74-4812-8d36-d6a988bdc311", differEventDefinition);
        }
        return differEventDefinition;
    }

    private void processUserMessage(String userId, String botUserId, Event event) {
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
        restBotManagement.sayWithinContext(intent, userId, false, true, null,
                inputData,
                new MockAsyncResponse() {
                    @Override
                    public boolean resume(Object response) {
                        SimpleConversationMemorySnapshot memorySnapshot = (SimpleConversationMemorySnapshot) response;

                        var bodyParts = new LinkedList<String>();

                        for (ConversationOutput conversationOutput : memorySnapshot.getConversationOutputs()) {
                            List outputs = (List) conversationOutput.get("output");
                            for (Object output : outputs) {
                                if (output instanceof String) {
                                    bodyParts.add(output.toString());
                                } else if (output instanceof Map) {
                                    Map<String, Object> outputMap = (Map<String, Object>) output;
                                    String type = outputMap.get("type").toString();
                                    if (INPUT_TYPE.equals(type)) {
                                        bodyParts.add(outputMap.get("text").toString());
                                    }
                                }
                            }
                        }

                        sendMessage(botUserId, _conversationId, bodyParts);
                        return true;
                    }

                    @Override
                    public boolean resume(Throwable response) {
                        return false;
                    }
                });
    }

    private void sendMessage(String senderId, String conversationId, List<String> bodyParts) {
        try {
            Event event = createEvent(conversationId, senderId, bodyParts);
            String eventBody = jsonSerialization.serialize(event);
            channel.basicPublish(MESSAGE_CREATED_EXCHANGE, null, null, eventBody.getBytes());
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
        }
    }

    private static Event createEvent(String conversationId, String senderId, List<String> bodyParts) {
        var event = new Event();
        event.setEventId(generateRandomID());
        event.setEventName(EVENT_NAME_MESSAGE_CREATED);
        event.setCreatedAt(getCurrentTime());
        event.setVersion(EVENT_VERSION);
        Event.Payload payload = new Event.Payload();
        //todo add payload here
        event.setPayload(payload);

        var message = new Event.Message();
        message.setId(generateRandomID());
        message.setConversationId(conversationId);
        message.setInputType(INPUT_TYPE);
        message.setSenderId(senderId);
        message.setSentAt(getCurrentTime());
        event.setMessage(message);

        List<Event.Part> parts = new LinkedList<>();
        for (var bodyPart : bodyParts) {
            var part = new Event.Part();
            part.setId(generateRandomID());
            part.setMimeType(MediaType.TEXT_PLAIN);
            part.setType(INPUT_TYPE);
            part.setBody(bodyPart);
            parts.add(part);
        }
        message.setParts(parts);

        return event;
    }

    private static Date getCurrentTime() {
        return new Date(System.currentTimeMillis());
    }

    private static String generateRandomID() {
        return UUID.randomUUID().toString();
    }
}
