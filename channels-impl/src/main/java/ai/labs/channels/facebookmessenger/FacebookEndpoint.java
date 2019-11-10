package ai.labs.channels.facebookmessenger;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import ai.labs.httpclient.IHttpClient;
import ai.labs.httpclient.IRequest;
import ai.labs.httpclient.IResponse;
import ai.labs.models.Deployment;
import ai.labs.persistence.model.ResourceId;
import ai.labs.resources.rest.config.bots.IBotStore;
import ai.labs.resources.rest.config.bots.model.BotConfiguration;
import ai.labs.rest.restinterfaces.IRestBotEngine;
import ai.labs.rest.restinterfaces.factory.IRestInterfaceFactory;
import ai.labs.runtime.IBotFactory;
import ai.labs.runtime.SystemRuntime;
import ai.labs.runtime.service.ServiceException;
import ai.labs.utilities.RestUtilities;
import ai.labs.utilities.URIUtilities;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.messenger4j.MessengerPlatform;
import com.github.messenger4j.exceptions.MessengerApiException;
import com.github.messenger4j.exceptions.MessengerIOException;
import com.github.messenger4j.exceptions.MessengerVerificationException;
import com.github.messenger4j.receive.MessengerReceiveClient;
import com.github.messenger4j.receive.handlers.QuickReplyMessageEventHandler;
import com.github.messenger4j.receive.handlers.TextMessageEventHandler;
import com.github.messenger4j.send.MessengerSendClient;
import com.github.messenger4j.send.QuickReply;
import com.github.messenger4j.send.SenderAction;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static ai.labs.models.Deployment.Environment.unrestricted;
import static ai.labs.persistence.IResourceStore.ResourceNotFoundException;
import static ai.labs.persistence.IResourceStore.ResourceStoreException;
import static ai.labs.rest.restinterfaces.factory.RestInterfaceFactory.RestInterfaceFactoryException;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Slf4j
public class FacebookEndpoint implements IFacebookEndpoint {
    private static final String RESOURCE_URI_CHANNEL_CONNECTOR = "eddi://ai.labs.channel.facebook";
    private static final String AI_LABS_USER_AGENT = "Jetty 9.4/HTTP CLIENT - AI.LABS.EDDI";
    private static final String ENCODING = "UTF-8";
    private static final int EDDI_TIMEOUT = 10000;
    private static final String DELAY = "delay";

    private final SystemRuntime.IRuntime runtime;
    private final IBotStore botStore;
    private final IBotFactory botFactory;
    private final IHttpClient httpClient;
    private final IRestInterfaceFactory restInterfaceFactory;
    private final String apiServerURI;
    private final ICache<String, String> conversationIdCache;
    private final ICache<BotResourceId, MessengerClient> messengerClientCache;

    @Inject
    public FacebookEndpoint(SystemRuntime.IRuntime runtime,
                            IBotStore botStore,
                            IBotFactory botFactory,
                            IHttpClient httpClient,
                            IRestInterfaceFactory restInterfaceFactory,
                            @Named("system.apiServerURI") String apiServerURI,
                            ICacheFactory cacheFactory) {
        this.runtime = runtime;
        this.botStore = botStore;
        this.botFactory = botFactory;
        this.httpClient = httpClient;
        this.restInterfaceFactory = restInterfaceFactory;
        this.apiServerURI = apiServerURI;
        this.conversationIdCache = cacheFactory.getCache("facebook.conversationIds");
        this.messengerClientCache = cacheFactory.getCache("facebook.messengerReceiveClients");
    }

    private MessengerClient getMessageClient(String botId, Integer botVersion)
            throws ResourceNotFoundException, ResourceStoreException, ServiceException {

        if (botVersion == -1) {
            botVersion = getLatestDeployedBotVersion(botId);
        }

        final BotResourceId botResourceId = new BotResourceId(botId, botVersion);
        if (!messengerClientCache.containsKey(botResourceId)) {
            messengerClientCache.put(botResourceId, createMessageClient(botId, botVersion));
        }

        return messengerClientCache.get(botResourceId);
    }

    private Integer getLatestDeployedBotVersion(String botId) throws ServiceException {
        return botFactory.getLatestBot(unrestricted, botId).getBotVersion();
    }

