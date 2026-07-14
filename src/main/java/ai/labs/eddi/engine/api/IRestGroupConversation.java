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
// Class-level path is the /groups root (not /groups/{groupId}/conversations) so
// the
// cross-group inbox GET /groups/pending-approvals (#21) can live alongside the
// per-group routes. Every per-group method carries the /{groupId}/conversations
// prefix, so all existing external URLs are unchanged.
@Path("/groups")
@Tag(name = "Conversations / Groups", description = "Multi-agent group discussion orchestration")
@RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user"})
public interface IRestGroupConversation {

    @POST
    @Path("/{groupId}/conversations")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Start a group discussion", description = "Starts a new multi-agent group discussion with the given question.")
    @APIResponse(responseCode = "200", description = "Discussion result with transcript.")
    @APIResponse(responseCode = "404", description = "Group not found.")
    Response discuss(@PathParam("groupId") String groupId, DiscussRequest request);

    @POST
    @Path("/{groupId}/conversations/stream")
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
    @Path("/{groupId}/conversations/{groupConversationId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read a group conversation", description = "Returns the full transcript of a group conversation.")
    @APIResponse(responseCode = "200", description = "Group conversation transcript.")
    @APIResponse(responseCode = "404", description = "Group conversation not found.")
    GroupConversation readGroupConversation(@PathParam("groupId") String groupId, @PathParam("groupConversationId") String groupConversationId);

    @DELETE
    @Path("/{groupId}/conversations/{groupConversationId}")
    @Operation(summary = "Delete a group conversation", description = "Deletes a group conversation and its member conversations.")
    @APIResponse(responseCode = "200", description = "Group conversation deleted.")
    @APIResponse(responseCode = "404", description = "Group conversation not found.")
    @APIResponse(responseCode = "409", description = "Another operation (follow-up / continue / close) is in progress — retry.")
    Response deleteGroupConversation(@PathParam("groupId") String groupId, @PathParam("groupConversationId") String groupConversationId);

    @GET
    @Path("/{groupId}/conversations")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List group conversations", description = "Lists group conversation transcripts for a group with pagination.")
    @APIResponse(responseCode = "200", description = "Paginated list of group conversations.")
    List<GroupConversation> listGroupConversations(@PathParam("groupId") String groupId, @QueryParam("index")
    @DefaultValue("0") Integer index,
                                                   @QueryParam("limit")
                                                   @DefaultValue("20") Integer limit);

