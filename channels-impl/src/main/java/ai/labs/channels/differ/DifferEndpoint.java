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
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static ai.labs.channels.differ.utilities.DifferUtilities.generateUUID;
import static ai.labs.channels.differ.utilities.DifferUtilities.getCurrentTime;
import static ai.labs.lifecycle.IConversation.CONVERSATION_END;
import static ai.labs.utilities.RuntimeUtilities.*;
import static java.util.concurrent.TimeUnit.MILLISECONDS;


@Slf4j
@Singleton
public class DifferEndpoint implements IDifferEndpoint {
    private static final String CONVERSATION_CREATE_EXCHANGE = "conversation";
    private static final String CONVERSATION_CREATE_ROUTING_KEY = "conversation.create";

    private final IRestBotManagement restBotManagement;
    private final SystemRuntime.IRuntime runtime;
    private final IJsonSerialization jsonSerialization;
    private final IDifferPublisher differPublisher;
    private final IDifferOutputTransformer differOutputTransformer;
    private final Map<String, DifferEventDefinition> availableBotUserIds = new HashMap<>();
    private final ICache<String, ConversationInfo> conversationInfoCache;
    private final ICache<String, CommandInfo> ackAwaitingCommandsCache;
    private static final String logStatementIgnoredEvent = " [x] received but ignored amqp event, " +
            "since non of the participants in this conversation is a bot. ('{}')";

    @Inject
    public DifferEndpoint(IRestBotManagement restBotManagement,
                          SystemRuntime.IRuntime runtime,
                          ICacheFactory cacheFactory,
                          IJsonSerialization jsonSerialization,
                          IDifferOutputTransformer differOutputTransformer,
                          IDifferPublisher differPublisher) {
        this.restBotManagement = restBotManagement;
        this.runtime = runtime;
        this.jsonSerialization = jsonSerialization;
        this.differOutputTransformer = differOutputTransformer;
        this.differPublisher = differPublisher;
        this.conversationInfoCache = cacheFactory.getCache("differ.conversation.participantIds");
        this.ackAwaitingCommandsCache = cacheFactory.getCache("differ.ackAwaitingCommands");
    }

    @Override
    public void init() {
        differPublisher.init(createConversationCreatedCallback(), createMessageCreatedCallback());

        String conversationId = "a6f5d80d-ca23-41aa-97e4-ced486d8188c";
        conversationInfoCache.put(conversationId,
                new ConversationInfo(
                        conversationId,
                        Arrays.asList("379db2b2-ea74-4812-8d36-d6a988bdc311", "cfc4347d-a585-4ddf-8767-6210f3876b91"),
                        List.of("379db2b2-ea74-4812-8d36-d6a988bdc311")));

        log.info("Differ integration started");
    }

