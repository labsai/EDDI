package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.CapabilityRegistryService.CapabilityMatch;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Set;

/**
 * REST interface for querying the A2A capability registry. Enables external
 * systems (MCP clients, other EDDI instances, dashboards) to discover agents by
 * skill.
 *
 * @since 6.0.0
 */
@Path("/capabilities")
@Tag(name = "06. Capability Registry", description = "A2A agent capability discovery")
public interface IRestCapabilityRegistry {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "searchCapabilities", description = "Find agents matching a skill")
    List<CapabilityMatch> searchBySkill(
                                        @QueryParam("skill") String skill,
                                        @QueryParam("strategy")
                                        @DefaultValue("highest_confidence") String strategy);

    @GET
    @Path("/skills")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "listAllSkills", description = "List all registered skills")
    Set<String> listSkills();
}
