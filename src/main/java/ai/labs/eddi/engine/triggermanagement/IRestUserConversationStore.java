package ai.labs.eddi.engine.triggermanagement;

import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

//@Api(value = "Configurations -> (5) Agent Management", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/userconversationstore/userconversations")
@Tag(name = "09. Talk to Agents", description = "Communicate with agents")
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
