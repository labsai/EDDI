/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.mcpcalls.impl;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.mcpcalls.model.McpCall;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.impl.McpToolProviderManager;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.*;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Lifecycle task for deterministic (action-triggered) MCP tool calls.
 *
 * <p>
 * Sits in the pipeline alongside {@code ApiCallsTask}. When behavior rules emit
 * actions, this task matches them against {@link McpCall#getActions()} entries,
 * executes the corresponding MCP tool via {@link McpToolProviderManager}, and
 * stores results in conversation memory.
 * </p>
 *
 * <h3>Pipeline position:</h3>
 *
 * <pre>
 * Parser → Rules → HttpCalls → McpCalls → LLM → Output
 * </pre>
 *
 * <h3>Execution flow per McpCall:</h3>
 * <ol>
 * <li>Run preRequest property instructions (variable preparation)</li>
 * <li>Template toolArguments from conversation memory</li>
 * <li>Validate toolName against whitelist/blacklist</li>
 * <li>Execute MCP tool via McpToolProviderManager</li>
 * <li>Store result in memory (if saveResponse = true)</li>
 * <li>Run postResponse property instructions (result processing)</li>
 * </ol>
 */
@ApplicationScoped
public class McpCallsTask implements ILifecycleTask {
    public static final String ID = "ai.labs.mcpcalls";
    public static final TaskId TASK_ID = new TaskId(ID);

    private static final String KEY_ACTIONS = "actions";
    private static final String KEY_MCP_CALLS = "mcpCalls";

    private static final Logger LOGGER = Logger.getLogger(McpCallsTask.class);

    /**
     * Provenance tag used when matching {@code hitlConfig.toolApprovals} patterns —
     * the same {@code "mcp"} source {@code AgentOrchestrator} reports for remote
     * MCP tools, so one pattern governs both doors.
     */
    private static final String TOOL_SOURCE_MCP = "mcp";

    private final IResourceClientLibrary resourceClientLibrary;
    private final IMemoryItemConverter memoryItemConverter;
    private final IJsonSerialization jsonSerialization;
    private final McpToolProviderManager mcpToolProviderManager;
    private final PrePostUtils prePostUtils;
    private final ToolExecutionService toolExecutionService;
    private final ToolApprovalGate toolApprovalGate = new ToolApprovalGate();

    /**
     * Cluster-wide kill switch for the tool-approval gate, mirroring
     * {@code LlmTask.toolHitlEnabled} so an operator can disable both doors
     * together during a rolling upgrade.
     */
    @Inject
    @ConfigProperty(name = "eddi.hitl.tool.enabled", defaultValue = "true")
    boolean toolHitlEnabled;

    /** Rate limit (calls/minute) applied to rule-triggered MCP tool calls. */
    @Inject
    @ConfigProperty(name = "eddi.mcpcalls.default-rate-limit", defaultValue = "100")
    int defaultRateLimit;

    @Inject
    public McpCallsTask(IResourceClientLibrary resourceClientLibrary, IMemoryItemConverter memoryItemConverter, IJsonSerialization jsonSerialization,
            McpToolProviderManager mcpToolProviderManager, PrePostUtils prePostUtils, ToolExecutionService toolExecutionService) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.memoryItemConverter = memoryItemConverter;
        this.jsonSerialization = jsonSerialization;
        this.mcpToolProviderManager = mcpToolProviderManager;
        this.prePostUtils = prePostUtils;
        this.toolExecutionService = toolExecutionService;
    }

    @Override
    public TaskId getId() {
        return TASK_ID;
    }

    @Override
    public String getType() {
        return KEY_MCP_CALLS;
    }

    @Override
    public void execute(IConversationMemory memory, Object component) throws LifecycleException {
        McpCallsConfiguration config = (McpCallsConfiguration) component;
        IWritableConversationStep currentStep = memory.getCurrentStep();

        // Read current actions from memory
        IData<List<String>> actionsData = currentStep.getLatestData(KEY_ACTIONS);
        if (actionsData == null || actionsData.getResult() == null) {
            return;
        }
        List<String> actions = actionsData.getResult();

        // No mcpCalls defined? Nothing to do in pipeline mode
        if (config.getMcpCalls() == null || config.getMcpCalls().isEmpty()) {
            return;
        }

        // Connect to the MCP server and discover tools (cached)
        McpServerConfig serverConfig = toServerConfig(config);
        McpToolProviderManager.McpToolsResult mcpTools = mcpToolProviderManager.discoverTools(List.of(serverConfig));

        if (mcpTools == null || mcpTools.toolSpecs().isEmpty()) {
            LOGGER.warnf("No tools discovered from MCP server '%s' — skipping", config.getMcpServerUrl());
            return;
        }

        // Build a lookup of available tool names (after whitelist/blacklist)
        Set<String> allowedToolNames = filterToolNames(mcpTools.toolSpecs(), config);

        // Template data from conversation memory
        Map<String, Object> templateDataObjects = memoryItemConverter.convert(memory);

        // Match actions and execute calls
        for (McpCall mcpCall : config.getMcpCalls()) {
            if (mcpCall.getActions() == null) {
                continue;
            }

            boolean triggered = mcpCall.getActions().contains("*") || mcpCall.getActions().stream().anyMatch(actions::contains);

            if (triggered) {
                executeMcpCall(memory, currentStep, config, mcpCall, mcpTools, allowedToolNames, templateDataObjects);
            }
        }
    }

    private void executeMcpCall(IConversationMemory memory, IWritableConversationStep currentStep, McpCallsConfiguration config, McpCall mcpCall,
                                McpToolProviderManager.McpToolsResult mcpTools, Set<String> allowedToolNames, Map<String, Object> templateDataObjects)
            throws LifecycleException {
        try {
            String callName = mcpCall.getName() != null ? mcpCall.getName() : mcpCall.getToolName();
            LOGGER.infof("Executing MCP call '%s' → tool '%s'", callName, mcpCall.getToolName());

            // 1. PreRequest — prepare template variables
            if (mcpCall.getPreRequest() != null) {
                templateDataObjects = prePostUtils.executePreRequestPropertyInstructions(memory, templateDataObjects, mcpCall.getPreRequest());
            }

            // 2. Validate tool against whitelist/blacklist
            String toolName = mcpCall.getToolName();
            if (!allowedToolNames.contains(toolName)) {
                LOGGER.warnf("MCP tool '%s' blocked by whitelist/blacklist for server '%s'", toolName, config.getMcpServerUrl());
                return;
            }

            // 3. Find the executor
            ToolExecutor executor = mcpTools.executors().get(toolName);
            if (executor == null) {
                LOGGER.warnf("MCP tool '%s' not found on server '%s'. Available: %s", toolName, config.getMcpServerUrl(),
                        mcpTools.executors().keySet());
                return;
            }

            // 4. Template the tool arguments
            String argumentsJson = "{}";
            if (mcpCall.getToolArguments() != null && !mcpCall.getToolArguments().isEmpty()) {
                // Template each value
                Map<String, Object> templatedArgs = new HashMap<>();
                for (var entry : mcpCall.getToolArguments().entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof String strVal) {
                        templatedArgs.put(entry.getKey(), prePostUtils.templateValues(strVal, templateDataObjects));
                    } else {
                        templatedArgs.put(entry.getKey(), value);
                    }
                }
                argumentsJson = jsonSerialization.serialize(templatedArgs);
            }

            // 5. Execute the tool
            ToolExecutionRequest toolRequest = ToolExecutionRequest.builder().name(toolName).arguments(argumentsJson).build();

            // Finding F14: this behavior-rule-triggered door reaches the SAME remote MCP
            // tools the LLM loop reaches, so it must honour the SAME human-approval
            // gate. Without this, hitlConfig.toolApprovals gated LLM-initiated calls and
            // silently let rule-initiated ones through.
            if (isApprovalGated(memory, toolRequest)) {
                LOGGER.warnf("MCP call '%s' → tool '%s' matches hitlConfig.toolApprovals.requireApproval and was NOT executed: "
                        + "rule-triggered MCP calls cannot be human-approved. Remove the tool from requireApproval or "
                        + "drive it through an LLM task instead.", callName, toolName);
                String deniedObjName = (mcpCall.getResponseObjectName() != null
                        ? mcpCall.getResponseObjectName()
                        : callName + "Response") + "Error";
                prePostUtils.createMemoryEntry(currentStep, "DENIED: tool '" + toolName + "' requires human approval", deniedObjName, KEY_MCP_CALLS);
                return;
            }

            // Finding F14: route through the same metering pipeline the LLM tool loop
            // uses — rate limiting, caching and cost tracking. Calling the executor
            // directly bypassed all three.
            String conversationId = memory.getConversationId();
            String toolResult = RetryConfiguration.executeWithRetry(
                    () -> toolExecutionService.executeToolWrapped(toolName, toolRequest.arguments(), null, conversationId,
                            () -> executor.execute(toolRequest, null), true, false, true, defaultRateLimit),
                    mcpCall.getRetry(),
                    "MCP call '" + callName + "'");
            LOGGER.infof("MCP call '%s' result: %d chars", callName, toolResult != null ? toolResult.length() : 0);

            // 6. Store result in memory
            if (mcpCall.getSaveResponse() != null && mcpCall.getSaveResponse() && toolResult != null) {
                String responseObjectName = mcpCall.getResponseObjectName();
                if (responseObjectName == null || responseObjectName.isBlank()) {
                    responseObjectName = callName + "Response";
                }

                // Try to parse as JSON, fallback to raw string
                Object responseObject;
                try {
                    responseObject = jsonSerialization.deserialize(toolResult.trim(), Object.class);
                } catch (IOException e) {
                    responseObject = toolResult;
                }

                templateDataObjects.put(responseObjectName, responseObject);
                prePostUtils.createMemoryEntry(currentStep, responseObject, responseObjectName, KEY_MCP_CALLS);
            }

            // 7. PostResponse — property instructions, output building, quick replies
            if (mcpCall.getPostResponse() != null) {
                prePostUtils.runPostResponse(memory, mcpCall.getPostResponse(), templateDataObjects, 200, false);
            }

        } catch (ITemplatingEngine.TemplateEngineException | IOException e) {
            LOGGER.errorf(e, "MCP template error for '%s'", mcpCall.getName());
            throw new LifecycleException("MCP call template failed: " + e.getMessage(), e);
        } catch (LifecycleException e) {
            // Retry exhaustion or non-retryable error from RetryConfiguration
            // Store error data, then check continueOnError
            String errorCallName = mcpCall.getName() != null ? mcpCall.getName() : mcpCall.getToolName();
            LOGGER.errorf(e, "MCP tool execution failed for '%s': %s", errorCallName, e.getMessage());
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            String truncated = errorMsg.length() > 500 ? errorMsg.substring(0, 500) : errorMsg;
            String errorObjName = (mcpCall.getResponseObjectName() != null
                    ? mcpCall.getResponseObjectName()
                    : errorCallName + "Response") + "Error";
            prePostUtils.createMemoryEntry(currentStep, truncated, errorObjName, KEY_MCP_CALLS);
            if (mcpCall.getPostResponse() != null) {
                try {
                    prePostUtils.runPostResponse(memory, mcpCall.getPostResponse(), templateDataObjects, 500, true);
                } catch (Exception postEx) {
                    LOGGER.warnf("Post-response processing failed during error handling for '%s': %s",
                            errorCallName, postEx.getMessage());
                }
            }
            if (!Boolean.TRUE.equals(mcpCall.getContinueOnError())) {
                throw e;
            }
            LOGGER.warnf("MCP call '%s' failed but continueOnError=true, proceeding", errorCallName);
        }
    }

    /**
     * Finding F14: decide whether this rule-triggered MCP tool call is covered by
     * the agent's {@code hitlConfig.toolApprovals.requireApproval} patterns.
     * <p>
     * A rule-triggered call has no LLM tool loop to pause and resume into, so the
     * gate is enforced <em>fail-closed</em>: a gated call is refused rather than
     * executed. That keeps the human-approval guarantee intact — the alternative
     * (executing it because no resume path exists) is exactly the hole this finding
     * describes.
     */
    boolean isApprovalGated(IConversationMemory memory, ToolExecutionRequest toolRequest) {
        if (!toolHitlEnabled) {
            return false;
        }
        ToolApprovalsConfig approvals = memory.getAgentToolApprovalsConfig();
        if (approvals == null || toolRequest.name() == null) {
            return false;
        }
        var gateResult = toolApprovalGate.classify(List.of(toolRequest), Map.of(toolRequest.name(), TOOL_SOURCE_MCP), approvals, Set.of());
        return !gateResult.gated().isEmpty();
    }

    /**
     * Filter tool names based on whitelist and blacklist.
     */
    private Set<String> filterToolNames(List<ToolSpecification> allSpecs, McpCallsConfiguration config) {
        Set<String> allowed = new LinkedHashSet<>();

        List<String> whitelist = config.getToolsWhitelist();
        List<String> blacklist = config.getToolsBlacklist();

        for (ToolSpecification spec : allSpecs) {
            String name = spec.name();

            // Apply whitelist: if non-empty, only allow listed tools
            if (whitelist != null && !whitelist.isEmpty() && !whitelist.contains(name)) {
                continue;
            }

            // Apply blacklist: exclude listed tools
            if (blacklist != null && blacklist.contains(name)) {
                continue;
            }

            allowed.add(name);
        }

        return allowed;
    }

    /**
     * Convert McpCallsConfiguration to McpServerConfig for McpToolProviderManager.
     */
    private McpServerConfig toServerConfig(McpCallsConfiguration config) {
        McpServerConfig serverConfig = new McpServerConfig();
        serverConfig.setUrl(config.getMcpServerUrl());
        serverConfig.setName(config.getName());
        serverConfig.setTransport(config.getTransport());
        serverConfig.setApiKey(config.getApiKey());
        serverConfig.setTimeoutMs(config.getTimeoutMs());
        return serverConfig;
    }

    @Override
    public Object configure(Map<String, Object> configuration, Map<String, Object> extensions) throws WorkflowConfigurationException {
        Object uriObj = configuration.get("uri");
        if (isNullOrEmpty(uriObj)) {
            throw new WorkflowConfigurationException("No resource URI has been defined for McpCalls!");
        }
        URI uri = URI.create(uriObj.toString());
        try {
            McpCallsConfiguration config = resourceClientLibrary.getResource(uri, McpCallsConfiguration.class);
            // Finding I3/A10: reject settings the engine cannot honour (unimplemented
            // transport, non-http URL) at deploy time rather than accepting them and
            // silently doing something else.
            if (config != null) {
                config.validate();
            }
            return config;
        } catch (ServiceException e) {
            throw new WorkflowConfigurationException(e.getLocalizedMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new WorkflowConfigurationException("Invalid MCP calls configuration at " + uri + ": " + e.getMessage(), e);
        }
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor descriptor = new ExtensionDescriptor(new TaskId(ID));
        descriptor.setDisplayName("MCP Calls");
        descriptor.getConfigs().put("uri", new ExtensionDescriptor.ConfigValue("Resource URI", ExtensionDescriptor.FieldType.URI, false, null));
        return descriptor;
    }
}
