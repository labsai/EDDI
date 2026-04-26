/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * JAX-RS interface for group conversation operations (discuss, read, delete,
 * list). No {@code {env}} parameter — group conversations default to the
 * production environment.
 */
@Path("/groups/{groupId}/conversations")
@Tag(name = "Group Conversations")
@RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user"})
public interface IRestGroupConversation {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Start a group discussion", description = "Starts a new multi-agent group discussion with the given question.")
    @APIResponse(responseCode = "200", description = "Discussion result with transcript.")
    @APIResponse(responseCode = "404", description = "Group not found.")
    Response discuss(@PathParam("groupId") String groupId, DiscussRequest request);

    @POST
    @Path("/stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Operation(summary = "Start a group discussion with SSE streaming",
               description = "Starts a group discussion asynchronously and streams progress events "
                       + "(group_start, phase_start, speaker_start, speaker_complete, "
                       + "phase_complete, synthesis_start, group_complete, group_error) "
                       + "via Server-Sent Events.")
    @APIResponse(responseCode = "200", description = "SSE event stream of discussion progress.")
    @APIResponse(responseCode = "404", description = "Group not found.")
    void discussStreaming(@PathParam("groupId") String groupId, DiscussRequest request, @Context SseEventSink eventSink, @Context Sse sse);

    @GET
    @Path("/{groupConversationId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read a group conversation", description = "Returns the full transcript of a group conversation.")
    @APIResponse(responseCode = "200", description = "Group conversation transcript.")
    @APIResponse(responseCode = "404", description = "Group conversation not found.")
    GroupConversation readGroupConversation(@PathParam("groupId") String groupId, @PathParam("groupConversationId") String groupConversationId);

    @DELETE
    @Path("/{groupConversationId}")
    @Operation(summary = "Delete a group conversation", description = "Deletes a group conversation and its member conversations.")
    @APIResponse(responseCode = "200", description = "Group conversation deleted.")
    @APIResponse(responseCode = "404", description = "Group conversation not found.")
    Response deleteGroupConversation(@PathParam("groupId") String groupId, @PathParam("groupConversationId") String groupConversationId);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List group conversations", description = "Lists group conversation transcripts for a group with pagination.")
    @APIResponse(responseCode = "200", description = "Paginated list of group conversations.")
    List<GroupConversation> listGroupConversations(@PathParam("groupId") String groupId, @QueryParam("index")
    @DefaultValue("0") Integer index,
                                                   @QueryParam("limit")
                                                   @DefaultValue("20") Integer limit);

    /**
     * Request body for starting a group discussion.
     */
    record DiscussRequest(String question, String userId) {
    }
}