    private MessengerClient createMessageClient(String botId, Integer botVersion)
            throws ResourceNotFoundException, ResourceStoreException {

        final BotConfiguration botConfiguration = botStore.read(botId, botVersion);

        String appSecret = null;
        String verificationToken = null;
        String pageAccessToken = null;
        for (BotConfiguration.ChannelConnector channelConnector : botConfiguration.getChannels()) {
            if (channelConnector.getType().toString().equals(RESOURCE_URI_CHANNEL_CONNECTOR)) {
                Map<String, String> channelConnectorConfig = channelConnector.getConfig();
                appSecret = channelConnectorConfig.get("appSecret");
                verificationToken = channelConnectorConfig.get("verificationToken");
                pageAccessToken = channelConnectorConfig.get("pageAccessToken");
                break;
            }
        }

        if (appSecret == null || verificationToken == null || pageAccessToken == null) {
            throw new IllegalArgumentException("appSecret, verificationToken and pageAccessToken must not be <null>.");
        }

        return new MessengerClient(MessengerPlatform.newSendClientBuilder(pageAccessToken).build(),
                MessengerPlatform.newReceiveClientBuilder(appSecret, verificationToken)
                        .onTextMessageEvent(createTextMessageEventHandler(botId, botVersion, unrestricted))
                        .onQuickReplyMessageEvent(createQuickMessageEventHandler(botId, botVersion, unrestricted))
                        .build());
    }

    private TextMessageEventHandler createTextMessageEventHandler(String botId, Integer botVersion,
                                                                  Deployment.Environment environment) {
        return event -> {
            try {
                String message = event.getText();
                log.info("fb message text: {}", message);
                String senderId = event.getSender().getId();
                final String conversationId = getConversationId(environment, botId, senderId);
                say(environment, botId, botVersion, conversationId, senderId, message);

            } catch (Exception e) {
                log.error(e.getLocalizedMessage(), e);
            }
        };
    }

    private QuickReplyMessageEventHandler createQuickMessageEventHandler(String botId, Integer botVersion,
                                                                         Deployment.Environment environment) {
        return event -> {
            try {
                String message = event.getQuickReply().getPayload();
                String senderId = event.getSender().getId();
                log.info("fb quick message text: {}", message);
                final String conversationId = getConversationId(environment, botId, senderId);
                say(environment, botId, botVersion, conversationId, senderId, message);

            } catch (Exception e) {
                log.error(e.getLocalizedMessage(), e);
            }
        };
    }

    private void say(Deployment.Environment environment,
                     String botId,
                     Integer botVersion,
                     String conversationId,
                     String senderId,
                     String message)
            throws IRequest.HttpRequestException, ResourceNotFoundException, ResourceStoreException, ServiceException, InterruptedException, ExecutionException, TimeoutException {

        URI uri = RestUtilities.createURI(
                apiServerURI, "/bots/",
                environment, "/",
                botId, "/",
                conversationId);

        final MessengerSendClient sendClient = getMessageClient(botId, botVersion).getSendClient();
        try {
            sendClient.sendSenderAction(senderId, SenderAction.TYPING_ON);
        } catch (MessengerApiException | MessengerIOException e) {
            log.error(e.getLocalizedMessage(), e);
        }

        final String jsonRequestBody = "{ \"input\": \"" + message + "\", \"context\": {} }";
        IResponse httpResponse = httpClient.newRequest(uri, IHttpClient.Method.POST)
                .setUserAgent(AI_LABS_USER_AGENT)
                .setTimeout(EDDI_TIMEOUT, MILLISECONDS)
                .setBodyEntity(jsonRequestBody, ENCODING, MediaType.APPLICATION_JSON)
                .send();
        log.debug("response: {}", httpResponse.getContentAsString());
        final List<Output> outputs = getOutputText(httpResponse.getContentAsString());
        final List<Map<String, String>> quickReplies = getQuickReplies(httpResponse.getContentAsString());

        final List<QuickReply> fbQuickReplies = new ArrayList<>();
        if (!quickReplies.isEmpty()) {
            QuickReply.ListBuilder listBuilder = QuickReply.newListBuilder();
            for (Map<String, String> quickReply : quickReplies) {
                listBuilder.addTextQuickReply(quickReply.get("value"), quickReply.get("value")).toList();
            }
            fbQuickReplies.addAll(listBuilder.build());
        }

        long delay = 0;
        for (Output output : outputs) {
            if (DELAY.equals(output.getType())) {
                delay += Long.parseLong(output.getValue());
                continue;
            }
            runtime.submitScheduledCallable(() -> {
                try {
                    if (!fbQuickReplies.isEmpty()) {
                        sendClient.sendTextMessage(senderId, output.getValue(), fbQuickReplies);
                    } else {
                        sendClient.sendTextMessage(senderId, output.getValue());
                    }
                } catch (MessengerIOException | MessengerApiException e) {
                    log.error(e.getLocalizedMessage(), e);
                }
                return null;
            }, delay, MILLISECONDS, null).get(EDDI_TIMEOUT, MILLISECONDS);
            delay = 0;
        }

        try {
            sendClient.sendSenderAction(senderId, SenderAction.TYPING_OFF);
        } catch (MessengerApiException | MessengerIOException e) {
            log.error(e.getLocalizedMessage(), e);
        }

        final String state = getConversationState(httpResponse.getContentAsString());
        if (state != null && !state.equals("READY")) {
            conversationIdCache.remove(senderId);
        }
    }

