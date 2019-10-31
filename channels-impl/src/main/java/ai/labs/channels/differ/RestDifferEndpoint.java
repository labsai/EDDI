package ai.labs.channels.differ;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import ai.labs.channels.config.model.ChannelDefinition;
import ai.labs.channels.differ.model.CommandInfo;
import ai.labs.channels.differ.model.CreateConversation;
import ai.labs.channels.differ.model.DifferConversationInfo;
import ai.labs.channels.differ.model.commands.Command;
import ai.labs.channels.differ.model.commands.CreateActionsCommand;
import ai.labs.channels.differ.model.commands.CreateConversationCommand;
import ai.labs.channels.differ.model.commands.CreateMessageCommand;
import ai.labs.channels.differ.model.events.ConversationCreatedEvent;
import ai.labs.channels.differ.model.events.Event;
import ai.labs.channels.differ.model.events.MessageCreatedEvent;
import ai.labs.channels.differ.storage.IDifferBotMappingStore;
import ai.labs.channels.differ.storage.IDifferConversationStore;
import ai.labs.memory.model.ConversationOutput;
import ai.labs.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.models.Context;
import ai.labs.models.InputData;
import ai.labs.persistence.IResourceStore.ResourceAlreadyExistsException;
import ai.labs.persistence.IResourceStore.ResourceStoreException;
import ai.labs.rest.MockAsyncResponse;
import ai.labs.rest.restinterfaces.IRestBotManagement;
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

import static ai.labs.channels.differ.model.commands.CreateConversationCommand.CONVERSATION_EXCHANGE;
import static ai.labs.channels.differ.model.commands.CreateConversationCommand.CREATE_CONVERSATION_ROUTING_KEY;
import static ai.labs.channels.differ.utilities.DifferUtilities.getCurrentTime;
import static ai.labs.lifecycle.IConversation.CONVERSATION_END;
import static ai.labs.utilities.RuntimeUtilities.*;
import static java.util.concurrent.TimeUnit.MILLISECONDS;


@Slf4j
@Singleton
public class RestDifferEndpoint implements IRestDifferEndpoint {
    private final IRestBotManagement restBotManagement;
    private final SystemRuntime.IRuntime runtime;
    private final IJsonSerialization jsonSerialization;
    private final IDifferPublisher differPublisher;
    private final IDifferOutputTransformer differOutputTransformer;
    private final ICache<String, String> availableBotUserIds;
    private final ICache<String, Boolean> availableConversationIds;
    private final IDifferBotMappingStore botMappingStore;
    private final ICache<String, DifferConversationInfo> conversationInfoCache;
    private final ICache<String, CommandInfo> ackAwaitingCommandsCache;
    private final IDifferConversationStore differConversationStore;
    private static final String logStatementIgnoredEvent = " [x] received but ignored amqp event, " +
            "since none of the participants in this conversation is a bot. ('{}')";

    private boolean isInit = false;

    @Inject
    public RestDifferEndpoint(IRestBotManagement restBotManagement,
                              SystemRuntime.IRuntime runtime,
                              ICacheFactory cacheFactory,
                              IJsonSerialization jsonSerialization,
                              IDifferOutputTransformer differOutputTransformer,
                              IDifferPublisher differPublisher,
                              IDifferConversationStore differConversationStore,
                              IDifferBotMappingStore botMappingStore) {
        this.restBotManagement = restBotManagement;
        this.runtime = runtime;
        this.jsonSerialization = jsonSerialization;
        this.differOutputTransformer = differOutputTransformer;
        this.differPublisher = differPublisher;
        this.differConversationStore = differConversationStore;
        this.botMappingStore = botMappingStore;

        this.conversationInfoCache = cacheFactory.getCache("differ.conversation.participantIds");
        this.ackAwaitingCommandsCache = cacheFactory.getCache("differ.ackAwaitingCommands");
        this.availableBotUserIds = cacheFactory.getCache("differ.availableBotUserIds");
        this.availableConversationIds = cacheFactory.getCache("differ.availableConversationIds");
    }

    @Override
    public void init(ChannelDefinition channelDefinition) {

        if (isInit) {
            log.warn("DifferEndpoint tried to initialized, but has already been started");
            return;
        }

        try {
            differConversationStore.getAllDifferConversationIds().
                    forEach(conversationId -> availableConversationIds.put(conversationId, true));
            botMappingStore.readAllDifferBotMappings().
                    forEach(botMapping -> botMapping.getDifferBotUserIds().
                            forEach(botUserId -> availableBotUserIds.put(botUserId, botMapping.getBotIntent())));
        } catch (ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            return;
        }

        differPublisher.init(conversationCreatedCallback(), messageCreatedCallback());

        isInit = true;
        log.info("Differ integration started");
    }

