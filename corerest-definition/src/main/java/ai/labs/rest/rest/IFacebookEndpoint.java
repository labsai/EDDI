package ai.labs.rest.rest;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.InputStream;

public interface IFacebookEndpoint {

	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	Response webhook(String callbackPayload, @HeaderParam("X-Hub-Signature") String sha1PayloadSignature);

	@GET
	Response webhookSetup(@QueryParam("hub.mode") String mode,
						  @QueryParam("hub.verify_token") String verificationToken,
						  @QueryParam("hub.challenge") String challenge);
}
