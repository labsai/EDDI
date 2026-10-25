/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.hitl.tools.TaskToolApprovalsResolver;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the agents an email integration targets that can use tools without any
 * tool approval (email plan §14 item 6). Mail is untrusted third-party content
 * — the textbook indirect prompt-injection input — and in trigger mode the
 * agent's tools are the whole blast radius. This is a save-time <b>warning</b>,
 * never a refusal: the engine is config-driven and the author decides.
 * <p>
 * "Can use tools" is read the way the LLM task decides it: agent mode
 * ({@code tools}, built-in tools, A2A agents), or HTTP/MCP call tools in the
 * same workflow while those switches are not off. EDDI cannot tell a read-only
 * tool from one that writes, so every tool counts. Approvals are the effective
 * {@code toolApprovals} of the task (task over agent level) with a non-empty
 * {@code requireApproval}. Best effort: a document that cannot be read is
 * skipped, so a store hiccup costs a warning, never a save.
 */
@ApplicationScoped
public class EmailAgentToolsInspector {

    private static final Logger LOGGER = Logger.getLogger(EmailAgentToolsInspector.class);

    private static final String LLM_AUTHORITY = "ai.labs.llm";
    private static final String API_CALLS_AUTHORITY = "ai.labs.apicalls";
    private static final String HTTP_CALLS_AUTHORITY = "ai.labs.httpcalls";
    private static final String MCP_CALLS_AUTHORITY = "ai.labs.mcpcalls";

    private final IAgentStore agentStore;
    private final IWorkflowStore workflowStore;
    private final IResourceClientLibrary resourceClientLibrary;

    @Inject
    public EmailAgentToolsInspector(IAgentStore agentStore, IWorkflowStore workflowStore, IResourceClientLibrary resourceClientLibrary) {
        this.agentStore = agentStore;
        this.workflowStore = workflowStore;
        this.resourceClientLibrary = resourceClientLibrary;
    }

    /** The names of the targets whose agent has tools and no tool approvals. */
    public List<String> targetsWithUngatedTools(List<ChannelTarget> targets) {
        List<String> names = new ArrayList<>();
        if (targets == null) {
            return names;
        }
        for (ChannelTarget target : targets) {
            if (target == null || target.getType() == ChannelTarget.TargetType.GROUP || target.getTargetId() == null) {
                continue;
            }
            try {
                if (hasUngatedTools(target.getTargetId())) {
                    names.add(target.getName());
                }
            } catch (Exception e) {
                LOGGER.debugf("Tool inspection skipped for agent %s: %s", target.getTargetId(), e.getMessage());
            }
        }
        return names;
    }

    boolean hasUngatedTools(String agentId) throws Exception {
        int version = agentStore.getCurrentResourceId(agentId).getVersion();
        AgentConfiguration agent = agentStore.read(agentId, version);
        if (agent == null || agent.getWorkflows() == null) {
            return false;
        }
        ToolApprovalsConfig agentLevel = agent.getHitlConfig() == null ? null : agent.getHitlConfig().getToolApprovals();
        for (URI workflowUri : agent.getWorkflows()) {
            var workflowId = RestUtilities.extractResourceId(workflowUri);
            if (workflowId == null || workflowId.getId() == null || workflowId.getVersion() == null) {
                continue;
            }
            WorkflowConfiguration workflow = workflowStore.read(workflowId.getId(), workflowId.getVersion());
            if (workflow == null || workflow.getWorkflowSteps() == null) {
                continue;
            }
            List<URI> llmUris = new ArrayList<>();
            boolean hasApiCalls = false;
            boolean hasMcpCalls = false;
            for (var step : workflow.getWorkflowSteps()) {
                URI uri = step.getConfig() != null && step.getConfig().get("uri") instanceof String value ? safeUri(value) : null;
                if (uri == null || uri.getHost() == null) {
                    continue;
                }
                switch (uri.getHost()) {
                    case LLM_AUTHORITY -> llmUris.add(uri);
                    case API_CALLS_AUTHORITY, HTTP_CALLS_AUTHORITY -> hasApiCalls = true;
                    case MCP_CALLS_AUTHORITY -> hasMcpCalls = true;
                    default -> {
                        // not relevant
                    }
                }
            }
            for (URI uri : llmUris) {
                LlmConfiguration llm = resourceClientLibrary.getResource(uri, LlmConfiguration.class);
                if (llm == null || llm.tasks() == null) {
                    continue;
                }
                for (LlmConfiguration.Task task : llm.tasks()) {
                    boolean toolsOn = task.isAgentMode() || (hasApiCalls && !Boolean.FALSE.equals(task.getEnableHttpCallTools()))
                            || (hasMcpCalls && !Boolean.FALSE.equals(task.getEnableMcpCallTools()));
                    if (!toolsOn) {
                        continue;
                    }
                    ToolApprovalsConfig effective = TaskToolApprovalsResolver.resolve(agentLevel, task.getToolApprovals());
                    if (effective == null || effective.getRequireApproval() == null || effective.getRequireApproval().isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static URI safeUri(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
