package ai.labs.xmpp.endpoint;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import ai.labs.httpclient.IHttpClient;
import ai.labs.httpclient.IRequest;
import ai.labs.httpclient.IResponse;
import ai.labs.memory.model.Deployment;
import ai.labs.resources.rest.bots.IBotStore;
import ai.labs.resources.rest.bots.model.BotConfiguration;
import ai.labs.rest.rest.IRestBotEngine;
import ai.labs.rest.restinterfaces.IRestInterfaceFactory;
import ai.labs.runtime.IBotFactory;
import ai.labs.runtime.SystemRuntime;
import ai.labs.runtime.service.ServiceException;
import ai.labs.utilities.RestUtilities;
import ai.labs.utilities.URIUtilities;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.plugins.guice.RequestScoped;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.chat2.Chat;
import org.jivesoftware.smack.chat2.ChatManager;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jxmpp.jid.EntityBareJid;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static ai.labs.memory.model.Deployment.Environment.unrestricted;
import static ai.labs.persistence.IResourceStore.ResourceNotFoundException;
import static ai.labs.persistence.IResourceStore.ResourceStoreException;
import static ai.labs.rest.restinterfaces.RestInterfaceFactory.RestInterfaceFactoryException;

@Slf4j
public class XmppEndpoint implements IXmppEndpoint {
    private static final String RESOURCE_URI_CHANNEL_CONNECTOR = "eddi://ai.labs.channel.xmpp";
    private static final String AI_LABS_USER_AGENT = "Jetty 9.4/HTTP CLIENT - AI.LABS.EDDI";
    private static final String ENCODING = "UTF-8";
    private static final int EDDI_TIMEOUT = 10000;


    private final IBotStore botStore;
    private final IBotFactory botFactory;
    private final IHttpClient httpClient;
    private final IRestInterfaceFactory restInterfaceFactory;
    private final String apiServerURI;
    private final ICache<String, String> conversationIdCache;

    private XMPPTCPConnection connection;
    private String configuredBotId;


    @Inject
    public XmppEndpoint(IBotStore botStore,
                            IBotFactory botFactory,
                            IHttpClient httpClient,
                            IRestInterfaceFactory restInterfaceFactory,
                            @Named("system.apiServerURI") String apiServerURI,
                            ICacheFactory cacheFactory) {
        this.botStore = botStore;
        this.botFactory = botFactory;
        this.httpClient = httpClient;
        this.restInterfaceFactory = restInterfaceFactory;
        this.apiServerURI = apiServerURI;

        this.conversationIdCache = cacheFactory.getCache("xmpp.conversationIds");
        log.info("XMPP STARTING");
    }

    public void init() {
        URI uri = RestUtilities.createURI(apiServerURI, "/botstore/bots/descriptors");
        try {
            IResponse httpResponse = httpClient.newRequest(uri, IHttpClient.Method.GET)
                    .setUserAgent(AI_LABS_USER_AGENT)
                    .setTimeout(EDDI_TIMEOUT, TimeUnit.MILLISECONDS)
                    .send();
            List<String> botids = extractBotIdsFromResponse(httpResponse.getContentAsString());
            for (String botId : botids) {
                Integer version = getLatestDeployedBotVersion(botId);
                BotConfiguration botConfiguration = botStore.read(botId, version);
                for (BotConfiguration.ChannelConnector channelConnector : botConfiguration.getChannels()) {
                    if (channelConnector.getType().toString().equals(RESOURCE_URI_CHANNEL_CONNECTOR)) {
                        Map<String, String> channelConnectorConfig = channelConnector.getConfig();
                        String username = channelConnectorConfig.get("username");
                        String password = channelConnectorConfig.get("password");
                        String hostname = channelConnectorConfig.get("hostname");

                        log.info("opening connection to: {}", hostname );
                        XMPPTCPConnectionConfiguration config = XMPPTCPConnectionConfiguration.builder()
                                .setUsernameAndPassword(username, password)
                                .setHost(hostname)
                                .setHostAddress(InetAddress.getByName(hostname))
                                .setXmppDomain(hostname)
                                .setPort(5222)
                                .setSecurityMode(ConnectionConfiguration.SecurityMode.ifpossible)
                                .setDebuggerEnabled(false)
                                .build();

                        connection = new XMPPTCPConnection(config);
                        connection.connect().login();
                        ChatManager chatManager = ChatManager.getInstanceFor(connection);
                        chatManager.addIncomingListener(this);
                        configuredBotId = botId;
                    }
                }
            }
        } catch (IRequest.HttpRequestException e) {
            log.error("error getting all bots", e);
        } catch (ServiceException e) {
            log.error("service exception on getting bot version", e);
        } catch (ResourceStoreException e) {
            log.error("resource store exception", e);
        } catch (ResourceNotFoundException e) {
            log.error("botid not found", e);
        } catch (InterruptedException e) {
            log.error("smack connection interruped" ,e);
        } catch (IOException e) {
            log.error("smack io exception", e);
        } catch (SmackException e) {
            log.error("smack exception", e);
        } catch (XMPPException e) {
            log.error("xmpp exception", e);
        }
    }

