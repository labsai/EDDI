package ai.labs.channels.differ;

import ai.labs.channels.differ.model.CreateConversation;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;

@Path("differ")
public interface IDifferEndpoint {
    void init();

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/createConversation")
    void triggerConversationCreated(CreateConversation createConversation);
}


