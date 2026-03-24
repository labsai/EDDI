package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.setup.CreateApiAgentRequest;
import ai.labs.eddi.engine.setup.SetupAgentRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST API for one-command agent setup.
 * Delegates to {@link ai.labs.eddi.engine.setup.AgentSetupService}.
 *
 * @author ginccc
 */
@Path("/administration/agents")
@Tag(name = "09. Agent Setup", description = "One-command agent creation and deployment")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface IRestAgentSetup {

    @POST
    @Path("/setup")
    @Operation(summary = "Setup a standard agent",
            description = "Creates a fully configured agent with parser, behavior rules, LLM configuration, " +
                    "optional output set, workflow, and agent — then optionally deploys it.")
    Response setupAgent(SetupAgentRequest request);

    @POST
    @Path("/setup-api")
    @Operation(summary = "Create an API agent from an OpenAPI spec",
            description = "Parses an OpenAPI specification, generates HttpCalls configurations grouped by tag, " +
                    "creates all necessary resources (parser, behavior, LLM, workflow), and optionally deploys the agent.")
    Response createApiAgent(CreateApiAgentRequest request);
}
