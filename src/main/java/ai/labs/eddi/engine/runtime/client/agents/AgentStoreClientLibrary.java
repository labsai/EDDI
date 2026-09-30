/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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

        // Persistent User Memory (Phase 11a).
        //
        // enableMemoryTools is the opt-in; userMemoryConfig is tuning on top of it,
        // and every one of its fields already has a working default. Requiring both
        // made the second a hidden second switch: an agent that set enableMemoryTools
        // and nothing else got no memory tool and no explanation, because the skip is
        // silent. Fall back to the defaults instead.
        //
        // The config and the switch are separate. userMemoryConfig governs the
        // longTerm property path EVERY agent uses — recall size and order, the
        // default visibility of a property that sets none — so it applies whenever
        // it is declared. It used to be dropped unless enableMemoryTools was on: an
        // agent with "defaultVisibility": "self" and no memory tools had every
        // longTerm property persisted as global, readable by all its sibling agents.
        // enableMemoryTools gates only what it names: the LLM memory tool.
        var memoryConfig = agentConfig.getUserMemoryConfig();
        if (memoryConfig != null) {
            ((Agent) agent).setUserMemoryConfig(memoryConfig);
        } else if (agentConfig.isEnableMemoryTools()) {
            ((Agent) agent).setUserMemoryConfig(new AgentConfiguration.UserMemoryConfig());
        }
        ((Agent) agent).setMemoryToolsEnabled(agentConfig.isEnableMemoryTools());

        // Memory Policy (Phase A: Strict Write Discipline)
        if (agentConfig.getMemoryPolicy() != null) {
            ((Agent) agent).setMemoryPolicy(agentConfig.getMemoryPolicy());
        }

        // Tool-level HITL: carry the agent-level tool-approval config so the gate is
        // honored on the CONVERSATION_START (init) turn, not just say/resume turns.
        if (agentConfig.getHitlConfig() != null) {
            ((Agent) agent).setToolApprovalsConfig(agentConfig.getHitlConfig().getToolApprovals());
        }

        // Read once here so the per-turn version resolution compares integers
        // instead of loading configurations.
        ((Agent) agent).setCompatibilityGeneration(agentConfig.getCompatibilityGeneration());

        return agent;
    }
}
