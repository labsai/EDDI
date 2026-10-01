/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.memory.IConversationMemoryStore.ConversationUsage;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * How much an agent is used — counts only. What its maintainers may always
 * know, whether or not they may read any conversation: a conversation belongs
 * to the person who had it, a count does not.
 */
@Path("/agents/{agentId}/usage")
@Tag(name = "Conversations", description = "Start, talk to, stream, undo/redo, and manage conversations")
@RolesAllowed({"eddi-admin", "eddi-editor", "eddi-viewer"})
public interface IRestAgentUsage {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "How much an agent is used", description = "Conversation and user counts across all versions. Requires VIEW.")
    @APIResponse(responseCode = "200", description = "The counts.")
    @APIResponse(responseCode = "403", description = "The caller may not view this agent.")
    ConversationUsage readUsage(@PathParam("agentId") String agentId);
}
