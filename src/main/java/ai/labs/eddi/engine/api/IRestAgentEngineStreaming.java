package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.model.InputData;
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
 * <li><strong>done</strong> — full conversation snapshot</li>
 * <li><strong>error</strong> — error during processing</li>
 * </ul>
 */
@Path("/agents")
@Tag(name = "09. Talk to Agents", description = "Communicate with agents")
public interface IRestAgentEngineStreaming {

    @POST
    @Path("/{conversationId}/stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Operation(description = "Send user input and receive streaming SSE response with workflow events and LLM tokens.")
    void sayStreaming(@PathParam("conversationId") String conversationId, @QueryParam("returnDetailed") @DefaultValue("false") Boolean returnDetailed,
            @QueryParam("returnCurrentStepOnly") @DefaultValue("true") Boolean returnCurrentStepOnly,
            @QueryParam("returningFields") List<String> returningFields, InputData inputData, @Context SseEventSink eventSink, @Context Sse sse);
}
