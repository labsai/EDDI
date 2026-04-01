package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared logic for traversing agent workflows and discovering extension
 * configurations by step type. Eliminates duplicated traversal code between
 * httpcall and mcpcalls tool discovery.
 */
class WorkflowTraversal {
    private static final Logger LOGGER = Logger.getLogger(WorkflowTraversal.class);

    private WorkflowTraversal() {
        // static utility class
    }

    /**
     * Result of traversing a workflow step — the loaded configuration and the
     * step's raw config map.
     *
     * @param config
     *            the deserialized extension configuration
     * @param stepConfig
     *            the raw step config map (for access to additional properties)
     * @param <T>
     *            the configuration type
     */
    record StepConfig<T>(T config, java.util.Map<String, Object> stepConfig) {
    }

    /**
     * Traverse all workflows for the agent in memory, filter by step type, and load
     * the extension configuration for each matching step.
     *
     * @param memory
     *            conversation memory (provides agentId/version)
     * @param stepTypeUri
     *            the step type URI to match (e.g., "eddi://ai.labs.httpcalls")
     * @param configClass
     *            the class to deserialize the configuration into
     * @param restAgentStore
     *            agent store for loading agent configurations
     * @param restWorkflowStore
     *            workflow store for loading workflow configurations
     * @param resourceClientLibrary
     *            resource client for loading extension configurations
     * @param <T>
     *            the configuration type
     * @return list of discovered configurations (never null)
     */
    static <T> List<StepConfig<T>> discoverConfigs(IConversationMemory memory, String stepTypeUri, Class<T> configClass,
                                                   IRestAgentStore restAgentStore, IRestWorkflowStore restWorkflowStore,
                                                   IResourceClientLibrary resourceClientLibrary) {

        List<StepConfig<T>> results = new ArrayList<>();

        String agentId = memory.getAgentId();
        Integer agentVersion = memory.getAgentVersion();
        if (agentId == null || agentVersion == null) {
            LOGGER.debugf("No agent context in memory — skipping %s discovery", stepTypeUri);
            return results;
        }

        AgentConfiguration agentConfig;
        try {
            agentConfig = restAgentStore.readAgent(agentId, agentVersion);
        } catch (Exception e) {
            LOGGER.warnf("Failed to load agent config for %s v%d: %s", agentId, agentVersion, e.getMessage());
            return results;
        }

        if (agentConfig == null || agentConfig.getWorkflows() == null || agentConfig.getWorkflows().isEmpty()) {
            LOGGER.debugf("No workflows found for agent %s — skipping %s discovery", agentId, stepTypeUri);
            return results;
        }

        for (URI workflowUri : agentConfig.getWorkflows()) {
            String workflowPath = workflowUri.getPath();
            if (workflowPath == null) {
                LOGGER.warnf("Workflow URI has no path: %s", workflowUri);
                continue;
            }
            String workflowId = workflowPath.substring(workflowPath.lastIndexOf('/') + 1);
            String workflowQuery = workflowUri.getQuery();
            if (workflowQuery == null || !workflowQuery.contains("version=")) {
                LOGGER.warnf("Workflow URI has no version query: %s", workflowUri);
                continue;
            }
            int workflowVersion = Integer.parseInt(workflowQuery.replaceAll(".*version=(\\d+).*", "$1"));

            try {
                WorkflowConfiguration workflowConfig = restWorkflowStore.readWorkflow(workflowId, workflowVersion);
                for (var step : workflowConfig.getWorkflowSteps()) {
                    if (step.getType() != null && stepTypeUri.equals(step.getType().toString())) {
                        String uri = (String) step.getConfig().get("uri");
                        if (uri == null)
                            continue;

                        try {
                            T config = resourceClientLibrary.getResource(URI.create(uri), configClass);
                            results.add(new StepConfig<>(config, step.getConfig()));
                        } catch (ServiceException e) {
                            LOGGER.warnf("Failed to load %s config: %s — %s", stepTypeUri, uri, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warnf("Failed to load workflow %s v%d: %s", workflowId, workflowVersion, e.getMessage());
            }
        }

        return results;
    }
}
