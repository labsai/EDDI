package ai.labs.core.rest.internal;

import ai.labs.caching.ICache;
import ai.labs.caching.ICacheFactory;
import ai.labs.memory.model.Deployment;
import ai.labs.resources.rest.bots.IBotStore;
import ai.labs.resources.rest.bots.model.BotConfiguration;
import ai.labs.resources.rest.bots.model.ChannelConnector;
import ai.labs.rest.rest.IFacebookEndpoint;
import ai.labs.rest.rest.IRestBotEngine;
import ai.labs.rest.restinterfaces.IRestInterfaceFactory;
import ai.labs.rest.restinterfaces.RestInterfaceFactory;
import ai.labs.utilities.URIUtilities;
import com.github.messenger4j.MessengerPlatform;
import com.github.messenger4j.exceptions.MessengerVerificationException;
import com.github.messenger4j.receive.MessengerReceiveClient;
import com.google.inject.assistedinject.Assisted;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.plugins.guice.RequestScoped;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.util.Map;

@RequestScoped
@Slf4j
public class FacebookEndpoint implements IFacebookEndpoint {
    public static final String RESOURCE_URI_CHANNELCONNECTOR = "eddi://ai.labs.channelconnector";
    private final MessengerReceiveClient messengerReceiveClient;

    @Inject
    public FacebookEndpoint(@Assisted String botId, IBotStore botStore,
                            IRestInterfaceFactory restInterfaceFactory,
                            @Named("coreServerURI") String coreServerURI,
                            ConversationCoordinator conversationCoordinator,
                            ICacheFactory cacheFactory) {
        ICache<String, BotConfiguration> botConfigCache =
                cacheFactory.getCache("facebook.botConfiguration");

        ICache<String, String> conversationIdCache =
                cacheFactory.getCache("facebook.conversationIds");

        BotConfiguration botConfiguration;
        try {
            Integer botVersion = botStore.getCurrentResourceId(botId).getVersion();
            String cacheKey = botId + ":" + botVersion;
            if (!botConfigCache.containsKey(cacheKey)) {
                botConfiguration = botStore.read(botId, botVersion);
                botConfigCache.put(cacheKey, botConfiguration);
            } else {
                botConfiguration = botConfigCache.get(cacheKey);
            }
        } catch (Exception e) {
            throw new WebApplicationException("Could not read bot configuration", e);
        }

        String appSecret = null;
        String verificationToken = null;
        for (ChannelConnector channelConnector : botConfiguration.getChannels()) {
            if (channelConnector.getType().toString().equals(RESOURCE_URI_CHANNELCONNECTOR)) {
                Map<String, String> channelConnectorConfig = channelConnector.getConfig();
                appSecret = channelConnectorConfig.get("facebook.appSecret");
                verificationToken = channelConnectorConfig.get("facebook.verificationToken");
                break;
            }
        }

        if (appSecret == null || verificationToken == null) {
            throw new IllegalArgumentException("appSecret and verificationToken must not be <null>.");
        }

        final Deployment.Environment environment = Deployment.Environment.unrestricted;

        messengerReceiveClient = MessengerPlatform.newReceiveClientBuilder(appSecret, verificationToken)
                .onTextMessageEvent(event -> {
                    // TODO need a way to continue conversation without response write
                    // TODO need fb session handling, i.e. convert sender id to conversation id

                    String message = event.getText();
                    String senderId = event.getSender().getId();
                    String conversationId = conversationIdCache.get(senderId);
                    if (conversationId == null) {
                        try {
                            Response response = restInterfaceFactory.get(IRestBotEngine.class, coreServerURI).
                                    startConversation(environment, botId);
                            URIUtilities.ResourceId resourceIdConversation =
                                    URIUtilities.extractResourceId(response.getLocation());
                            conversationId = resourceIdConversation.getId();
                            conversationIdCache.put(senderId, conversationId);
                        } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
                            log.error(e.getLocalizedMessage(), e);
                        }
                    }


                    final String finalConversationId = conversationId;
                    conversationCoordinator.submitInOrder(conversationId, () -> {
                        try {
                            restInterfaceFactory.get(IRestBotEngine.class, coreServerURI).
                                    say(environment, botId, finalConversationId, message, null);
                        } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
                            log.error(e.getLocalizedMessage(), e);
                        }
                        return null;
                    });
                })
                .build();
    }

    @Override
    public Response webHook(String callbackPayload, String sha1PayloadSignature) {
        try {
            messengerReceiveClient.processCallbackPayload(callbackPayload, sha1PayloadSignature);
            return Response.ok().build();
        } catch (MessengerVerificationException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Error when processing callback payload");
        }
    }

    @Override
    public Response webHookSetup(String mode, String verificationToken, String challenge) {
        try {
            return Response.ok(messengerReceiveClient.verifyWebhook(mode, verificationToken, challenge)).build();
        } catch (MessengerVerificationException e) {
            log.error(e.getLocalizedMessage(), e);
            return Response.status(Response.Status.FORBIDDEN).build();
        }
    }
}
