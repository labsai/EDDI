package ai.labs.eddi.engine.rest;

import ai.labs.eddi.engine.api.IRestAgentSetup;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.engine.setup.AgentSetupService.AgentSetupException;
import ai.labs.eddi.engine.setup.CreateApiAgentRequest;
import ai.labs.eddi.engine.setup.SetupAgentRequest;
import ai.labs.eddi.engine.setup.SetupResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * REST implementation for one-command agent setup. Thin adapter that delegates
 * to {@link AgentSetupService}.
 *
 * @author ginccc
 */
@ApplicationScoped
public class RestAgentSetup implements IRestAgentSetup {

    private static final Logger LOGGER = Logger.getLogger(RestAgentSetup.class);

    private final AgentSetupService agentSetupService;

    @Inject
    public RestAgentSetup(AgentSetupService agentSetupService) {
        this.agentSetupService = agentSetupService;
    }

    @Override
    public Response setupAgent(SetupAgentRequest request) {
        try {
            SetupResult result = agentSetupService.setupAgent(request);
            return Response.status(Response.Status.CREATED).entity(result).build();
        } catch (AgentSetupException e) {
            LOGGER.warnf("Agent setup validation failed: %s", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(java.util.Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            LOGGER.error("Agent setup failed", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(java.util.Map.of("error", "Agent setup failed: " + e.getMessage()))
                    .build();
        }
    }

    @Override
    public Response createApiAgent(CreateApiAgentRequest request) {
        try {
            SetupResult result = agentSetupService.createApiAgent(request);
            return Response.status(Response.Status.CREATED).entity(result).build();
        } catch (AgentSetupException e) {
            LOGGER.warnf("API agent setup validation failed: %s", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(java.util.Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            LOGGER.error("API agent setup failed", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(java.util.Map.of("error", "API agent setup failed: " + e.getMessage())).build();
        }
    }
}