    private List<String> extractBotIdsFromResponse(String json) {
        List<String> botIds = new ArrayList<>();

        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode rootNode = mapper.readValue(json, JsonNode.class);
            for (JsonNode bot : rootNode) {
                if (bot.get("resource") != null) {
                    String[] urlParts = bot.get("resource").asText().split("\\/");
                    if (urlParts.length > 0) {
                        String[] requestParts = urlParts[urlParts.length-1].split("\\?");
                        botIds.add(requestParts[0]);
                        log.debug("added botid: {}" + requestParts[0]);
                    }
                }
            }
        } catch (IOException e) {
            log.error("json parsing error", e);
        }

        return botIds;
    }

    private Integer getLatestDeployedBotVersion(String botId) throws ServiceException {
        return botFactory.getLatestBot(unrestricted, botId).getVersion();
    }

    private void say(Deployment.Environment environment,
                     String botId,
                     Integer botVersion,
                     String conversationId,
                     String senderId,
                     String message,
                     Chat chat)
            throws IRequest.HttpRequestException, ResourceNotFoundException, ResourceStoreException, ServiceException {

        URI uri = RestUtilities.createURI(
                apiServerURI, "/bots/",
                environment, "/",
                botId, "/",
                conversationId);


        final String jsonRequestBody = "{ \"input\": \"" + message + "\", \"context\": {} }";
        try {
            IResponse httpResponse = httpClient.newRequest(uri, IHttpClient.Method.POST)
                    .setUserAgent(AI_LABS_USER_AGENT)
                    .setTimeout(EDDI_TIMEOUT, TimeUnit.MILLISECONDS)
                    .setBodyEntity(jsonRequestBody, ENCODING, MediaType.APPLICATION_JSON)
                    .send();
            log.debug("response: {}", httpResponse.getContentAsString());
            final List<String> output = getOutputText(httpResponse.getContentAsString());

            for (String outputText : output) {
                chat.send(outputText);
            }

            final String state = getConversationState(httpResponse.getContentAsString());
            if (state != null && !state.equals("READY")) {
                conversationIdCache.remove(senderId);
            }
        } catch (SmackException.NotConnectedException e) {
            log.error("not connected to xmpp server", e);
        } catch (InterruptedException e) {
            log.error("interrupted exception", e);
        }
    }

    private List<String> getOutputText(String json) {

        List<String> output = new ArrayList<>();

        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode rootNode = mapper.readValue(json, JsonNode.class);
            JsonNode conversationStepsArray = rootNode.path("conversationSteps");
            for (JsonNode conversationStep : conversationStepsArray) {
                for (JsonNode conversationStepValues : conversationStep.get("conversationStep")) {
                    if (conversationStepValues.get("key") != null && conversationStepValues.get("key").asText().startsWith("output:text")) {
                        output.add(conversationStepValues.get("value").asText());
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
            Response response = restInterfaceFactory.get(IRestBotEngine.class, apiServerURI).
                    startConversation(environment, botId);
            if (response.getStatus() == 201) {
                URIUtilities.ResourceId resourceIdConversation =
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
    public void newIncomingMessage(EntityBareJid entityBareJid, Message message, Chat chat) {
        try {
            log.info("xmpp incoming: {}", message.getBody());
            String conversationId = getConversationId(unrestricted, configuredBotId, entityBareJid.asEntityBareJidString());
            if (conversationId == null) {
                conversationId = createConversation(unrestricted,configuredBotId, entityBareJid.asEntityBareJidString());
            }
            say(unrestricted, configuredBotId, getLatestDeployedBotVersion(configuredBotId), conversationId, entityBareJid.asEntityBareJidString(), message.getBody(), chat);
        } catch (RestInterfaceFactoryException e) {
            log.error("rest interface exception", e);
        } catch (ResourceNotFoundException e) {
            log.error("resource not found", e);
        } catch (ServiceException e) {
            log.error("service excption", e);
        } catch (IRequest.HttpRequestException e) {
            log.error("http exception", e);
        } catch (ResourceStoreException e) {
            log.error("resource store exception", e);
        }
    }

}
