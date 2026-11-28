/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.mcpcalls.model.McpCall;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.hitl.tools.TaskToolApprovalsResolver;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.EscalationGuard;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
                LOGGER.debugf("Tool inspection skipped for agent %s: %s", LogSanitizer.sanitize(target.getTargetId()),
                        LogSanitizer.sanitize(e.getMessage()));
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
                    if (!task.usesTools(hasApiCalls, hasMcpCalls)) {
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

    /**
     * The targets whose agent holds, by name, a tool or an HTTP call that a turn a
     * mail started may never use ({@link EscalationGuard}): as
     * {@code "<target>: <tool>"}. Refused at save time (email plan v3 §5.3).
     * <p>
     * <b>Best effort</b>, never the boundary — the runtime refusal is. It reads
     * only what a configuration names: built-in tool and {@code tools} entries of
     * the LLM tasks, {@code mcpcalls} whitelists and calls, and {@code httpcalls}
     * method and path. It cannot see the tools an MCP server lists at runtime,
     * skips GROUP targets and documents it cannot read, and does not run when the
     * agent is changed after the integration was saved.
     */
    public List<String> targetsHoldingGuardedTools(List<ChannelTarget> targets) {
        List<String> found = new ArrayList<>();
        if (targets == null) {
            return found;
        }
        for (ChannelTarget target : targets) {
            if (target == null || target.getType() == ChannelTarget.TargetType.GROUP || target.getTargetId() == null) {
                continue;
            }
            try {
                for (String tool : guardedToolsOf(target.getTargetId())) {
                    found.add(target.getName() + ": " + tool);
                }
            } catch (Exception e) {
                LOGGER.debugf("Guarded-tool inspection skipped for agent %s: %s", LogSanitizer.sanitize(target.getTargetId()),
                        LogSanitizer.sanitize(e.getMessage()));
            }
        }
        return found;
    }

    Set<String> guardedToolsOf(String agentId) throws Exception {
        Set<String> found = new LinkedHashSet<>();
        int version = agentStore.getCurrentResourceId(agentId).getVersion();
        AgentConfiguration agent = agentStore.read(agentId, version);
        if (agent == null || agent.getWorkflows() == null) {
            return found;
        }
        for (URI workflowUri : agent.getWorkflows()) {
            var workflowId = RestUtilities.extractResourceId(workflowUri);
            if (workflowId == null || workflowId.getId() == null || workflowId.getVersion() == null) {
                continue;
            }
            WorkflowConfiguration workflow = workflowStore.read(workflowId.getId(), workflowId.getVersion());
            if (workflow == null || workflow.getWorkflowSteps() == null) {
                continue;
            }
            for (var step : workflow.getWorkflowSteps()) {
                URI uri = step.getConfig() != null && step.getConfig().get("uri") instanceof String value ? safeUri(value) : null;
                if (uri == null || uri.getHost() == null) {
                    continue;
                }
                try {
                    switch (uri.getHost()) {
                        case LLM_AUTHORITY -> guardedLlmTools(uri, found);
                        case API_CALLS_AUTHORITY, HTTP_CALLS_AUTHORITY -> guardedHttpCalls(uri, found);
                        case MCP_CALLS_AUTHORITY -> guardedMcpCalls(uri, found);
                        default -> {
                            // not relevant
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debugf("Guarded-tool inspection skipped for %s: %s", LogSanitizer.sanitize(String.valueOf(uri)),
                            LogSanitizer.sanitize(e.getMessage()));
                }
            }
        }
        return found;
    }

    private void guardedLlmTools(URI uri, Set<String> found) throws Exception {
        LlmConfiguration llm = resourceClientLibrary.getResource(uri, LlmConfiguration.class);
        if (llm == null || llm.tasks() == null) {
            return;
        }
        for (LlmConfiguration.Task task : llm.tasks()) {
            addGuarded(task.getBuiltInToolsWhitelist(), found);
            addGuarded(task.getTools(), found);
        }
    }

    private void guardedMcpCalls(URI uri, Set<String> found) throws Exception {
        McpCallsConfiguration mcp = resourceClientLibrary.getResource(uri, McpCallsConfiguration.class);
        if (mcp == null) {
            return;
        }
        addGuarded(mcp.getToolsWhitelist(), found);
        if (mcp.getMcpCalls() != null) {
            for (McpCall call : mcp.getMcpCalls()) {
                if (call != null && EscalationGuard.isGuardedTool(call.getToolName())) {
                    found.add(call.getToolName());
                }
            }
        }
    }

    private void guardedHttpCalls(URI uri, Set<String> found) throws Exception {
        ApiCallsConfiguration calls = resourceClientLibrary.getResource(uri, ApiCallsConfiguration.class);
        if (calls == null || calls.getHttpCalls() == null) {
            return;
        }
        String base = calls.getTargetServerUrl() == null ? "" : calls.getTargetServerUrl();
        for (ApiCall call : calls.getHttpCalls()) {
            if (call == null || call.getRequest() == null) {
                continue;
            }
            String path = call.getRequest().getPath() == null ? "" : call.getRequest().getPath();
            String method = call.getRequest().getMethod();
            if (EscalationGuard.isGuardedRequest(method, pathOf(base) + "/" + path)) {
                found.add((call.getName() == null ? "httpcall" : call.getName()) + " (" + method + " " + path + ")");
            }
        }
    }

    private static String pathOf(String url) {
        URI parsed = safeUri(url.replace("{", "%7B").replace("}", "%7D"));
        return parsed == null || parsed.getRawPath() == null ? url : parsed.getRawPath();
    }

    private static void addGuarded(List<String> names, Set<String> found) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            if (EscalationGuard.isGuardedTool(name)) {
                found.add(name);
            }
        }
    }

    private static URI safeUri(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
