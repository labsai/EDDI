/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.CapabilityRegistryService.CapabilityMatch;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Set;

/**
 * REST interface for querying the A2A capability registry. Enables external
 * systems (MCP clients, other EDDI instances, dashboards) to discover agents by
 * skill.
 * <p>
 * Guarded like every other configuration-read surface: the responses enumerate
 * the deployment's agent ids and the skills they declare, which is an inventory
 * of the config, not conversational data a chat user needs. The in-process
 * {@code capabilityMatch} behaviour condition talks to
 * {@link CapabilityRegistryService} directly and is unaffected.
 *
 * @since 6.0.0
 */
@Path("/capabilities")
@Tag(name = "Integrations / Capability Registry", description = "A2A agent capability discovery")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestCapabilityRegistry {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "searchCapabilities", description = "Find agents matching a skill")
    @APIResponse(responseCode = "200", description = "Matching agent capabilities")
    List<CapabilityMatch> searchBySkill(
                                        @QueryParam("skill") String skill,
                                        @QueryParam("strategy")
                                        @DefaultValue("highest_confidence") String strategy);

    @GET
    @Path("/skills")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "listAllSkills", description = "List all registered skills")
    @APIResponse(responseCode = "200", description = "List of registered skills")
    Set<String> listSkills();
}
