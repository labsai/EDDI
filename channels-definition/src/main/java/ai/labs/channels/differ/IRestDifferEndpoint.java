package ai.labs.channels.differ;

import ai.labs.channels.config.model.ChannelDefinition;
import ai.labs.channels.differ.model.CreateConversation;
import io.swagger.annotations.Api;
import io.swagger.annotations.Authorization;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

@Path("/channels/differ")
@Api(value = "Configurations -> (6) Channels --> Differ", authorizations = {@Authorization(value = "eddi_auth")})
public interface IRestDifferEndpoint {
    String RESOURCE_URI_DIFFER_CHANNEL_CONNECTOR = "eddi://ai.labs.channel.differ";

    void init(ChannelDefinition channelDefinition);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/createConversation")
    void triggerConversationCreated(CreateConversation createConversation);

    @POST
    @Path("/endBotConversation")
    void endBotConversation(@QueryParam("intent") String intent,
                            @QueryParam("botUserId") String botUserId,
                            @QueryParam("differConversationId") String differConversationId);
}


