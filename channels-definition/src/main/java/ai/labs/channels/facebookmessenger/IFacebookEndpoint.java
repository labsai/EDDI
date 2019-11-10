package ai.labs.channels.facebookmessenger;

import io.swagger.annotations.Api;
import io.swagger.annotations.Authorization;

import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/channels/facebook")
@Api(value = "Configurations -> (6) Channels --> Facebook", authorizations = {@Authorization(value = "eddi_auth")})
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
