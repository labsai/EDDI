package ai.labs.eddi.engine.runtime.client.agents;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IExecutablePipeline;
import ai.labs.eddi.engine.runtime.IPipelineFactory;
import ai.labs.eddi.engine.runtime.internal.agent;
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
public class agentStoreClientLibrary implements IAgentStoreClientLibrary {
    private final IAgentStoreService agentStoreService;
    private final IPipelineFactory pipelineFactory;
    private static final Logger LOGGER = Logger.getLogger(AgentFactory.class);

    @Inject
    public agentStoreClientLibrary(IAgentStoreService agentStoreService,
                                 IPipelineFactory pipelineFactory) {
        this.agentStoreService = agentStoreService;
        this.pipelineFactory = pipelineFactory;
    }

    @Override
    public IAgent getAgent(final String agentId, final Integer version) throws ServiceException, IllegalAccessException {
        final IAgent Agent = new Agent(agentId, version);
        final AgentConfiguration AgentConfiguration = agentStoreService.getAgentConfiguration(agentId, version);
        for (final URI pipelineUri : AgentConfiguration.getPipelines()) {
            IResourceId resourceId = RestUtilities.extractResourceId(pipelineUri);
            if (resourceId != null) {
                IExecutablePipeline thePipeline = pipelineFactory.getExecutablePipeline(resourceId.getId(), resourceId.getVersion());
                agent.addPipeline(thePipeline);
            } else {
                LOGGER.warn(format("packageId should not have been null! (agentId=%s,agentVersion=%d)", agentId, version));
            }
        }

        return agent;
    }
}