    private DeliverCallback conversationCreatedCallback() {
        return (consumerTag, delivery) -> {
            final long deliveryTag = delivery.getEnvelope().getDeliveryTag();
            String receivedMessage = new String(delivery.getBody(), StandardCharsets.UTF_8);
            log.trace("Received Raw Conversation Created Message: {}", receivedMessage);

            try {
                var conversationCreatedEvent = jsonSerialization.deserialize(receivedMessage, ConversationCreatedEvent.class);

                if (conversationCreatedEvent.getPayload().getError() != null) {
                    log.error("ConversationCreated event contained error '{}'", receivedMessage);
                    differPublisher.positiveDeliveryAck(deliveryTag);
                    return;
                }

                var payload = conversationCreatedEvent.getPayload();
                var conversationId = payload.getConversation().getId();
                var participantIds = payload.getParticipantIds();
                // in case there are multiple bots part of the conversation, we need to send this data to all of them
                var botUserParticipantIds = filterBotUserParticipantIds(participantIds);

                if (botUserParticipantIds.isEmpty()) {
                    //No bot involved in this conversation
                    log.debug(logStatementIgnoredEvent, receivedMessage);
                    differPublisher.positiveDeliveryAck(deliveryTag);
                    return;
                }

                log.info(" [x] Received and accepted amqp event: {}", receivedMessage);

                var conversationInfo = createDifferConversation(conversationId, participantIds, botUserParticipantIds);
                log.debug("Differ Conversation created. {}", conversationInfo);

                botUserParticipantIds.forEach(botUserId ->
                        startConversationWithUser(delivery, botUserId, conversationId, conversationCreatedEvent, conversationInfo));
            } catch (Exception e) {
                log.error("Error processing delivery {} of conversation.created.eddi with body {}", deliveryTag, receivedMessage);
                log.error(e.getLocalizedMessage(), e);
                differPublisher.negativeDeliveryAck(delivery);
            }
        };
    }

    private DifferConversationInfo createDifferConversation(String conversationId,
                                                            List<String> participantIds,
                                                            List<String> botUserParticipantIds)
            throws ResourceAlreadyExistsException, ResourceStoreException {

        var conversationInfo = new DifferConversationInfo(conversationId, participantIds, botUserParticipantIds);
        differConversationStore.createDifferConversation(conversationInfo);
        conversationInfoCache.put(conversationId, conversationInfo);
        availableConversationIds.put(conversationId, true);
        return conversationInfo;
    }

    private DeliverCallback messageCreatedCallback() {
        return (consumerTag, delivery) -> {
            final long deliveryTag = delivery.getEnvelope().getDeliveryTag();
            String receivedMessage = new String(delivery.getBody(), StandardCharsets.UTF_8);
            log.trace("Received Raw Message Created Message: {}", receivedMessage);

            try {
                var messageCreatedEvent = jsonSerialization.deserialize(receivedMessage, MessageCreatedEvent.class);
                final var conversationId = messageCreatedEvent.getPayload().getConversation().getId();
                if (!availableConversationIds.containsKey(conversationId)) {
                    log.debug("Ignored message because " +
                            "conversationId is not part of any known conversations " +
                            "(conversationId={}).", conversationId);
                    differPublisher.positiveDeliveryAck(deliveryTag);
                    return;
                }

                //we have confirmed that we are part of this conversation
                // next step: see if there are any bot messages waiting for this message to arrive to be send out
                var messageSentAtTime = messageCreatedEvent.getCreatedAt().getTime();
                var message = messageCreatedEvent.getPayload().getMessage();
                if (message == null) {
                    log.error("Received a conversation.created event in the message.created queue. {}", receivedMessage);
                    differPublisher.negativeDeliveryAck(delivery);
                    return;
                }
                executeConfirmationSeekingCommands(message.getId(), messageSentAtTime);

                var payload = messageCreatedEvent.getPayload();
                String senderId = payload.getMessage().getSenderId();
                if (availableBotUserIds.containsKey(senderId)) {
                    //this message has been created by a bot user, likely us, therefore skip processing
                    differPublisher.positiveDeliveryAck(deliveryTag);
                    log.debug("Ignored message because created by us: {}", receivedMessage);
                    return;
                }

                var conversationInfo = getConversationInfo(conversationId);
                if (conversationInfo == null) {
                    log.warn("ConversationId {} is unknown in the system, yet it contains a know botUserId {}. " +
                            "Likely to eddi instances interfere each other here. " +
                            "This message.created event will be ignored due to lack of information.", conversationId, senderId);
                    return;
                }
                if (isGroupChat(conversationInfo.getAllParticipantIds())) {
                    List<MessageCreatedEvent.Mention> mentions = message.getMentions();
                    if (mentions == null ||
                            mentions.stream().noneMatch(
                                    mention -> availableBotUserIds.containsKey(mention.getUserId()))) {
                        //this message belongs to a conversation we are a part of, but since this
                        //is a group chat, we only process messages that contain a mentions of a bot user
                        differPublisher.positiveDeliveryAck(deliveryTag);
                        log.debug("Ignored message because it is a group chat: {}", receivedMessage);
                        return;
                    }

                    // this group chat has a mentions containing a botUserId
                    // so we process this message
                }

                log.info(" [x] Received and accepted amqp event: '" + receivedMessage + "'");

                String userInput = payload.getMessage().getParts().get(0).getBody();
                conversationInfo.getBotParticipantIds().forEach(botUserId ->
                        processUserMessage(delivery, botUserId, conversationId,
                                userInput, messageCreatedEvent, getBotIntent(botUserId), Collections.emptyMap()));

            } catch (Exception e) {
                log.error("Error processing delivery {} of message.created.eddi with body {}", deliveryTag, receivedMessage);
                log.error(e.getLocalizedMessage(), e);
                differPublisher.negativeDeliveryAck(delivery);
            }
        };
    }

