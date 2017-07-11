package ai.labs.rest.rest;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

public interface IFacebookEndpoint {

	@POST
	@Consumes(MediaType.APPLICATION_JSON)
    Response webHook(String callbackPayload, @HeaderParam("X-Hub-Signature") String sha1PayloadSignature);

	@GET
    Response webHookSetup(@QueryParam("hub.mode") String mode,
                          @QueryParam("hub.verify_token") String verificationToken,
                          @QueryParam("hub.challenge") String challenge);
}
