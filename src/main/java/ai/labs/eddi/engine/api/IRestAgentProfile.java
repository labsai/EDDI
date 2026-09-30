/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.model.Deployment;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * What a chat window needs to know about an agent before the first message: its
 * name, and whether the conversation may be read by the agent's maintainers.
 *
 * <h3>Why its own endpoint</h3> A chat client used to read the agent's name
 * from the configuration store. Under workspaces somebody who may only
 * <em>talk</em> to an agent cannot read its configuration, so that read fails
 * and the window shows no name. This answers at the level talking needs —
 * {@code USE} — and discloses nothing about how the agent is built.
 */
@Path("/agents/{agentId}/profile")
@Tag(name = "Conversations", description = "Start, talk to, stream, undo/redo, and manage conversations")
@RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user"})
public interface IRestAgentProfile {

    /**
     * @param agentId
     *            the agent
     * @param name
     *            its display name, or null
     * @param description
     *            its description, or null
     * @param reviewNotice
     *            what to tell the person chatting when the deployed version lets
     *            its maintainers read conversations; null when it does not
     */
    record AgentProfile(String agentId, String name, String description, String reviewNotice) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "What a chat window shows about an agent",
               description = "Name, description, and the conversation-review notice for the deployed version. Requires USE.")
    @APIResponse(responseCode = "200", description = "The profile.")
    @APIResponse(responseCode = "403", description = "The caller may not use this agent.")
    AgentProfile readProfile(@PathParam("agentId") String agentId,
                             @QueryParam("environment")
                             @DefaultValue("production") Deployment.Environment environment);
}