    private DifferConversationInfo getConversationInfo(String conversationId)
            throws ResourceStoreException {

        DifferConversationInfo conversationInfo;
        if ((conversationInfo = conversationInfoCache.get(conversationId)) == null) {

            var differConversationInfo = differConversationStore.readDifferConversation(conversationId);
            conversationInfoCache.put(conversationId, differConversationInfo);
        }

        return conversationInfo;
    }

    private static boolean isGroupChat(List<String> participantIds) {
        return participantIds.size() > 2;
    }

    private List<String> filterBotUserParticipantIds(List<String> allParticipantIds) {
        return allParticipantIds.stream().
                filter(availableBotUserIds::containsKey). //if true, this participant is a defined bot user
                collect(Collectors.toList());
    }

    private void startConversationWithUser(Delivery delivery, String botUserId, String conversationId,
                                           ConversationCreatedEvent conversationCreatedEvent, DifferConversationInfo differConversationInfo) {

        Map<String, Context> context = new LinkedHashMap<>();
        context.put("botUserId", new Context(Context.ContextType.string, botUserId));
        List<String> allParticipantIds = differConversationInfo.getAllParticipantIds();
        List<String> botParticipantIds = differConversationInfo.getBotParticipantIds();
        List<String> nonBotUsers = allParticipantIds.stream().filter(o -> !botParticipantIds.contains(o)).collect(Collectors.toList());
        Map<String, List<String>> userIds = Map.of("userIds", nonBotUsers);
        context.put("conversationPartners", new Context(Context.ContextType.object, userIds));
        context.put("isNewConversation", new Context(Context.ContextType.string, "true"));
        context.put("isGroupConversation",
                new Context(Context.ContextType.string,
                        String.valueOf(isGroupChat(allParticipantIds))));

        processUserMessage(delivery, botUserId, conversationId, "", conversationCreatedEvent, getBotIntent(botUserId), context);
    }

    private String getBotIntent(String botUserId) {
        return availableBotUserIds.get(botUserId);
    }

