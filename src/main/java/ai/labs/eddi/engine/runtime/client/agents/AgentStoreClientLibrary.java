package ai.labs.eddi.engine.runtime.client.agents;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import ai.labs.eddi.engine.runtime.IWorkflowFactory;
import ai.labs.eddi.engine.runtime.internal.Agent;
import ai.labs.eddi.engine.runtime.internal.AgentFactory;
import ai.labs.eddi.engine.runtime.service.IAgentStoreService;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;

import static java.lang.String.format;

/**
 * @author ginccc
 */
@ApplicationScoped
public class AgentStoreClientLibrary implements IAgentStoreClientLibrary {
    private final IAgentStoreService agentStoreService;
    private final IWorkflowFactory workflowFactory;
    private static final Logger LOGGER = Logger.getLogger(AgentFactory.class);

    @Inject
    public AgentStoreClientLibrary(IAgentStoreService agentStoreService, IWorkflowFactory workflowFactory) {
        this.agentStoreService = agentStoreService;
        this.workflowFactory = workflowFactory;
    }

    @Override
    public IAgent getAgent(final String agentId, final Integer version) throws ServiceException, IllegalAccessException {
        final IAgent agent = new Agent(agentId, version);
        final AgentConfiguration agentConfig = agentStoreService.getAgentConfiguration(agentId, version);
        for (final URI workflowUri : agentConfig.getWorkflows()) {
            IResourceId resourceId = RestUtilities.extractResourceId(workflowUri);
            if (resourceId != null) {
                IExecutableWorkflow theWorkflow = workflowFactory.getExecutableWorkflow(resourceId.getId(), resourceId.getVersion());
                agent.addWorkflow(theWorkflow);
            } else {
                LOGGER.warn(format("workflowId should not have been null! (agentId=%s,agentVersion=%d)", agentId, version));
            }
        }

        return agent;
    }
}
