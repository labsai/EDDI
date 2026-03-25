package ai.labs.eddi.engine.api;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * JAX-RS interface for group conversation operations (discuss, read, delete,
 * list). No {@code {env}} parameter — group conversations default to the
 * production environment.
 *
 * @author ginccc
 */
@Path("/groups/{groupId}/conversations")
@Tag(name = "10. Group Conversations", description = "multi-agent group discussion orchestration")
public interface IRestGroupConversation {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Start a new group discussion with the given question.")
    Response discuss(@PathParam("groupId") String groupId, DiscussRequest request);

    @GET
    @Path("/{groupConversationId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read a group conversation transcript.")
    GroupConversation readGroupConversation(@PathParam("groupId") String groupId, @PathParam("groupConversationId") String groupConversationId);

    @DELETE
    @Path("/{groupConversationId}")
    @Operation(description = "Delete a group conversation and its member conversations.")
    Response deleteGroupConversation(@PathParam("groupId") String groupId, @PathParam("groupConversationId") String groupConversationId);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "List group conversation transcripts for a group.")
    List<GroupConversation> listGroupConversations(@PathParam("groupId") String groupId, @QueryParam("index") @DefaultValue("0") Integer index,
            @QueryParam("limit") @DefaultValue("20") Integer limit);

    /**
     * Request body for starting a group discussion.
     */
    record DiscussRequest(String question, String userId) {
    }
}
