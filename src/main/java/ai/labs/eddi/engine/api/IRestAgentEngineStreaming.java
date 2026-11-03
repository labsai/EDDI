/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.model.InputData;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * SSE streaming endpoint for real-time conversation responses.
 * <p>
 * Provides two layers of events:
 * <ul>
 * <li><strong>task_start / task_complete</strong> — lifecycle workflow
 * progress</li>
 * <li><strong>token</strong> — LLM response tokens in real-time</li>
 * <li><strong>tool_call / tool_result</strong> — one tool call started /
 * finished (never arguments or results)</li>
 * <li><strong>hitl_resume</strong> — a resume stream only: the decision was
 * committed</li>
 * <li><strong>approval_required</strong> — the turn ended waiting for a human,
 * sent just before <em>done</em></li>
 * <li><strong>done</strong> — full conversation snapshot</li>
 * <li><strong>error</strong> — error during processing</li>
 * </ul>
 */
@Path("/agents")
@Tag(name = "Conversations", description = "Start, talk to, stream, undo/redo, and manage conversations")
@RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user"})
public interface IRestAgentEngineStreaming {

    @POST
    @Path("/{conversationId}/stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Operation(summary = "Send message with SSE streaming", description = "Send user input and receive streaming SSE response "
            + "with workflow events and LLM tokens.")
    void sayStreaming(@PathParam("conversationId") String conversationId, @QueryParam("returnDetailed")
    @DefaultValue("false") Boolean returnDetailed,
                      @QueryParam("returnCurrentStepOnly")
                      @DefaultValue("true") Boolean returnCurrentStepOnly,
                      @QueryParam("returningFields") List<String> returningFields, InputData inputData, @Context SseEventSink eventSink,
                      @Context Sse sse);

    @POST
    @Path("/{conversationId}/resume/stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user", "eddi-approver"})
    @Operation(summary = "Resume a paused conversation with SSE streaming",
               description = "Submits a human decision (APPROVED/REJECTED) exactly as POST /agents/{conversationId}/resume does - "
                       + "same body, same checks, same errors - and streams the resumed turn. Every refusal (400, 403, 404, 409, 503) is "
                       + "a plain HTTP error before any event is written. Once the decision is accepted the stream carries hitl_resume, "
                       + "then the turn's task_start / tool_call / tool_result / token / task_complete events, "
                       + "approval_required when the turn pauses again, and finally done (or error).")
    void resumeStreaming(@PathParam("conversationId") String conversationId, HitlDecision decision,
                         @QueryParam("returnDetailed")
                         @DefaultValue("false") Boolean returnDetailed,
                         @QueryParam("returnCurrentStepOnly")
                         @DefaultValue("true") Boolean returnCurrentStepOnly,
                         @QueryParam("returningFields") List<String> returningFields, @Context SseEventSink eventSink,
                         @Context Sse sse);
}
