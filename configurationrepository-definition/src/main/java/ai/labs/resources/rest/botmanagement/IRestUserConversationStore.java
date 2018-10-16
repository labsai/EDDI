package ai.labs.resources.rest.botmanagement;

import ai.labs.models.UserConversation;
import io.swagger.annotations.Api;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

@Api(value = "configurations")
@Path("/bottriggerstore/bottriggers")
public interface IRestUserConversationStore {
    String resourceURI = "eddi://ai.labs.userconversation/userconversationstore/userconversations/";

    @GET
    @Path("/{intent}/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    UserConversation readUserConversation(@PathParam("intent") String intent, @PathParam("userId") String userId);

    @POST
    @Path("/{intent}/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    void createUserConversation(@PathParam("intent") String intent, @PathParam("userId") String userId,
                                UserConversation userConversation);

    @DELETE
    @Path("/{intent}/{userId}")
    void deleteUserConversation(@PathParam("intent") String intent, @PathParam("userId") String userId);
}
