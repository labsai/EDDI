package ai.labs.resources.rest.botmanagement;

import ai.labs.models.UserConversation;
import io.swagger.annotations.Api;
import io.swagger.annotations.Authorization;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Api(value = "Configurations -> (5) Bot Management", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/userconversationstore/userconversations")
public interface IRestUserConversationStore {
    String resourceURI = "eddi://ai.labs.userconversation/userconversationstore/userconversations/";

    @GET
    @Path("/{intent}/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    UserConversation readUserConversation(@PathParam("intent") String intent, @PathParam("userId") String userId);

    @POST
    @Path("/{intent}/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    Response createUserConversation(@PathParam("intent") String intent, @PathParam("userId") String userId,
                                    UserConversation userConversation);

    @DELETE
    @Path("/{intent}/{userId}")
    Response deleteUserConversation(@PathParam("intent") String intent, @PathParam("userId") String userId);
}
