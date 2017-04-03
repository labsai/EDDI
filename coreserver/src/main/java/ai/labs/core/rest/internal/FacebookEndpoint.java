package ai.labs.core.rest.internal;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import ai.labs.resources.rest.bots.IBotStore;
import ai.labs.resources.rest.bots.model.BotConfiguration;
import ai.labs.rest.rest.IFacebookEndpoint;
import com.github.messenger4j.MessengerPlatform;
import com.github.messenger4j.exceptions.MessengerVerificationException;
import com.github.messenger4j.receive.MessengerReceiveClient;
import com.google.inject.assistedinject.Assisted;
import org.jboss.resteasy.plugins.guice.RequestScoped;

@RequestScoped
public class FacebookEndpoint implements IFacebookEndpoint {

	private final String botId;
	private final MessengerReceiveClient messengerReceiveClient;

	@Inject
	public FacebookEndpoint(@Assisted String botId, IBotStore botStore, ConversationCoordinator conversationCoordinator) {
		this.botId = botId;

		BotConfiguration botConfiguration;
		try {
			// TODO: needs caching
			botConfiguration = botStore.read(botId, null);
		} catch (Exception e) {
			throw new WebApplicationException("Could not read bot configuration", e);
		}

//		TODO retrieve appSecret + verificationToken from BotConfiguration/ChannelConfiguration
		String appSecret = null;
		String verificationToken = null;

		messengerReceiveClient = MessengerPlatform.newReceiveClientBuilder(appSecret, verificationToken)
				.onTextMessageEvent(event -> {
					// TODO need a way to continue conversation without response write
					// TODO need fb session handling, i.e. convert sender id to conversation id
//					conversationCoordinator.submitInOrder(conversationId, conversationInvocation)
				})
				.build();
	}

	@Override
	public Response webhook(String callbackPayload, String sha1PayloadSignature) {
		try {
			messengerReceiveClient.processCallbackPayload(callbackPayload, sha1PayloadSignature);
		} catch (MessengerVerificationException e) {
			throw new WebApplicationException("Error when processing callback payload", e);
		}

		return Response.ok().build();
	}

	@Override
	@GET
	public Response webhookSetup(@QueryParam("hub.mode") String mode,
								 @QueryParam("hub.verify_token") String verificationToken,
								 @QueryParam("hub.challenge") String challenge) {
		try {
			return Response.ok(messengerReceiveClient.verifyWebhook(mode, verificationToken, challenge)).build();
		} catch (MessengerVerificationException e) {
			return Response.status(Response.Status.FORBIDDEN).build();
		}
	}
}
