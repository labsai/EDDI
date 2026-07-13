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
@Tag(name = "Conversations / Groups", description = "Multi-agent group discussion orchestration")
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

    @POST
    @Path("/{groupConversationId}/followup")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Follow up with a group member",
               description = "Send a follow-up question to a specific member agent in a completed "
                       + "group conversation. The agent retains full context from the discussion. "
                       + "Both the question and response are recorded on the group transcript. "
                       + "The targetAgentId field accepts either an agent ID or a display name. "
                       + "Returns the full updated GroupConversation including the new transcript entries.")
    @APIResponse(responseCode = "200", description = "Updated group conversation with follow-up on transcript.")
    @APIResponse(responseCode = "404", description = "Group conversation not found.")
    @APIResponse(responseCode = "409", description = "Conversation not in COMPLETED state.")
    Response followUpWithMember(@PathParam("groupId") String groupId,
                                @PathParam("groupConversationId") String gcId,
                                FollowUpRequest request);

    @POST
    @Path("/{groupConversationId}/continue")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Continue a group discussion",
               description = "Re-run all discussion phases with a new question. All agents retain "
                       + "memory of prior rounds. The round counter increments.")
    @APIResponse(responseCode = "200", description = "Updated group conversation with new round.")
    @APIResponse(responseCode = "404", description = "Group conversation not found.")
    @APIResponse(responseCode = "409", description = "Conversation not in COMPLETED state.")
    Response continueDiscussion(@PathParam("groupId") String groupId,
                                @PathParam("groupConversationId") String gcId,
                                DiscussRequest request);

    @POST
    @Path("/{groupConversationId}/continue/stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Operation(summary = "Continue a group discussion with SSE streaming",
               description = "Re-run all discussion phases with SSE event streaming for progress. "
                       + "Emits round_start (new round marker) followed by the same events as the "
                       + "initial stream (phase_start, speaker_start, speaker_complete, phase_complete, "
                       + "synthesis_start, group_complete, group_error).")
    @APIResponse(responseCode = "200", description = "SSE event stream of continuation progress.")
    @APIResponse(responseCode = "404", description = "Group conversation not found.")
    void continueDiscussionStreaming(@PathParam("groupId") String groupId,
                                     @PathParam("groupConversationId") String gcId,
                                     DiscussRequest request,
                                     @Context SseEventSink eventSink, @Context Sse sse);

    @POST
    @Path("/{groupConversationId}/close")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Close a group conversation",
               description = "Permanently close a group conversation. Ends all member conversations "
                       + "and cleans up ephemeral agents. No further follow-ups or continuations "
                       + "are accepted after closing. "
                       + "Lifecycle: discuss → COMPLETED → [followup|continue]* → close → CLOSED (terminal).")
    @APIResponse(responseCode = "200", description = "Closed group conversation.")
    @APIResponse(responseCode = "404", description = "Group conversation not found.")
    @APIResponse(responseCode = "409", description = "Conversation not in COMPLETED or FAILED state.")
    Response closeGroupConversation(@PathParam("groupId") String groupId,
                                    @PathParam("groupConversationId") String gcId);

    // --- Request Bodies ---

    /**
     * Request body for starting a group discussion.
     */
    record DiscussRequest(String question, String userId) {
    }

    /**
     * Request body for following up with a specific group member.
     * {@code targetAgentId} accepts either a raw agent ID or a display name.
     */
    record FollowUpRequest(String question, String targetAgentId, String userId) {
    }
}
