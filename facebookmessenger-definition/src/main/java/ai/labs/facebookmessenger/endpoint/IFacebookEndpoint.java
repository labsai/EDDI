package ai.labs.facebookmessenger.endpoint;

import io.swagger.annotations.Api;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/channels/facebook")
@Api(description = "channels")
public interface IFacebookEndpoint {
    @POST
    @Path("{botId}")
    @Consumes(MediaType.APPLICATION_JSON)
    Response webHook(@PathParam("botId") String botId, String callbackPayload,
                     @HeaderParam("X-Hub-Signature") String sha1PayloadSignature);

    @GET
    @Path("{botId}")
    Response webHookSetup(@PathParam("botId") String botId,
                          @QueryParam("hub.mode") String mode,
                          @QueryParam("hub.verify_token") String verificationToken,
                          @QueryParam("hub.challenge") String challenge);
}
