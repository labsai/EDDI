/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.internal.GroupApprovalRequest;
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
    @Path("/{groupConversationId}/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user", "eddi-approver"})
    @Operation(summary = "Cancel a group discussion", description = "Cancels an in-progress group discussion.")
    @APIResponse(responseCode = "200", description = "Discussion cancelled.")
    @APIResponse(responseCode = "404", description = "Group conversation not found.")
    Response cancelDiscussion(@PathParam("groupId") String groupId,
                              @PathParam("groupConversationId") String gcId);

    @POST
    @Path("/{groupConversationId}/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user", "eddi-approver"})
    @Operation(summary = "Approve a paused group phase", description = "Resumes a paused group discussion after human approval.")
    @APIResponse(responseCode = "200", description = "Discussion resumed.")
    @APIResponse(responseCode = "400", description = "Invalid decision body or taskApprovals.")
    @APIResponse(responseCode = "404", description = "Group conversation not found.")
    @APIResponse(responseCode = "409", description = "Not awaiting approval / concurrent modification conflict.")
    Response approveGroupPhase(@PathParam("groupId") String groupId,
                               @PathParam("groupConversationId") String gcId,
                               GroupApprovalRequest request);

    @POST
    @Path("/{groupConversationId}/approve/stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user", "eddi-approver"})
    @Operation(summary = "Approve a paused group phase with SSE streaming",
               description = "Resumes a paused group discussion and streams progress events via Server-Sent Events.")
    @APIResponse(responseCode = "200", description = "SSE event stream of resumed discussion progress.")
    void approveGroupPhaseStreaming(@PathParam("groupId") String groupId,
                                    @PathParam("groupConversationId") String gcId,
                                    GroupApprovalRequest request,
                                    @Context SseEventSink eventSink,
                                    @Context Sse sse);

    @GET
    @Path("/{groupConversationId}/approval-status")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user", "eddi-approver"})
    @Operation(summary = "Get group approval status", description = "Returns the current approval status of a group conversation.")
    @APIResponse(responseCode = "200", description = "Approval status.")
    @APIResponse(responseCode = "404", description = "Group conversation not found.")
    Response getGroupApprovalStatus(@PathParam("groupId") String groupId,
                                    @PathParam("groupConversationId") String gcId,
                                    @QueryParam("detail")
                                    @DefaultValue("summary") String detail);

    /**
     * Request body for starting a group discussion.
     */
    record DiscussRequest(String question, String userId) {
    }

    /**
     * List the group conversations of THIS group that are currently awaiting human
     * approval. Visibility: admins and approvers see all of the group's pending
     * items; other callers only their own conversations.
     */
    @GET
    @Path("/pending-approvals")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user", "eddi-approver"})
    @Operation(summary = "List pending HITL approvals",
               description = "Lists this group's conversations currently awaiting human approval.")
    @APIResponse(responseCode = "200", description = "List of pending approval group conversations.")
    List<GroupConversation> listGroupPendingApprovals(@PathParam("groupId") String groupId);
}
