/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.inject.Vetoed;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;

/**
 * LLM tool for tearing down dynamically created agents. Constructed
 * per-invocation by {@code AgentOrchestrator} with the factory, agent store,
 * and the lists of created/retained agent IDs from the group conversation.
 *
 * <p>
 * Only agents that were created during the current discussion (tracked in
 * {@code createdAgentIds}) can be torn down — preventing accidental destruction
 * of pre-existing agents.
 *
 * @since 6.0.0
 */
@Vetoed // Instantiated per-invocation by AgentOrchestrator — must NOT be a CDI bean
public class TeardownAgentTool {

    private static final Logger LOGGER = Logger.getLogger(TeardownAgentTool.class);
    private static final Environment DEFAULT_ENV = Environment.production;

    private final IAgentFactory agentFactory;
    private final IAgentStore agentStore;
    private final List<String> createdAgentIds;
    private final Set<String> retainedAgentIds;

    public TeardownAgentTool(IAgentFactory agentFactory,
            IAgentStore agentStore,
            List<String> createdAgentIds,
            Set<String> retainedAgentIds) {
        this.agentFactory = agentFactory;
        this.agentStore = agentStore;
        this.createdAgentIds = createdAgentIds != null ? createdAgentIds : new java.util.concurrent.CopyOnWriteArrayList<>();
        this.retainedAgentIds = retainedAgentIds != null ? retainedAgentIds : new java.util.concurrent.CopyOnWriteArraySet<>();
    }

    @Tool("Tear down (undeploy) a dynamically created agent. Only agents created during this discussion "
            + "can be torn down. Optionally delete the agent configuration permanently.")
    public String teardownAgent(
                                @P("The ID of the agent to tear down") String agentId,
                                @P("If true, permanently delete the agent config after undeploying. Default: false") Boolean delete) {

        try {
            // --- Validate parameters ---
            if (agentId == null || agentId.isBlank()) {
                return "⚠️ Agent ID is required.";
            }

            // --- Security: can only teardown agents we created ---
            if (!createdAgentIds.contains(agentId)) {
                return "⚠️ Cannot tear down agent '%s' — it was not created during this discussion."
                        .formatted(agentId);
            }

            // --- Check if retained ---
            if (retainedAgentIds.contains(agentId)) {
                return "⚠️ Agent '%s' has been marked as retained and cannot be torn down. "
                        .formatted(agentId)
                        + "Remove the retain flag first if you want to tear it down.";
            }

            // --- Undeploy ---
            try {
                agentFactory.undeployAgent(DEFAULT_ENV, agentId, null);
                LOGGER.infof("[TEARDOWN] Undeployed agent '%s'", agentId);
            } catch (Exception e) {
                LOGGER.warnf("[TEARDOWN] Undeploy failed for agent '%s': %s", agentId, e.getMessage());
                return "❌ Failed to undeploy agent '%s': %s".formatted(agentId, e.getMessage());
            }

            // --- Optional: delete agent configuration ---
            createdAgentIds.remove(agentId);
            if (Boolean.TRUE.equals(delete)) {
                try {
                    agentStore.deleteAllPermanently(agentId);
                    LOGGER.infof("[TEARDOWN] Permanently deleted agent '%s'", agentId);
                    return "✅ Agent '%s' has been undeployed and permanently deleted.".formatted(agentId);
                } catch (Exception e) {
                    LOGGER.warnf("[TEARDOWN] Delete failed for agent '%s': %s", agentId, e.getMessage());
                    return "⚠️ Agent '%s' was undeployed but deletion failed: %s"
                            .formatted(agentId, e.getMessage());
                }
            }

            return "✅ Agent '%s' has been undeployed successfully.".formatted(agentId);

        } catch (Exception e) {
            LOGGER.errorf("[TEARDOWN] Unexpected error tearing down agent '%s': %s",
                    agentId, e.getMessage());
            return "❌ Unexpected error: " + e.getMessage();
        }
    }

    @Tool("Mark a dynamically created agent for retention — it will NOT be automatically deleted "
            + "when the discussion ends. Use this when a created agent should be kept for future use.")
    public String retainAgent(
                              @P("The ID of the agent to retain") String agentId) {

        try {
            // --- Validate parameters ---
            if (agentId == null || agentId.isBlank()) {
                return "⚠️ Agent ID is required.";
            }

            // --- Security: can only retain agents we created ---
            if (!createdAgentIds.contains(agentId)) {
                return "⚠️ Cannot retain agent '%s' — it was not created during this discussion."
                        .formatted(agentId);
            }

            // --- Check if already retained ---
            if (retainedAgentIds.contains(agentId)) {
                return "ℹ️ Agent '%s' is already marked as retained.".formatted(agentId);
            }

            retainedAgentIds.add(agentId);
            LOGGER.infof("[TEARDOWN] Retained agent '%s'", agentId);

            return "✅ Agent '%s' has been marked as retained. It will not be auto-deleted after the discussion."
                    .formatted(agentId);

        } catch (Exception e) {
            LOGGER.errorf("[TEARDOWN] Error retaining agent '%s': %s", agentId, e.getMessage());
            return "❌ Error retaining agent: " + e.getMessage();
        }
    }

    @Tool("Remove the retention flag from a previously retained agent, allowing it to be cleaned up when the discussion ends.")
    public String unretainAgent(@P("The agent ID to un-retain") String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return "⚠️ Agent ID is required.";
        }
        if (!retainedAgentIds.contains(agentId)) {
            return "⚠️ Agent '%s' is not currently retained.".formatted(agentId);
        }
        retainedAgentIds.remove(agentId);
        return "✅ Retention flag removed from agent '%s'. It will be cleaned up when the discussion ends.".formatted(agentId);
    }
}
