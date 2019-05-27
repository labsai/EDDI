package ai.labs.xmpp.endpoint;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import ai.labs.memory.model.ConversationOutput;
import ai.labs.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.models.ConversationState;
import ai.labs.models.Deployment;
import ai.labs.models.DocumentDescriptor;
import ai.labs.resources.rest.bots.IBotStore;
import ai.labs.resources.rest.bots.IRestBotStore;
import ai.labs.resources.rest.bots.model.BotConfiguration;
import ai.labs.rest.rest.IRestBotEngine;
import ai.labs.runtime.IBotFactory;
import ai.labs.runtime.service.ServiceException;
import ai.labs.utilities.URIUtilities;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.chat2.Chat;
import org.jivesoftware.smack.chat2.ChatManager;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jxmpp.jid.EntityBareJid;

import javax.inject.Inject;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.TimeoutHandler;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static ai.labs.models.Deployment.Environment.unrestricted;
import static ai.labs.persistence.IResourceStore.ResourceNotFoundException;
import static ai.labs.persistence.IResourceStore.ResourceStoreException;
import static ai.labs.rest.restinterfaces.RestInterfaceFactory.RestInterfaceFactoryException;

@Slf4j
public class XmppEndpoint implements IXmppEndpoint {
    private static final String RESOURCE_URI_CHANNEL_CONNECTOR = "eddi://ai.labs.channel.xmpp";

    private final IBotStore botStore;
    private final IBotFactory botFactory;
    private final IRestBotStore restBotStore;
    private final IRestBotEngine restBotEngine;
    private final ICache<String, String> conversationIdCache;

    private XMPPTCPConnection connection;
    private String configuredBotId;


    @Inject
    public XmppEndpoint(IBotStore botStore,
                        IBotFactory botFactory,
                        IRestBotStore restBotStore,
                        IRestBotEngine restBotEngine,
                        ICacheFactory cacheFactory) {
        this.botStore = botStore;
        this.botFactory = botFactory;
        this.restBotStore = restBotStore;
        this.restBotEngine = restBotEngine;

        this.conversationIdCache = cacheFactory.getCache("xmpp.conversationIds");
        log.info("XMPP STARTING");
    }

    public void init() {
        try {
            List<DocumentDescriptor> documentDescriptors = restBotStore.readBotDescriptors("", 0, 20);
            for (DocumentDescriptor documentDescriptor : documentDescriptors) {
                URIUtilities.ResourceId resourceId = URIUtilities.extractResourceId(documentDescriptor.getResource());
                String botId = resourceId.getId();
                Integer version = getLatestDeployedBotVersion(botId);
                BotConfiguration botConfiguration = botStore.read(botId, version);
                for (BotConfiguration.ChannelConnector channelConnector : botConfiguration.getChannels()) {
                    if (channelConnector.getType().toString().equals(RESOURCE_URI_CHANNEL_CONNECTOR)) {
                        Map<String, String> channelConnectorConfig = channelConnector.getConfig();
                        String username = channelConnectorConfig.get("username");
                        String password = channelConnectorConfig.get("password");
                        String hostname = channelConnectorConfig.get("hostname");

                        log.info("opening connection to: {}", hostname);
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
        } catch (ServiceException e) {
            log.error("service exception on getting bot version", e);
        } catch (ResourceStoreException e) {
            log.error("resource store exception", e);
        } catch (ResourceNotFoundException e) {
            log.error("botId not found", e);
        } catch (InterruptedException e) {
            log.error("smack connection interruped", e);
        } catch (IOException e) {
            log.error("smack io exception", e);
        } catch (SmackException e) {
            log.error("smack exception", e);
        } catch (XMPPException e) {
            log.error("xmpp exception", e);
        }
    }

    private Integer getLatestDeployedBotVersion(String botId) throws ServiceException {
        return botFactory.getLatestBot(unrestricted, botId) != null ?
                botFactory.getLatestBot(unrestricted, botId).getBotVersion() : Integer.valueOf(1);
    }

    private void say(Deployment.Environment environment,
                     String botId,
                     String conversationId,
                     String senderId,
                     String message,
                     Chat chat) {

        final String jsonRequestBody = "{ \"input\": \"" + message + "\", \"context\": {} }";

        restBotEngine.say(environment, botId, conversationId, false, true,
                Collections.emptyList(), jsonRequestBody,
                new AsyncDummy() {
                    @Override
                    public boolean resume(Object response) {
                        try {
                            SimpleConversationMemorySnapshot memorySnapshot = (SimpleConversationMemorySnapshot) response;

                            for (ConversationOutput conversationOutput : memorySnapshot.getConversationOutputs()) {
                                List outputs = conversationOutput.get("output", List.class);
                                for (Object output : outputs) {
                                    chat.send(output.toString());

                                }
                            }

                            final ConversationState state = memorySnapshot.getConversationState();
                            if (state != null && !state.equals(ConversationState.READY)) {
                                conversationIdCache.remove(senderId);
                            }
                            return true;
                        } catch (SmackException.NotConnectedException e) {
                            log.error("not connected to xmpp server", e);
                        } catch (InterruptedException e) {
                            log.error("interrupted exception", e);
                        }

                        return false;
                    }
                });
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
            Response response = restBotEngine.startConversation(environment, botId, senderId);
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
                conversationId = createConversation(unrestricted, configuredBotId, entityBareJid.asEntityBareJidString());
            }
            say(unrestricted, configuredBotId, conversationId, entityBareJid.asEntityBareJidString(), message.getBody(), chat);
        } catch (RestInterfaceFactoryException e) {
            log.error("rest interface exception", e);
        }
    }

    private class AsyncDummy implements AsyncResponse {

        @Override
        public boolean resume(Object response) {
            return false;
        }

        @Override
        public boolean resume(Throwable response) {
            return false;
        }

        @Override
        public boolean cancel() {
            return false;
        }

        @Override
        public boolean cancel(int retryAfter) {
            return false;
        }

        @Override
        public boolean cancel(Date retryAfter) {
            return false;
        }

        @Override
        public boolean isSuspended() {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public boolean setTimeout(long time, TimeUnit unit) {
            return false;
        }

        @Override
        public void setTimeoutHandler(TimeoutHandler handler) {

        }

        @Override
        public Collection<Class<?>> register(Class<?> callback) {
            return null;
        }

        @Override
        public Map<Class<?>, Collection<Class<?>>> register(Class<?> callback, Class<?>... callbacks) {
            return null;
        }

        @Override
        public Collection<Class<?>> register(Object callback) {
            return null;
        }

        @Override
        public Map<Class<?>, Collection<Class<?>>> register(Object callback, Object... callbacks) {
            return null;
        }
    }
}