    private DeliverCallback createConversationCreatedCallback() {
        return (consumerTag, delivery) -> {
            final long deliveryTag = delivery.getEnvelope().getDeliveryTag();
            String receivedMessage = new String(delivery.getBody(), StandardCharsets.UTF_8);

            try {
                Event receivedEvent = jsonSerialization.deserialize(receivedMessage, Event.class);

                if (receivedEvent.getPayload().getError() != null) {
                    log.error("ConversationCreated event contained error '{}'", receivedMessage);
                    differPublisher.positiveDeliveryAck(deliveryTag);
                    return;
                }

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
                    logIgnoredEvent(logStatementIgnoredEvent, receivedMessage);
                    differPublisher.positiveDeliveryAck(deliveryTag);
                    return;
                }
                log.info(" [x] Received and accepted amqp event: '" + receivedMessage + "'");

                botUserParticipantIds.forEach(botUserId ->
                        startConversationWithUser(delivery, botUserId, conversationId, receivedEvent.getPayload(), conversationInfo));
            } catch (Exception e) {
                log.error("Error processing delivery {} of conversation.created.eddi with body {}", deliveryTag, receivedMessage);
                log.error(e.getLocalizedMessage(), e);
                differPublisher.negativeDeliveryAck(delivery);
            }
        };
    }

    private void logIgnoredEvent(String logStatement, String logObject) {
        log.info(logStatement, logObject);
    }

    private DeliverCallback createMessageCreatedCallback() {
        return (consumerTag, delivery) -> {
            final long deliveryTag = delivery.getEnvelope().getDeliveryTag();
            String receivedMessage = new String(delivery.getBody(), StandardCharsets.UTF_8);

            try {
                Event receivedEvent = jsonSerialization.deserialize(receivedMessage, Event.class);

                executeConfirmationSeekingCommands(receivedEvent.getPayload().getMessage().getId());

                Event.Payload payload = receivedEvent.getPayload();
                String senderId = payload.getMessage().getSenderId();
                if (availableBotUserIds.containsKey(senderId)) {
                    //this message has been created by a bot user, likely us, therefore skip processing
                    differPublisher.positiveDeliveryAck(deliveryTag);
                    return;
                }

                var conversationId = payload.getConversation().getId();
                var conversationInfo = getConversationInfo(conversationId);

                if (!isNullOrEmpty(conversationInfo)) {
                    if (isGroupChat(conversationInfo.getAllParticipantIds())) {
                        List<String> mentions = receivedEvent.getPayload().getMessage().getMentions();
                        if (isNullOrEmpty(mentions)) {
                            //this message belongs to a conversation we are a part of, but since this
                            //is a group chat, we only process messages that contain a mentions of a bot user
                            differPublisher.positiveDeliveryAck(deliveryTag);
                            return;
                        }
                    }

                    log.info(" [x] Received and accepted amqp event: '" + receivedMessage + "'");

                    var botIntent = getChannelEventDefinition(conversationInfo.getBotParticipantIds()).getBotIntent();

                    conversationInfo.getBotParticipantIds().forEach(botUserId ->
                    {
                        if (!isGroupChat(conversationInfo.getAllParticipantIds())) {
                            String userInput = payload.getMessage().getParts().get(0).getBody();
                            processUserMessage(delivery, botUserId, conversationId,
                                    userInput, payload, botIntent, Collections.emptyMap());
                        } else {
                            //todo change to debug
                            logIgnoredEvent(" [x] Ignoring message (id={}) " +
                                    "because it is from a group chat", receivedEvent.getEventId());
                            differPublisher.positiveDeliveryAck(deliveryTag);
                        }
                    });
                } else {
                    //todo change to debug
                    //No bot involved in this conversation
                    logIgnoredEvent(logStatementIgnoredEvent, receivedMessage);
                    differPublisher.positiveDeliveryAck(deliveryTag);
                }
            } catch (Exception e) {
                log.error("Error processing delivery {} of message.created.eddi with body {}", deliveryTag, receivedMessage);
                log.error(e.getLocalizedMessage(), e);
                differPublisher.negativeDeliveryAck(delivery);
            }
        };
    }

    private static boolean isGroupChat(List<String> participantIds) {
        return participantIds.size() > 2;
    }

    private List<String> filterBotUserParticipantIds(List<String> allParticipantIds) {
        getChannelEventDefinition(List.of(""));

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

    private DifferEventDefinition getChannelEventDefinition(List<String> botUserId) {
        DifferEventDefinition differEventDefinition = new DifferEventDefinition();
        differEventDefinition.setBotIntent("test");
        String adminDifferBotId = "379db2b2-ea74-4812-8d36-d6a988bdc311";
        differEventDefinition.getDifferBotUserIds().add(adminDifferBotId);
        if (availableBotUserIds.isEmpty()) {
            availableBotUserIds.put(adminDifferBotId, differEventDefinition);
        }
        return differEventDefinition;
    }

    private void startConversationWithUser(Delivery delivery, String botUserId, String conversationId, Event.Payload payload, ConversationInfo conversationInfo) {
        DifferEventDefinition channelEventDefinition = getChannelEventDefinition(List.of(botUserId));

        Map<String, Context> context = new LinkedHashMap<>();
        context.put("newConversationStarted", new Context(Context.ContextType.string, "true"));
        context.put("isGroupConversation", new Context(Context.ContextType.string, String.valueOf(isGroupChat(conversationInfo.getAllParticipantIds()))));

        processUserMessage(delivery, botUserId, conversationId, "", payload, channelEventDefinition.getBotIntent(), context);
    }

    private void processUserMessage(Delivery delivery, String botUserId, String conversationId, String userInput,
                                    Event.Payload payload, String botIntent, Map<String, Context> context) {

        if (isNullOrEmpty(conversationId)) {
            log.error("conversationId was Null. Stopped execution.");
            differPublisher.negativeDeliveryAck(delivery);
            return;
        }

        Map<String, Context> contextMap = new HashMap<>(context);
        if (payload != null) {
            contextMap.put("payload", new Context(Context.ContextType.object, payload));
        }

        var inputData = new InputData();
        inputData.setInput(userInput);
        inputData.setContext(contextMap);
        restBotManagement.sayWithinContext(botIntent, conversationId,
                false, true, Collections.emptyList(),
                inputData, new MockAsyncResponse() {
                    @Override
                    public boolean resume(Object response) {
                        if (response instanceof Response) {
                            log.error("An error occurred in bot management {}", ((Response) response).getEntity());
                            differPublisher.negativeDeliveryAck(delivery);
                            return true;
                        }

                        var memorySnapshot = (SimpleConversationMemorySnapshot) response;
                        boolean success = sendBotOutputToConversation(memorySnapshot, botUserId, conversationId);
                        if (success) {
                            differPublisher.positiveDeliveryAck(delivery.getEnvelope().getDeliveryTag());
                        } else {
                            differPublisher.negativeDeliveryAck(delivery);
                        }

                        List actions = (List) memorySnapshot.getConversationOutputs().get(0).get("actions");
                        if (actions != null && actions.contains(CONVERSATION_END)) {
                            memorySnapshot = restBotManagement.
                                    loadConversationMemory(botIntent, conversationId,
                                            false, true, Collections.emptyList());
                            sendBotOutputToConversation(memorySnapshot, botUserId, conversationId);
                        }

                        return true;
                    }

                    @Override
                    public boolean resume(Throwable response) {
                        differPublisher.negativeDeliveryAck(delivery);
                        return false;
                    }
                });
    }

    private boolean sendBotOutputToConversation(SimpleConversationMemorySnapshot memorySnapshot, String botUserId, String conversationId) {
        List<CommandInfo> commandInfos = differOutputTransformer.
                convertBotOutputToMessageCreateCommands(memorySnapshot, botUserId, conversationId);
        return publishCommands(commandInfos);
    }

    private boolean publishCommands(List<CommandInfo> commandInfos) {
        try {
            if (!isNullOrEmpty(commandInfos)) {
                var firstCommandInfo = commandInfos.get(0);

                Command firstCommand = firstCommandInfo.getCommand();
                String referenceCommandId = getReferenceId(firstCommand);
                for (int i = 1; i < commandInfos.size(); i++) {
                    var commandInfo = commandInfos.get(i);
                    storeCommandToBeExecuteOnEventReceived(referenceCommandId, commandInfo);
                    referenceCommandId = getReferenceId(commandInfo.getCommand());
                }

                return runtime.submitScheduledCallable(() ->
                                differPublisher.publishCommandAndWaitForConfirm(firstCommandInfo),
                        firstCommandInfo.getSendingDelay(), MILLISECONDS, null).get();
            }

            return true;
        } catch (ExecutionException | InterruptedException e) {
            log.error(e.getLocalizedMessage(), e);
            return false;
        }
    }

    private String getReferenceId(Command command) {
        if (command instanceof ConversationCreateCommand) {
            return ((ConversationCreateCommand) command).getPayload().getId();
        } else if (command instanceof ActionsCreateCommand) {
            var actions = ((ActionsCreateCommand) command).getPayload().getActions();
            List<String> actionIds = actions.stream().map(ActionsCreateCommand.Payload.Action::getId).collect(Collectors.toList());
            return String.join("-", actionIds);
        } else {
            return ((MessageCreateCommand) command).getPayload().getId();
        }
    }

    private void storeCommandToBeExecuteOnEventReceived(String eventId, CommandInfo commandInfo) {
        ackAwaitingCommandsCache.put(eventId, commandInfo);
    }

    private void executeConfirmationSeekingCommands(String messageId) {
        if (messageId != null) {
            var commandInfo = ackAwaitingCommandsCache.get(messageId);
            if (commandInfo != null) {
                runtime.submitScheduledCallable(() -> {
                    try {
                        var success = differPublisher.publishCommandAndWaitForConfirm(commandInfo);

                        if (success) {
                            ackAwaitingCommandsCache.remove(messageId);
                        } else {
                            log.error("Unable to send command to broker: exchange: {} routingKey: {} command: {}",
                                    commandInfo.getCommand(), commandInfo.getRoutingKey(),
                                    jsonSerialization.serialize(commandInfo.getCommand()));
                        }
                    } catch (IOException e) {
                        log.error(e.getLocalizedMessage(), e);
                    }

                    return null;
                }, commandInfo.getSendingDelay(), MILLISECONDS, null);
            }
        }
    }

    @Override
    public void triggerConversationCreated(CreateConversation createConversation) {
        checkNotNull(createConversation, "createConversation");
        checkNotEmpty(createConversation.getBotUserIdCreator(), "createConversation.botUserIdCreator");
        checkNotEmpty(createConversation.getConversationName(), "createConversation.conversationName");
        checkNotEmpty(createConversation.getParticipantIds(), "createConversation.participantIds");

        try {
            triggerConversationCreateCommand(createConversation);
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public void endBotConversation(String intent, String botUserId, String differConversationId) {
        restBotManagement.endCurrentConversation(intent, differConversationId);

        var memorySnapshot = restBotManagement.
                loadConversationMemory(intent, differConversationId,
                        false, true, Collections.emptyList());

        sendBotOutputToConversation(memorySnapshot, botUserId, differConversationId);
    }

    private void triggerConversationCreateCommand(CreateConversation createConversation) throws IOException {
        var command = new ConversationCreateCommand(
                new Command.AuthContext(createConversation.getBotUserIdCreator()),
                generateUUID(), CONVERSATION_CREATE_ROUTING_KEY, getCurrentTime());

        command.setPayload(
                new ConversationCreateCommand.Payload(
                        generateUUID(),
                        createConversation.getConversationName(),
                        createConversation.getParticipantIds()));

        publishConversationCreateCommand(command);
    }

    private void publishConversationCreateCommand(ConversationCreateCommand conversationCreateCommand) throws IOException {
        differPublisher.publishCommandAndWaitForConfirm(new CommandInfo(
                CONVERSATION_CREATE_EXCHANGE,
                CONVERSATION_CREATE_ROUTING_KEY, conversationCreateCommand, 0));
    }
}