    @POST
    @Path("/{groupId}/conversations/{groupConversationId}/followup")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Follow up with a group member",
               description = "Send a follow-up question to a specific member agent in a completed "
                       + "group conversation. The agent retains full context from the discussion. "
                       + "Both the question and response are recorded on the group transcript. "
                       + "The targetAgentId field accepts either an agent ID or a display name. "
                       + "Returns the full updated GroupConversation including the new transcript entries.")
    @APIResponse(responseCode = "200", description = "Updated group conversation with follow-up on transcript.")
    @APIResponse(responseCode = "400", description = "Missing 'question' or 'targetAgentId'.")
    @APIResponse(responseCode = "404", description = "Group conversation not found.")
    @APIResponse(responseCode = "409", description = "Conversation not in COMPLETED state, or concurrently cancelled/deleted.")
    Response followUpWithMember(@PathParam("groupId") String groupId,
                                @PathParam("groupConversationId") String gcId,
                                FollowUpRequest request);

    @POST
    @Path("/{groupId}/conversations/{groupConversationId}/continue")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Continue a group discussion",
               description = "Re-run all discussion phases with a new question. All agents retain "
                       + "memory of prior rounds. The round counter increments. Any 'attachments' on "
                       + "the request are shared with every member agent for this round, on top of "
                       + "those already bound to the conversation.")
    @APIResponse(responseCode = "200", description = "Updated group conversation with new round.")
    @APIResponse(responseCode = "400", description = "Missing 'question'.")
    @APIResponse(responseCode = "404", description = "Group conversation not found.")
    @APIResponse(responseCode = "409", description = "Conversation not in COMPLETED state.")
    Response continueDiscussion(@PathParam("groupId") String groupId,
                                @PathParam("groupConversationId") String gcId,
                                DiscussRequest request);

    @POST
    @Path("/{groupId}/conversations/{groupConversationId}/continue/stream")
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
    @Path("/{groupId}/conversations/{groupConversationId}/close")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Close a group conversation",
               description = "Permanently close a group conversation. Ends all member conversations "
                       + "and cleans up ephemeral agents. No further follow-ups or continuations "
                       + "are accepted after closing. "
                       + "Lifecycle: discuss → COMPLETED → [followup|continue]* → close → CLOSED (terminal).")
    @APIResponse(responseCode = "200", description = "Closed group conversation.")
    @APIResponse(responseCode = "404", description = "Group conversation not found.")
    @APIResponse(responseCode = "409", description = "Conversation not in COMPLETED, FAILED or CANCELLED state.")
    Response closeGroupConversation(@PathParam("groupId") String groupId,
                                    @PathParam("groupConversationId") String gcId);

    @POST
    @Path("/{groupId}/conversations/{groupConversationId}/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user", "eddi-approver"})
    @Operation(summary = "Cancel a group discussion", description = "Cancels an in-progress group discussion.")
    @APIResponse(responseCode = "200", description = "Discussion cancelled.")
    @APIResponse(responseCode = "404", description = "Group conversation not found.")
    Response cancelDiscussion(@PathParam("groupId") String groupId,
                              @PathParam("groupConversationId") String gcId);

    @POST
    @Path("/{groupId}/conversations/{groupConversationId}/approve")
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
    @Path("/{groupId}/conversations/{groupConversationId}/approve/stream")
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
    @Path("/{groupId}/conversations/{groupConversationId}/approval-status")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user", "eddi-approver"})
    @Operation(summary = "Get group approval status",
               description = "Returns the current approval status of a group conversation. Default is a summary "
                       + "(pause coordinates, no transcript); use detail=full for the complete conversation "
                       + "(approver-only callers may use detail=full only while the conversation is awaiting approval).")
    @APIResponse(responseCode = "200", description = "Approval status.")
    @APIResponse(responseCode = "403", description = "Approver-only caller requested detail=full on a non-paused conversation.")
    @APIResponse(responseCode = "404", description = "Group conversation not found.")
    Response getGroupApprovalStatus(@PathParam("groupId") String groupId,
                                    @PathParam("groupConversationId") String gcId,
                                    @QueryParam("detail")
                                    @DefaultValue("summary") String detail);

    /**
     * Request body for starting a group discussion.
     * <p>
     * Optional {@code attachments} are shared with every member agent: inline files
     * ({@code data}) are stored server-side bound to the group conversation and
     * each member is granted access; {@code url} references are forwarded as-is.
     * Old two-argument clients remain compatible.
     */
    record DiscussRequest(String question, String userId, List<AttachmentRef> attachments) {
        public DiscussRequest(String question, String userId) {
            this(question, userId, null);
        }
    }

    /**
     * Request body for following up with a specific group member.
     * {@code targetAgentId} accepts either a raw agent ID or a display name.
     */
    record FollowUpRequest(String question, String targetAgentId, String userId) {
    }

    /**
     * A single attachment reference on a group discussion request. Provide either
     * inline base64 {@code data} (+ {@code mimeType}) or a hosted {@code url}.
     */
    record AttachmentRef(String mimeType, String data, String url, String fileName) {
    }

    /**
     * List the group conversations of THIS group that are currently awaiting human
     * approval, as bounded summaries (no transcripts). Visibility: admins and
     * approvers see all of the group's pending items; other callers only their own
     * conversations.
     */
    @GET
    @Path("/{groupId}/conversations/pending-approvals")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user", "eddi-approver"})
    @Operation(summary = "List pending HITL approvals",
               description = "Lists this group's conversations currently awaiting human approval (summaries).")
    @APIResponse(responseCode = "200", description = "List of pending approval summaries.")
    List<ai.labs.eddi.engine.model.PendingApprovalSummary> listGroupPendingApprovals(
                                                                                     @PathParam("groupId") String groupId,
                                                                                     @QueryParam("limit")
                                                                                     @DefaultValue("100") Integer limit);

    /**
     * Cross-group HITL inbox (#21): all group conversations currently awaiting
     * human approval across every group, as bounded summaries (no transcripts).
     * Lets an approvals dashboard answer "what is waiting for me?" in one request
     * instead of enumerating every group (N+1). Visibility mirrors the per-group
     * and regular surfaces: admins and approvers see all pending items; other
     * callers see only their own conversations; anonymous sees nothing.
     */
    @GET
    @Path("/pending-approvals")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user", "eddi-approver"})
    @Operation(summary = "List all pending group HITL approvals",
               description = "Lists group conversations awaiting human approval across all groups (summaries).")
    @APIResponse(responseCode = "200", description = "List of pending approval summaries.")
    List<ai.labs.eddi.engine.model.PendingApprovalSummary> listAllGroupPendingApprovals(
                                                                                        @QueryParam("limit")
                                                                                        @DefaultValue("100") Integer limit);
}
