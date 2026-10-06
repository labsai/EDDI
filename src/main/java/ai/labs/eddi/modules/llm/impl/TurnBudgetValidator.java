/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.shared.TurnDeadline;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Deploy-time warning for an agent whose {@code turnDeadlineMs} cannot be met
 * by its own retry and timeout settings. Warning only, and best effort: a
 * failure here is logged at debug and never reaches the deployment.
 * <p>
 * Runs once per agent deployment, when the agent is loaded
 * ({@code AgentStoreClientLibrary#getAgent}). Only agents that configure
 * {@code turnDeadlineMs} are examined — with no deadline there is nothing to
 * exceed.
 */
@ApplicationScoped
public class TurnBudgetValidator {
    private static final Logger LOGGER = Logger.getLogger(TurnBudgetValidator.class);

    private static final String LLM_TYPE = "eddi://ai.labs.llm";
    private static final String HTTPCALLS_TYPE = "eddi://ai.labs.httpcalls";

    private final IAgentStore agentStore;
    private final IWorkflowStore workflowStore;
    private final IResourceClientLibrary resourceClientLibrary;

    @Inject
    public TurnBudgetValidator(IAgentStore agentStore, IWorkflowStore workflowStore, IResourceClientLibrary resourceClientLibrary) {
        this.agentStore = agentStore;
        this.workflowStore = workflowStore;
        this.resourceClientLibrary = resourceClientLibrary;
    }

    /**
     * Logs a warning when the agent's static worst case exceeds its turn budget.
     */
    public void warnIfOverBudget(AgentConfiguration agentConfig, String agentId, Integer agentVersion) {
        try {
            Long deadlineMs = agentConfig.getTurnDeadlineMs();
            if (deadlineMs == null || deadlineMs <= 0) {
                return;
            }
            long reserve = agentConfig.getTurnDeadlineReserveMs() != null && agentConfig.getTurnDeadlineReserveMs() >= 0
                    ? agentConfig.getTurnDeadlineReserveMs()
                    : TurnDeadline.DEFAULT_RESERVE_MS;

            var probe = new ConversationMemory(agentId, agentVersion, null);
            long llmMs = 0;
            for (var step : WorkflowTraversal.discoverConfigs(probe, LLM_TYPE, LlmConfiguration.class, agentStore,
                    workflowStore, resourceClientLibrary)) {
                if (step.config() != null && step.config().tasks() != null) {
                    for (var task : step.config().tasks()) {
                        llmMs += TurnResilienceWarnings.worstCaseLlmMs(task);
                    }
                }
            }
            long httpMs = 0;
            for (var step : WorkflowTraversal.discoverConfigs(probe, HTTPCALLS_TYPE, ApiCallsConfiguration.class, agentStore, workflowStore,
                    resourceClientLibrary)) {
                httpMs += TurnResilienceWarnings.worstCaseApiCallMs(step.config());
            }

            String warning = TurnResilienceWarnings.budgetWarning(deadlineMs, reserve, llmMs, httpMs);
            if (warning != null) {
                LOGGER.warnf("Agent %s v%d: %s.", agentId, agentVersion, warning);
            }
        } catch (RuntimeException e) {
            LOGGER.debugf("Turn budget check skipped for agent %s v%d: %s", agentId, agentVersion, e.getMessage());
        }
    }
}
