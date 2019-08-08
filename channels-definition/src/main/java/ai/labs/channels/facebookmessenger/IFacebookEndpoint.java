package ai.labs.channels.facebookmessenger;

import io.swagger.annotations.Api;
import io.swagger.annotations.Authorization;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/channels/facebook")
@Api(value = "Bot Engine -> Channels", authorizations = {@Authorization(value = "eddi_auth")})
public interface IFacebookEndpoint {
    String RESOURCE_URI_FACEBOOK_CHANNEL_CONNECTOR = "eddi://ai.labs.channel.facebook";

    @POST
    @Path("{botId}")
    @Consumes(MediaType.APPLICATION_JSON)
    Response webHook(@PathParam("botId") String botId,
                     @QueryParam("version") @DefaultValue("-1") Integer version,
                     String callbackPayload,
                     @HeaderParam("X-Hub-Signature") String sha1PayloadSignature);

    @GET
    @Path("{botId}")
    Response webHookSetup(@PathParam("botId") String botId,
                          @QueryParam("version") @DefaultValue("-1") Integer version,
                          @QueryParam("hub.mode") String mode,
                          @QueryParam("hub.verify_token") String verificationToken,
                          @QueryParam("hub.challenge") String challenge);
}