    private void processUserMessage(Delivery delivery, String botUserId, String conversationId, String userInput,
                                    Event event, String botIntent, Map<String, Context> context) {

        if (isNullOrEmpty(conversationId)) {
            log.error("conversationId was Null. Stopped execution.");
            differPublisher.negativeDeliveryAck(delivery);
            return;
        }

        Map<String, Context> contextMap = new HashMap<>(context);
        var payload = event.getPayload();
        if (payload != null) {
            contextMap.put("payload", new Context(Context.ContextType.object, payload));
        }

        var inputData = new InputData();
        inputData.setInput(userInput);
        inputData.setContext(contextMap);
        log.debug("Executing bot management with params intent {}, conversationId {}, input {}", botIntent, conversationId, userInput);
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

                        long timeOfLastMessageReceived = event.getCreatedAt().getTime();
                        var memorySnapshot = (SimpleConversationMemorySnapshot) response;
                        List<ConversationOutput> conversationOutputs = memorySnapshot.getConversationOutputs();
                        log.debug("Conversation Output to be send (botUserId={}, conversationId={})", botUserId, conversationId);

                        List actions = (List) memorySnapshot.getConversationOutputs().get(0).get("actions");
                        if (actions != null && actions.contains(CONVERSATION_END)) {
                            memorySnapshot = restBotManagement.
                                    loadConversationMemory(botIntent, conversationId,
                                            false, true, Collections.emptyList());
                            conversationOutputs.addAll(memorySnapshot.getConversationOutputs());
                        }

                        boolean success = sendBotOutputToConversation(
                                conversationOutputs, timeOfLastMessageReceived, botUserId, conversationId);

                        log.debug("sendBotOutputToConversation operation was " + (success ? "" : "not ") + "successful.");
                        if (success) {
                            differPublisher.positiveDeliveryAck(delivery.getEnvelope().getDeliveryTag());
                        } else {
                            differPublisher.negativeDeliveryAck(delivery);
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

    private boolean sendBotOutputToConversation(List<ConversationOutput> conversationOutputs,
                                                long timeOfLastMessageReceived,
                                                String botUserId,
                                                String conversationId) {

        List<CommandInfo> commandInfos = differOutputTransformer.convertBotOutputToMessageCreateCommands(
                conversationOutputs, botUserId, conversationId, timeOfLastMessageReceived);

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
                        0, MILLISECONDS, null).get();
            } else {
                log.debug("Size of commandsInfos was 0 when calling publishCommand. Therefore no commands have been published.");
            }

            return true;
        } catch (ExecutionException | InterruptedException e) {
            log.error(e.getLocalizedMessage(), e);
            return false;
        }
    }

    private String getReferenceId(Command command) {
        if (command instanceof CreateConversationCommand) {
            return ((CreateConversationCommand) command).getPayload().getId();
        } else if (command instanceof CreateActionsCommand) {
            var actions = ((CreateActionsCommand) command).getPayload().getActions();
            var actionIds = actions.stream().map(CreateActionsCommand.Payload.Action::getId).collect(Collectors.toList());
            return String.join("-", actionIds);
        } else {
            return ((CreateMessageCommand) command).getPayload().getId();
        }
    }

    private void storeCommandToBeExecuteOnEventReceived(String eventId, CommandInfo commandInfo) {
        ackAwaitingCommandsCache.put(eventId, commandInfo);
    }

    private void executeConfirmationSeekingCommands(String messageId, long messageSentAtTime) {
        if (messageId != null) {
            var commandInfo = ackAwaitingCommandsCache.get(messageId);
            if (commandInfo != null) {
                runtime.submitScheduledCallable(() -> {
                    try {
                        commandInfo.setMinSentAt(messageSentAtTime);
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
    public Response triggerConversationCreated(CreateConversation createConversation) {
        checkNotNull(createConversation, "createConversation");
        checkNotEmpty(createConversation.getBotUserIdCreator(), "createConversation.botUserIdCreator");
        checkNotEmpty(createConversation.getConversationName(), "createConversation.conversationName");
        checkNotEmpty(createConversation.getParticipantIds(), "createConversation.participantIds");

        try {
            triggerCreateConversationCommand(createConversation);
            return Response.ok().build();
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public Response endBotConversation(String intent, String botUserId, String differConversationId) {
        restBotManagement.endCurrentConversation(intent, differConversationId);

        var memorySnapshot = restBotManagement.
                loadConversationMemory(intent, differConversationId,
                        false, true, Collections.emptyList());

        sendBotOutputToConversation(memorySnapshot.getConversationOutputs(), 0, botUserId, differConversationId);

        return Response.ok().build();
    }

    private void triggerCreateConversationCommand(CreateConversation createConversation) throws IOException {
        var command = new CreateConversationCommand(
                new Command.AuthContext(createConversation.getBotUserIdCreator()), getCurrentTime());

        command.setPayload(
                new CreateConversationCommand.Payload(
                        createConversation.getConversationName(),
                        createConversation.getParticipantIds()));

        publishCreateConversationCommand(command);
    }

    private void publishCreateConversationCommand(CreateConversationCommand createConversationCommand) throws IOException {
        differPublisher.publishCommandAndWaitForConfirm(new CommandInfo(
                CONVERSATION_EXCHANGE, CREATE_CONVERSATION_ROUTING_KEY,
                createConversationCommand, 0, 0, 0));
    }
}