    private List<Map<String, String>> getQuickReplies(String json) {
        List<Map<String, String>> output = new ArrayList<>();

        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode rootNode = mapper.readValue(json, JsonNode.class);
            JsonNode conversationStepsArray = rootNode.path("conversationSteps");
            for (JsonNode conversationStep : conversationStepsArray) {
                for (JsonNode conversationStepValues : conversationStep.get("conversationStep")) {
                    if (conversationStepValues.get("key") != null && conversationStepValues.get("key").asText().startsWith("quickReplies")) {
                        if (conversationStepValues.get("value").isArray()) {
                            for (JsonNode node : conversationStepValues.get("value")) {
                                HashMap<String, String> map = new HashMap<>();
                                map.put("value", node.get("value").asText());
                                map.put("expressions", node.get("expressions").asText());
                                output.add(map);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("json parsing error", e);
        }

        return output;
    }

    private List<Output> getOutputText(String json) {

        List<Output> output = new ArrayList<>();

        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode rootNode = mapper.readValue(json, JsonNode.class);
            JsonNode conversationStepsArray = rootNode.path("conversationSteps");
            for (JsonNode conversationStep : conversationStepsArray) {
                for (JsonNode conversationStepValues : conversationStep.get("conversationStep")) {
                    if (conversationStepValues.get("key") != null) {
                        if (conversationStepValues.get("key").asText().startsWith("output:text")) {
                            output.add(new Output("text", conversationStepValues.get("value").asText()));
                        } else if (conversationStepValues.get("key").asText().startsWith("output:" + DELAY)) {
                            output.add(new Output(DELAY, conversationStepValues.get("value").asText()));
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("json parsing error", e);
        }

        return output;
    }

    private String getConversationState(String json) {
        String state = null;

        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode rootNode = mapper.readValue(json, JsonNode.class);
            JsonNode conversationState = rootNode.path("conversationState");
            if (conversationState != null) {
                state = conversationState.asText();
            }
        } catch (IOException e) {
            log.error("json parsing error", e);
        }

        return state;
    }


    private String getConversationId(Deployment.Environment environment, String botId, String senderId)
            throws RestInterfaceFactoryException {

        String conversationId;
        if ((conversationId = conversationIdCache.get(senderId)) == null) {
            conversationId = createConversation(environment, botId, senderId);
            conversationIdCache.put(senderId, conversationId);
        }

        return conversationId;
    }

    private String createConversation(Deployment.Environment environment, String botId, String senderId)
            throws RestInterfaceFactoryException {
        String conversationId;
        try {
            Response response = restInterfaceFactory.get(IRestBotEngine.class).
                    startConversation(environment, botId, senderId);
            if (response.getStatus() == 201) {
                ResourceId resourceIdConversation =
                        URIUtilities.extractResourceId(response.getLocation());
                conversationId = resourceIdConversation.getId();
                conversationIdCache.put(senderId, conversationId);
                return conversationId;
            }
            Exception e = new Exception("bot (id:" + botId + ") is not deployed");
            throw new RestInterfaceFactoryException(e.getLocalizedMessage(), e);
        } catch (RestInterfaceFactoryException e) {
            log.error(e.getLocalizedMessage(), e);
            throw e;
        }
    }

    @Override
    public Response webHook(final String botId, final Integer botVersion,
                            final String callbackPayload, final String sha1PayloadSignature) {
        SystemRuntime.getRuntime().submitCallable((Callable<Void>) () -> {
            try {
                log.info("web hook called");
                getMessageClient(botId, botVersion).getReceiveClient().
                        processCallbackPayload(callbackPayload, sha1PayloadSignature);
            } catch (MessengerVerificationException e) {
                log.error(e.getLocalizedMessage(), e);
                throw new InternalServerErrorException("Error when processing callback payload");
            }
            return null;
        }, null);

        return Response.ok().build();
    }

    @Override
    public Response webHookSetup(final String botId, final Integer botVersion, final String mode,
                                 final String verificationToken, final String challenge) {
        try {
            log.info("web hook setup called");
            return Response.ok(
                    getMessageClient(botId, botVersion).
                            getReceiveClient().
                            verifyWebhook(mode, verificationToken, challenge)
            ).build();
        } catch (MessengerVerificationException e) {
            log.error(e.getLocalizedMessage(), e);
            return Response.status(Response.Status.FORBIDDEN).build();
        } catch (ServiceException | ResourceStoreException | ResourceNotFoundException e) {
            log.error(e.getLocalizedMessage(), e);
            return Response.serverError().build();
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    private static class MessengerClient {
        private MessengerSendClient sendClient;
        private MessengerReceiveClient receiveClient;
    }

    @Getter
    @AllArgsConstructor
    @EqualsAndHashCode
    private static class BotResourceId {
        private final String id;
        private final Integer version;
    }

    @AllArgsConstructor
    @Getter
    @Setter
    private static class Output {
        private String type;
        private String value;
    }
}
