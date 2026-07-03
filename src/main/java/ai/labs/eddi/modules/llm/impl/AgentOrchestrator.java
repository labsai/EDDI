/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.setup.AgentSetupService;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.UserMemoryTool;
import ai.labs.eddi.modules.llm.tools.ConversationRecallTool;
import ai.labs.eddi.modules.llm.tools.CreateSubAgentTool;
import ai.labs.eddi.modules.llm.tools.ConverseWithAgentTool;
import ai.labs.eddi.modules.llm.tools.FindAgentsByCapabilityTool;
import ai.labs.eddi.modules.llm.tools.TeardownAgentTool;
import ai.labs.eddi.modules.llm.tools.impl.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Executes the tool-calling agent loop against a ChatModel.
 * <p>
 * Responsibilities:
 * <ul>
 * <li>Collect enabled built-in tools based on task configuration</li>
 * <li>Build tool specifications and executors via reflection</li>
 * <li>Run the iterative tool-calling loop (max 10 iterations)</li>
 * <li>Manage budget checks, rate limiting, caching, and cost tracking via
 * ToolExecutionService</li>
 * <li>Produce an execution trace for debugging</li>
 * </ul>
 */
class AgentOrchestrator {
    private static final Logger LOGGER = Logger.getLogger(AgentOrchestrator.class);
    private static final String HTTPCALLS_TYPE = "eddi://ai.labs.httpcalls";
    private static final String MCPCALLS_TYPE = "eddi://ai.labs.mcpcalls";

    /** Well-known data keys for dynamic agent lifecycle tracking. */
    public static final String KEY_DYNAMIC_CREATED_AGENT_IDS = "dynamic:created_agent_ids";
    public static final String KEY_DYNAMIC_RETAINED_AGENT_IDS = "dynamic:retained_agent_ids";

    // === Tool-level HITL (tool-approval gate) ===
    /** Step-data key holding this turn's cumulative gated-pause count (int). */
    static final String KEY_TOOL_PAUSE_COUNT = "hitl:tool_pause_count";
    /** Default max pauses per turn when the config does not specify one. */
    private static final int DEFAULT_MAX_PAUSES_PER_TURN = 3;
    /** Default transcript serialization cap (bytes). */
    private static final int DEFAULT_TRANSCRIPT_MAX_BYTES = PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT;
    /** Approver-facing pause reason cap (chars). */
    private static final int PAUSE_REASON_MAX_CHARS = 500;

    // Stateless helpers — safe to instantiate directly (no CDI needed).
    private final ToolApprovalGate toolApprovalGate = new ToolApprovalGate();
    private final ChatTranscriptCodec chatTranscriptCodec = new ChatTranscriptCodec();

    // Built-in tools
    private final CalculatorTool calculatorTool;
    private final DateTimeTool dateTimeTool;
    private final WebSearchTool webSearchTool;
    private final DataFormatterTool dataFormatterTool;
    private final WebScraperTool webScraperTool;
    private final TextSummarizerTool textSummarizerTool;
    private final PdfReaderTool pdfReaderTool;
    private final WeatherTool weatherTool;
    private final FetchToolResponsePageTool fetchToolResponsePageTool;
    private final ToolExecutionService toolExecutionService;
    private final McpToolProviderManager mcpToolProviderManager;
    private final A2AToolProviderManager a2aToolProviderManager;

    // For httpcall auto-discovery from workflow
    private final IRestAgentStore restAgentStore;
    private final IRestWorkflowStore restWorkflowStore;
    private final IResourceClientLibrary resourceClientLibrary;
    private final IApiCallExecutor apiCallExecutor;
    private final IJsonSerialization jsonSerialization;
    private final IMemoryItemConverter memoryItemConverter;
    private final IUserMemoryStore userMemoryStore;
    private final ToolResponseTruncator toolResponseTruncator;
    private final TenantQuotaService tenantQuotaService;
    private final MemorySnapshotService memorySnapshotService;

    // Dynamic agent creation dependencies
    private final AgentSetupService agentSetupService;
    private final CapabilityRegistryService capabilityRegistryService;
    private final IConversationService conversationService;
    private final IAgentFactory agentFactory;
    private final IAgentStore agentStore;

    AgentOrchestrator(CalculatorTool calculatorTool, DateTimeTool dateTimeTool, WebSearchTool webSearchTool, DataFormatterTool dataFormatterTool,
            WebScraperTool webScraperTool, TextSummarizerTool textSummarizerTool, PdfReaderTool pdfReaderTool, WeatherTool weatherTool,
            FetchToolResponsePageTool fetchToolResponsePageTool,
            ToolExecutionService toolExecutionService, McpToolProviderManager mcpToolProviderManager, A2AToolProviderManager a2aToolProviderManager,
            IRestAgentStore restAgentStore, IRestWorkflowStore restWorkflowStore, IResourceClientLibrary resourceClientLibrary,
            IApiCallExecutor apiCallExecutor, IJsonSerialization jsonSerialization, IMemoryItemConverter memoryItemConverter,
            IUserMemoryStore userMemoryStore, ToolResponseTruncator toolResponseTruncator, TenantQuotaService tenantQuotaService,
            MemorySnapshotService memorySnapshotService,
            AgentSetupService agentSetupService, CapabilityRegistryService capabilityRegistryService,
            IConversationService conversationService, IAgentFactory agentFactory, IAgentStore agentStore) {
        this.calculatorTool = calculatorTool;
        this.dateTimeTool = dateTimeTool;
        this.webSearchTool = webSearchTool;
        this.dataFormatterTool = dataFormatterTool;
        this.webScraperTool = webScraperTool;
        this.textSummarizerTool = textSummarizerTool;
        this.pdfReaderTool = pdfReaderTool;
        this.weatherTool = weatherTool;
        this.toolExecutionService = toolExecutionService;
        this.mcpToolProviderManager = mcpToolProviderManager;
        this.a2aToolProviderManager = a2aToolProviderManager;
        this.fetchToolResponsePageTool = fetchToolResponsePageTool;
        this.restAgentStore = restAgentStore;
        this.restWorkflowStore = restWorkflowStore;
        this.resourceClientLibrary = resourceClientLibrary;
        this.apiCallExecutor = apiCallExecutor;
        this.jsonSerialization = jsonSerialization;
        this.memoryItemConverter = memoryItemConverter;
        this.userMemoryStore = userMemoryStore;
        this.toolResponseTruncator = toolResponseTruncator;
        this.tenantQuotaService = tenantQuotaService;
        this.memorySnapshotService = memorySnapshotService;
        this.agentSetupService = agentSetupService;
        this.capabilityRegistryService = capabilityRegistryService;
        this.conversationService = conversationService;
        this.agentFactory = agentFactory;
        this.agentStore = agentStore;
    }

    /**
     * Result of an agent execution.
     *
     * @param response
     *            the final LLM text response
     * @param trace
     *            list of tool call/result trace entries for debugging
     */
    record ExecutionResult(String response, List<Map<String, Object>> trace) {
    }

    /**
     * Collect enabled tools, append tool instructions to system message, and
     * execute the tool-calling loop.
     *
     * @return null if no tools are enabled (caller should use legacy mode),
     *         otherwise the execution result
     */
    ExecutionResult executeIfToolsEnabled(ChatModel chatModel, String systemMessage, List<ChatMessage> chatMessages, LlmConfiguration.Task task,
                                          IConversationMemory memory)
            throws LifecycleException {
        // Backward-compatible overload: no tool-approval gate (config null, index -1).
        return executeIfToolsEnabled(chatModel, systemMessage, chatMessages, task, memory, null, -1);
    }

    /**
     * Collect enabled tools and run the tool-calling loop with the tool-approval
     * gate active.
     *
     * @param effectiveToolApprovals
     *            the resolved tool-approval config (task override or agent
     *            default); {@code null}/empty means the gate is inert —
     *            byte-identical to the pre-HITL behavior.
     * @param llmTaskIndex
     *            the position of {@code task} in the llmConfig task list — recorded
     *            on the pending batch so resume re-enters the correct task.
     */
    ExecutionResult executeIfToolsEnabled(ChatModel chatModel, String systemMessage, List<ChatMessage> chatMessages, LlmConfiguration.Task task,
                                          IConversationMemory memory, ToolApprovalsConfig effectiveToolApprovals, int llmTaskIndex)
            throws LifecycleException {

        // Collect enabled built-in tools
        List<Object> enabledTools = collectEnabledTools(task, memory);

        // Discover httpcall tools from workflow (auto-discovery)
        boolean enableHttpCallTools = task.getEnableHttpCallTools() == null || task.getEnableHttpCallTools();
        HttpCallToolsResult httpCallTools = null;
        if (enableHttpCallTools) {
            httpCallTools = discoverHttpCallTools(memory);
        }
        boolean hasHttpCallTools = httpCallTools != null && !httpCallTools.toolSpecs().isEmpty();

        // Discover mcpcalls tools from workflow (auto-discovery)
        boolean enableMcpCallTools = task.getEnableMcpCallTools() == null || task.getEnableMcpCallTools();
        McpToolProviderManager.McpToolsResult mcpCallWorkflowTools = null;
        if (enableMcpCallTools) {
            mcpCallWorkflowTools = discoverMcpCallTools(memory);
        }
        boolean hasMcpCallWorkflowTools = mcpCallWorkflowTools != null && !mcpCallWorkflowTools.toolSpecs().isEmpty();

        // Discover A2A agent tools (if configured)
        A2AToolProviderManager.A2AToolsResult a2aTools = null;
        List<A2AAgentConfig> a2aAgents = task.getA2aAgents();
        if (a2aAgents != null && !a2aAgents.isEmpty()) {
            a2aTools = a2aToolProviderManager.discoverTools(a2aAgents);
        }
        boolean hasA2aTools = a2aTools != null && !a2aTools.toolSpecs().isEmpty();

        // No tools? Return null — caller should use legacy mode
        if (enabledTools.isEmpty() && !hasHttpCallTools && !hasMcpCallWorkflowTools && !hasA2aTools) {
            return null;
        }

        return executeWithTools(chatModel, systemMessage, chatMessages, enabledTools, httpCallTools, mcpCallWorkflowTools, a2aTools, task, memory,
                effectiveToolApprovals, llmTaskIndex);
    }

    /**
     * Executes the tool-calling loop using direct ChatModel API.
     */
    private ExecutionResult executeWithTools(ChatModel chatModel, String systemMessage, List<ChatMessage> chatMessages, List<Object> tools,
                                             HttpCallToolsResult httpCallTools, McpToolProviderManager.McpToolsResult mcpCallWorkflowTools,
                                             A2AToolProviderManager.A2AToolsResult a2aTools, LlmConfiguration.Task task, IConversationMemory memory,
                                             ToolApprovalsConfig effectiveToolApprovals, int llmTaskIndex)
            throws LifecycleException {

        // Build tool specifications and executors from built-in tool objects.
        // toolSources maps dispatch name → provenance ("builtin"/"http"/"mcp"/…) so
        // the gate can match qualified "source:name" patterns; missing entries are
        // tolerated (the gate falls back to bare-name matching).
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        Map<String, ToolExecutor> toolExecutors = new HashMap<>();
        Map<String, String> toolSources = new HashMap<>();

        for (Object tool : tools) {
            // CDI proxies don't carry @Tool annotations — resolve to actual bean class
            Class<?> toolClass = tool.getClass();
            if (toolClass.getName().contains("_ClientProxy") || toolClass.getName().contains("$$")) {
                toolClass = toolClass.getSuperclass();
            }

            var specs = ToolSpecifications.toolSpecificationsFrom(toolClass);
            toolSpecs.addAll(specs);

            // Find methods annotated with @Tool and map them to executors
            for (java.lang.reflect.Method method : toolClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                    dev.langchain4j.agent.tool.Tool toolAnnotation = method.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
                    String toolName = toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();
                    toolExecutors.put(toolName, new DefaultToolExecutor(tool, method));
                    toolSources.put(toolName, sourceForBuiltInTool(tool));
                }
            }
        }

        // --- LAZY mode: separate built-in specs from external specs ---
        // In LAZY mode, all built-in tool executors are registered (so they CAN be
        // called), but initially only discover_tools spec is presented to the LLM.
        // After the LLM calls discover_tools, we parse the result and activate the
        // matching built-in specs for subsequent iterations.
        boolean isLazy = task.getToolLoadingStrategy() == LlmConfiguration.ToolLoadingStrategy.LAZY;
        List<ToolSpecification> builtInSpecs = new ArrayList<>(toolSpecs); // copy before merging external

        // Merge httpcall tools discovered from workflow (if any)
        if (httpCallTools != null && !httpCallTools.toolSpecs().isEmpty()) {
            toolSpecs.addAll(httpCallTools.toolSpecs());
            toolExecutors.putAll(httpCallTools.executors());
            httpCallTools.executors().keySet().forEach(name -> toolSources.put(name, "http"));
        }

        // Merge mcpcalls tools discovered from workflow (if any)
        if (mcpCallWorkflowTools != null && !mcpCallWorkflowTools.toolSpecs().isEmpty()) {
            toolSpecs.addAll(mcpCallWorkflowTools.toolSpecs());
            toolExecutors.putAll(mcpCallWorkflowTools.executors());
            mcpCallWorkflowTools.executors().keySet().forEach(name -> toolSources.put(name, "mcp"));
        }

        // Merge A2A agent tools (if any)
        if (a2aTools != null && !a2aTools.toolSpecs().isEmpty()) {
            toolSpecs.addAll(a2aTools.toolSpecs());
            toolExecutors.putAll(a2aTools.executors());
            a2aTools.executors().keySet().forEach(name -> toolSources.put(name, "a2a"));
        }

        // Active specs: what the LLM currently sees
        List<ToolSpecification> activeSpecs;
        if (isLazy) {
            // Start with only discover_tools + all external tools (HTTP/MCP/A2A)
            activeSpecs = new ArrayList<>();
            for (ToolSpecification spec : builtInSpecs) {
                if ("discover_tools".equals(spec.name())) {
                    activeSpecs.add(spec);
                }
            }
            // Add external tool specs (always visible regardless of strategy)
            int externalCount = 0;
            if (httpCallTools != null) {
                activeSpecs.addAll(httpCallTools.toolSpecs());
                externalCount += httpCallTools.toolSpecs().size();
            }
            if (mcpCallWorkflowTools != null) {
                activeSpecs.addAll(mcpCallWorkflowTools.toolSpecs());
                externalCount += mcpCallWorkflowTools.toolSpecs().size();
            }
            if (a2aTools != null) {
                activeSpecs.addAll(a2aTools.toolSpecs());
                externalCount += a2aTools.toolSpecs().size();
            }
            LOGGER.infof("LAZY mode: presenting %d specs initially (discover_tools + %d external)",
                    activeSpecs.size(), externalCount);
        } else {
            activeSpecs = toolSpecs;
        }

        // Build message list with system message if provided
        List<ChatMessage> messages = new ArrayList<>();
        if (!isNullOrEmpty(systemMessage)) {
            messages.add(SystemMessage.from(systemMessage));
        }
        messages.addAll(chatMessages);

        // Trace for tool calls
        List<Map<String, Object>> trace = new ArrayList<>();

        // Read config fields for tool execution controls
        boolean enableRateLimiting = task.getEnableRateLimiting() != null ? task.getEnableRateLimiting() : true;
        boolean enableCaching = task.getEnableToolCaching() != null ? task.getEnableToolCaching() : true;
        boolean enableCostTracking = task.getEnableCostTracking() != null ? task.getEnableCostTracking() : true;
        int defaultRateLimit = task.getDefaultRateLimit() != null ? task.getDefaultRateLimit() : 100;
        Map<String, Integer> toolRateLimits = task.getToolRateLimits();
        Double maxBudget = task.getMaxBudgetPerConversation();
        String conversationId = memory.getConversationId();

        // Execute with retry logic — the tool execution loop
        String response = AgentExecutionHelper.executeWithRetry(() -> {
            List<ChatMessage> currentMessages = new ArrayList<>(messages);
            int maxIterations = task.getMaxToolIterations() != null ? task.getMaxToolIterations() : 10;

            // Engine-enforced counterweight: strict mode caps iterations
            var counterweight = task.getCounterweight();
            if (counterweight != null && counterweight.isEnabled()
                    && "strict".equalsIgnoreCase(counterweight.getLevel())) {
                int strictCap = 5;
                if (maxIterations > strictCap) {
                    LOGGER.infof("Counterweight strict mode: capping tool iterations from %d to %d",
                            maxIterations, strictCap);
                    maxIterations = strictCap;
                }
            }

            for (int i = 0; i < maxIterations; i++) {
                ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(currentMessages);

                if (!activeSpecs.isEmpty()) {
                    requestBuilder.toolSpecifications(activeSpecs);
                }

                ChatRequest chatRequest = requestBuilder.build();

                ChatResponse chatResponse = chatModel.chat(chatRequest);
                AiMessage aiMessage = chatResponse.aiMessage();
                currentMessages.add(aiMessage);

                if (aiMessage.hasToolExecutionRequests()) {
                    // === Tool-approval gate (tool-level HITL) ===
                    // Split the batch into gated (require human approval) and allowed
                    // calls. clearedCallIds is empty for live turns (Task 9 passes
                    // approved ids on resume). Inert when effectiveToolApprovals is
                    // null/empty — byte-identical to the pre-HITL path.
                    var gateResult = toolApprovalGate.classify(aiMessage.toolExecutionRequests(), toolSources,
                            effectiveToolApprovals, Set.of());

                    if (!gateResult.gated().isEmpty()) {
                        int pausesSoFar = readToolPauseCount(memory);
                        if (pausesSoFar >= maxPausesPerTurn(effectiveToolApprovals)) {
                            // Fail-closed: the pause budget for this turn is spent —
                            // do not pause and do not execute the gated calls; tell
                            // the model to stop asking so the loop can still finish
                            // with the ungated results.
                            for (ToolExecutionRequest gatedReq : gateResult.gated()) {
                                currentMessages.add(ToolExecutionResultMessage.from(gatedReq,
                                        "{\"status\":\"DENIED\",\"reason\":\"approval-pause limit for this turn reached; do not retry\"}"));
                                Map<String, Object> capStep = new HashMap<>();
                                capStep.put("type", "tool_error");
                                capStep.put("tool", gatedReq.name());
                                capStep.put("error", "hitl_pause_cap");
                                trace.add(capStep);
                            }
                            // ungated calls still execute below
                        } else {
                            // 1) execute the ungated calls of this batch normally
                            for (ToolExecutionRequest allowedReq : gateResult.allowed()) {
                                executeSingleToolCall(allowedReq, memory, currentMessages, trace, toolExecutors,
                                        toolRateLimits, defaultRateLimit, maxBudget, conversationId,
                                        enableRateLimiting, enableCaching, enableCostTracking, task, isLazy, builtInSpecs, activeSpecs);
                            }
                            // 2) snapshot + persist the pending batch, then abort the loop
                            PendingToolCallBatch batch = buildPendingBatch(currentMessages, gateResult, task, memory,
                                    i, activatedToolNames(isLazy, activeSpecs), trace, pausesSoFar + 1, llmTaskIndex,
                                    toolSources);
                            memory.setHitlPendingToolCalls(batch);
                            incrementToolPauseCount(memory, pausesSoFar);
                            throw new ToolApprovalRequiredException(buildPauseReason(effectiveToolApprovals, gateResult), batch);
                        }
                    }

                    // Execute the allowed calls (was: aiMessage.toolExecutionRequests()).
                    // When the gate is inert, gateResult.allowed() == the full batch.
                    for (ToolExecutionRequest toolRequest : gateResult.allowed()) {
                        executeSingleToolCall(toolRequest, memory, currentMessages, trace, toolExecutors,
                                toolRateLimits, defaultRateLimit, maxBudget, conversationId,
                                enableRateLimiting, enableCaching, enableCostTracking, task, isLazy, builtInSpecs, activeSpecs);
                    }
                } else {
                    return aiMessage.text();
                }
            }

            AiMessage lastMessage = (AiMessage) currentMessages.get(currentMessages.size() - 1);
            return lastMessage.text() != null ? lastMessage.text() : "Max tool iterations reached";
        }, task, "Agent execution");

        return new ExecutionResult(response, trace);
    }

    /**
     * Executes a single tool call through the full per-request pipeline:
     * auto-checkpoint, trace entry, per-conversation budget check, tenant cost
     * budget check, executor dispatch (via {@link ToolExecutionService} for rate
     * limiting/caching/cost tracking), response truncation, result trace, and LAZY
     * activation. Extracted verbatim from the live loop so the live path and Task
     * 9's resume path share ONE copy.
     * <p>
     * Package-private for direct unit testing of the gate.
     */
    void executeSingleToolCall(ToolExecutionRequest toolRequest, IConversationMemory memory,
                               List<ChatMessage> currentMessages, List<Map<String, Object>> trace,
                               Map<String, ToolExecutor> toolExecutors, Map<String, Integer> toolRateLimits,
                               int defaultRateLimit, Double maxBudget, String conversationId,
                               boolean enableRateLimiting, boolean enableCaching, boolean enableCostTracking,
                               LlmConfiguration.Task task, boolean isLazy,
                               List<ToolSpecification> builtInSpecs, List<ToolSpecification> activeSpecs) {
        // Auto-checkpoint before tool execution (Wave 4)
        if (memorySnapshotService != null) {
            try {
                memorySnapshotService.createCheckpoint(
                        memory, "before_tool:" + toolRequest.name(),
                        AgentOrchestrator.class.getSimpleName());
            } catch (Exception cpEx) {
                LOGGER.warnf("Auto-checkpoint failed before tool '%s': %s",
                        toolRequest.name(), cpEx.getMessage());
            }
        }

        Map<String, Object> callStep = new HashMap<>();
        callStep.put("type", "tool_call");
        callStep.put("tool", toolRequest.name());
        callStep.put("arguments", toolRequest.arguments());
        trace.add(callStep);

        // Check per-conversation budget before executing tool
        if (maxBudget != null && conversationId != null
                && !toolExecutionService.getCostTracker().isWithinBudget(conversationId, maxBudget)) {
            String budgetError = "Budget exceeded for conversation " + conversationId;
            LOGGER.warn(sanitize(budgetError));

            Map<String, Object> budgetStep = new HashMap<>();
            budgetStep.put("type", "tool_error");
            budgetStep.put("tool", toolRequest.name());
            budgetStep.put("error", budgetError);
            trace.add(budgetStep);

            currentMessages.add(ToolExecutionResultMessage.from(toolRequest, "Error: " + budgetError));
            return;
        }

        // Check tenant-level monthly cost budget (MCP governance)
        if (tenantQuotaService != null) {
            var costCheck = tenantQuotaService.checkCostBudget(tenantQuotaService.getDefaultTenantId());
            if (!costCheck.allowed()) {
                LOGGER.warnf("Tenant cost budget exceeded during tool call: %s", costCheck.reason());

                Map<String, Object> quotaStep = new HashMap<>();
                quotaStep.put("type", "tool_error");
                quotaStep.put("tool", toolRequest.name());
                quotaStep.put("error", costCheck.reason());
                trace.add(quotaStep);

                currentMessages.add(ToolExecutionResultMessage.from(toolRequest, "Error: " + costCheck.reason()));
                return;
            }
        }

        // Execute through ToolExecutionService for rate limiting, caching, cost
        // tracking
        ToolExecutor executor = toolExecutors.get(toolRequest.name());
        String toolResult;
        if (executor != null) {
            int rateLimit = (toolRateLimits != null && toolRateLimits.containsKey(toolRequest.name()))
                    ? toolRateLimits.get(toolRequest.name())
                    : defaultRateLimit;

            toolResult = toolExecutionService.executeToolWrapped(toolRequest.name(), toolRequest.arguments(), conversationId,
                    () -> executor.execute(toolRequest, null), enableRateLimiting, enableCaching, enableCostTracking, rateLimit);
        } else {
            toolResult = "Error: Tool '" + toolRequest.name() + "' not found";
        }

        // Apply response truncation (MCP governance)
        toolResult = toolResponseTruncator.truncateIfNeeded(
                toolRequest.name(), toolResult, task.getToolResponseLimits(),
                task.getType(), task.getParameters());

        Map<String, Object> resultStep = new HashMap<>();
        resultStep.put("type", "tool_result");
        resultStep.put("tool", toolRequest.name());
        resultStep.put("result", toolResult);
        trace.add(resultStep);

        currentMessages.add(ToolExecutionResultMessage.from(toolRequest, toolResult));

        // LAZY mode: after discover_tools returns, activate the matching built-in specs
        if (isLazy && "discover_tools".equals(toolRequest.name())) {
            activateDiscoveredTools(toolResult, builtInSpecs, activeSpecs);
        }
    }

    // ─── Tool-approval gate helpers ───

    /** Maps a built-in tool instance to its gate source tag. */
    private static String sourceForBuiltInTool(Object tool) {
        Class<?> c = tool.getClass();
        if (c.getName().contains("_ClientProxy") || c.getName().contains("$$")) {
            c = c.getSuperclass();
        }
        String simple = c.getSimpleName();
        return switch (simple) {
            case "UserMemoryTool" -> "memory";
            case "ConversationRecallTool" -> "recall";
            case "CreateSubAgentTool", "ConverseWithAgentTool", "FindAgentsByCapabilityTool", "TeardownAgentTool" -> "dynamic";
            default -> "builtin";
        };
    }

    /** Reads this turn's cumulative gated-pause count (0 when absent). */
    private static int readToolPauseCount(IConversationMemory memory) {
        var step = memory.getCurrentStep();
        if (step == null) {
            return 0;
        }
        IData<Integer> data = step.getLatestData(KEY_TOOL_PAUSE_COUNT);
        if (data == null || data.getResult() == null) {
            return 0;
        }
        return data.getResult();
    }

    /** Writes the incremented gated-pause count for this turn. */
    private static void incrementToolPauseCount(IConversationMemory memory, int pausesSoFar) {
        var step = memory.getCurrentStep();
        if (step != null) {
            step.storeData(new Data<>(KEY_TOOL_PAUSE_COUNT, pausesSoFar + 1));
        }
    }

    /** Effective max pauses per turn (default 3, clamped 1..10). */
    private static int maxPausesPerTurn(ToolApprovalsConfig cfg) {
        if (cfg == null || cfg.getMaxPausesPerTurn() == null) {
            return DEFAULT_MAX_PAUSES_PER_TURN;
        }
        return Math.max(1, Math.min(10, cfg.getMaxPausesPerTurn()));
    }

    /** Names activated in LAZY mode (for resume reactivation); empty otherwise. */
    private static List<String> activatedToolNames(boolean isLazy, List<ToolSpecification> activeSpecs) {
        if (!isLazy) {
            return List.of();
        }
        return activeSpecs.stream().map(ToolSpecification::name).toList();
    }

    /**
     * Builds the approver-facing pause reason: {@code cfg.pauseReason} with
     * {@code {toolNames}} replaced by the comma-joined gated names, defaulting to
     * "Tool call requires approval: names". Redacted and capped at 500 chars.
     */
    private static String buildPauseReason(ToolApprovalsConfig cfg, ToolApprovalGate.GateResult gateResult) {
        String names = gateResult.gated().stream().map(ToolExecutionRequest::name).distinct()
                .reduce((a, b) -> a + ", " + b).orElse("");
        String template = cfg != null && cfg.getPauseReason() != null && !cfg.getPauseReason().isBlank()
                ? cfg.getPauseReason()
                : "Tool call requires approval: {toolNames}";
        String reason = template.replace("{toolNames}", names);
        reason = SecretRedactionFilter.redact(reason);
        if (reason.length() > PAUSE_REASON_MAX_CHARS) {
            reason = reason.substring(0, PAUSE_REASON_MAX_CHARS);
        }
        return reason;
    }

    /**
     * Snapshots the interrupted tool-call batch into a durable
     * {@link PendingToolCallBatch}. All approver-facing strings are secret-redacted
     * and size-capped.
     */
    PendingToolCallBatch buildPendingBatch(List<ChatMessage> currentMessages, ToolApprovalGate.GateResult gateResult,
                                           LlmConfiguration.Task task, IConversationMemory memory, int iterationIndex,
                                           List<String> activatedToolNames, List<Map<String, Object>> trace,
                                           int pauseCountThisTurn, int llmTaskIndex,
                                           Map<String, String> toolSources) {
        PendingToolCallBatch batch = new PendingToolCallBatch();
        batch.setPauseEpoch(UUID.randomUUID().toString());
        batch.setLlmTaskId(task.getId());
        batch.setLlmTaskIndex(llmTaskIndex);
        // workflowId is informational; the authoritative pause bookmark carries the
        // paused workflow id (set by the LifecycleManager → Conversation pause commit).
        batch.setIterationIndex(iterationIndex);
        batch.setActivatedToolNames(activatedToolNames);
        batch.setPauseCountThisTurn(pauseCountThisTurn);
        batch.setAutoApproveCount(0);

        // Serialize the transcript (capped); omitted flag drives fallback on resume.
        int transcriptMaxBytes = DEFAULT_TRANSCRIPT_MAX_BYTES;
        ChatTranscriptCodec.CodecResult codecResult = chatTranscriptCodec.serialize(currentMessages, transcriptMaxBytes);
        batch.setChatTranscriptJson(codecResult.json());
        batch.setTranscriptOmitted(codecResult.omitted());

        // Per gated call: cap raw args, redact + cap redacted args, carry gate reason.
        List<PendingToolCallBatch.PendingToolCall> calls = new ArrayList<>();
        for (ToolExecutionRequest req : gateResult.gated()) {
            var call = new PendingToolCallBatch.PendingToolCall();
            String callId = req.id() != null ? req.id() : "gen-" + UUID.randomUUID();
            call.setCallId(callId);
            call.setToolName(req.name());
            String source = toolSources.getOrDefault(req.name(), "unknown");
            call.setSource(source);

            String rawArgs = req.arguments() != null ? req.arguments() : "";
            byte[] rawBytes = rawArgs.getBytes(StandardCharsets.UTF_8);
            if (rawBytes.length > PendingToolCallBatch.ARGS_RAW_MAX_BYTES) {
                call.setArgumentsRaw(capUtf8(rawArgs, PendingToolCallBatch.ARGS_RAW_MAX_BYTES));
                call.setArgsTruncated(true);
            } else {
                call.setArgumentsRaw(rawArgs);
                call.setArgsTruncated(false);
            }

            String redacted = SecretRedactionFilter.redact(rawArgs);
            call.setArgumentsRedacted(capUtf8(redacted, PendingToolCallBatch.ARGS_REDACTED_MAX_BYTES));

            // gateReason: the matched pattern (by call id), fall back to bare name.
            String reason = req.id() != null ? gateResult.gateReasonByCallId().get(req.id()) : null;
            call.setGateReason(reason);
            calls.add(call);
        }
        batch.setCalls(calls);

        // Ungated calls of this batch that already executed (approver visibility).
        batch.setExecutedUngatedCallNames(gateResult.allowed().stream().map(ToolExecutionRequest::name).toList());

        // Deep copy of trace with each entry's "result" string capped.
        batch.setTraceSoFar(capTrace(trace));

        // Fingerprint over sorted gated (name + "|" + arguments).
        batch.setFingerprint(fingerprint(gateResult.gated()));

        return batch;
    }

    /** Caps a string to at most maxBytes UTF-8 bytes without splitting a char. */
    private static String capUtf8(String s, int maxBytes) {
        if (s == null) {
            return null;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return s;
        }
        int end = maxBytes;
        // Back off to a char boundary (avoid splitting a multi-byte sequence).
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /** Deep-copies the trace, capping each entry's "result" string. */
    private static List<Map<String, Object>> capTrace(List<Map<String, Object>> trace) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> entry : trace) {
            Map<String, Object> e = new HashMap<>(entry);
            Object result = e.get("result");
            if (result instanceof String rs && rs.getBytes(StandardCharsets.UTF_8).length > PendingToolCallBatch.TRACE_ENTRY_MAX_BYTES) {
                e.put("result", capUtf8(rs, PendingToolCallBatch.TRACE_ENTRY_MAX_BYTES));
            }
            copy.add(e);
        }
        return copy;
    }

    /** sha256Hex of sorted gated (name + "|" + arguments) joined by newline. */
    private static String fingerprint(List<ToolExecutionRequest> gated) {
        List<String> parts = new ArrayList<>();
        for (ToolExecutionRequest req : gated) {
            parts.add(req.name() + "|" + (req.arguments() != null ? req.arguments() : ""));
        }
        Collections.sort(parts);
        String joined = String.join("\n", parts);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM; treat as unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Parses the discover_tools JSON result and activates matching built-in tool
     * specs so the LLM can call them on subsequent iterations.
     */
    private void activateDiscoveredTools(String discoverResult,
                                         List<ToolSpecification> builtInSpecs,
                                         List<ToolSpecification> activeSpecs) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(discoverResult);
            JsonNode toolsNode = root.get("tools");
            if (toolsNode == null || !toolsNode.isArray()) {
                return;
            }

            Set<String> discoveredNames = new HashSet<>();
            for (JsonNode tool : toolsNode) {
                if (tool.has("name")) {
                    discoveredNames.add(tool.get("name").asText());
                }
            }

            // Add matching specs (skip discover_tools itself and already-active specs)
            Set<String> activeNames = new HashSet<>();
            for (ToolSpecification spec : activeSpecs) {
                activeNames.add(spec.name());
            }

            int activated = 0;
            for (ToolSpecification spec : builtInSpecs) {
                if (discoveredNames.contains(spec.name()) && !activeNames.contains(spec.name())) {
                    activeSpecs.add(spec);
                    activated++;
                }
            }

            LOGGER.infof("LAZY activation: %d tools activated from discovery (%s)",
                    activated, discoveredNames);
        } catch (Exception e) {
            LOGGER.warnf("Failed to parse discover_tools result for LAZY activation: %s",
                    e.getMessage());
        }
    }

    /**
     * Collects enabled built-in tools based on task configuration.
     * <p>
     * When {@link LlmConfiguration.ToolLoadingStrategy#LAZY} is set, ALL tools are
     * returned (so executors get registered), plus a {@link DiscoverToolsTool}
     * meta-tool. The {@code executeWithTools} method handles presenting only
     * {@code discover_tools} spec initially and activating matching specs after
     * discovery.
     */
    List<Object> collectEnabledTools(LlmConfiguration.Task task, IConversationMemory memory) {
        List<Object> tools = new ArrayList<>();

        if (task.getEnableBuiltInTools() == null || !task.getEnableBuiltInTools()) {
            return tools;
        }

        // Collect the full set of tools first (needed for both EAGER and LAZY)
        List<Object> allTools = collectAllBuiltInTools(task, memory);

        // LAZY strategy: return ALL tools + DiscoverToolsTool (so executors get
        // registered)
        // The executeWithTools method handles initially presenting only discover_tools
        if (task.getToolLoadingStrategy() == LlmConfiguration.ToolLoadingStrategy.LAZY) {
            // Build tool specs from all available tools for discovery
            List<ToolSpecification> allSpecs = new ArrayList<>();
            for (Object tool : allTools) {
                Class<?> toolClass = tool.getClass();
                if (toolClass.getName().contains("_ClientProxy") || toolClass.getName().contains("$$")) {
                    toolClass = toolClass.getSuperclass();
                }
                allSpecs.addAll(ToolSpecifications.toolSpecificationsFrom(toolClass));
            }
            int maxToolsInContext = task.getMaxToolsInContext();
            allTools.add(new DiscoverToolsTool(allSpecs, maxToolsInContext));
            LOGGER.infof("LAZY tool loading: %d built-in tools + discover_tools meta-tool registered", allSpecs.size());
            return allTools;
        }

        // EAGER strategy (default): return all tools directly
        LOGGER.info("Enabled " + allTools.size() + " built-in tools for agent");
        return allTools;
    }

    /**
     * Collects all built-in tools without considering loading strategy.
     */
    private List<Object> collectAllBuiltInTools(LlmConfiguration.Task task, IConversationMemory memory) {
        List<Object> tools = new ArrayList<>();
        List<String> whitelist = task.getBuiltInToolsWhitelist();

        if (whitelist != null && !whitelist.isEmpty()) {
            // Only add tools that are explicitly listed in the whitelist
            if (whitelist.contains("calculator"))
                tools.add(calculatorTool);
            if (whitelist.contains("datetime"))
                tools.add(dateTimeTool);
            if (whitelist.contains("websearch"))
                tools.add(webSearchTool);
            if (whitelist.contains("dataformatter"))
                tools.add(dataFormatterTool);
            if (whitelist.contains("webscraper"))
                tools.add(webScraperTool);
            if (whitelist.contains("textsummarizer"))
                tools.add(textSummarizerTool);
            if (whitelist.contains("pdfreader"))
                tools.add(pdfReaderTool);
            if (whitelist.contains("weather"))
                tools.add(weatherTool);
            if (whitelist.contains("fetch_page") || whitelist.contains("fetch_tool_response_page"))
                tools.add(fetchToolResponsePageTool);
            if (whitelist.contains("usermemory"))
                addUserMemoryToolIfEnabled(tools, memory);
            if (whitelist.contains("conversationRecall"))
                addConversationRecallToolIfEnabled(tools, task, memory);
            // Dynamic agent tools (whitelist-gated, shared tracking lists)
            {
                List<String> sharedCreatedIds = new java.util.concurrent.CopyOnWriteArrayList<>();
                Set<String> sharedRetainedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
                String parentAgentId = memory.getAgentId();
                String userId = memory.getUserId();
                DynamicAgentConfig dynamicConfig = resolveDynamicAgentConfig(memory);

                boolean anyDynamicToolAdded = false;
                if (whitelist.contains("create_sub_agent") && agentSetupService != null && conversationService != null) {
                    tools.add(new CreateSubAgentTool(agentSetupService,
                            conversationService, parentAgentId, userId, dynamicConfig,
                            sharedCreatedIds, sharedRetainedIds));
                    LOGGER.debugf("[DYNAMIC] CreateSubAgentTool enabled for agent='%s'", sanitize(parentAgentId));
                    anyDynamicToolAdded = true;
                }
                if (whitelist.contains("converse_with_agent") && conversationService != null) {
                    tools.add(new ConverseWithAgentTool(conversationService, userId));
                    LOGGER.debugf("[DYNAMIC] ConverseWithAgentTool enabled for agent='%s'", sanitize(parentAgentId));
                }
                if (whitelist.contains("find_agents_by_capability") && capabilityRegistryService != null) {
                    tools.add(new FindAgentsByCapabilityTool(capabilityRegistryService));
                    LOGGER.debugf("[DYNAMIC] FindAgentsByCapabilityTool enabled for agent='%s'", sanitize(parentAgentId));
                }
                if (whitelist.contains("teardown_agent") && agentFactory != null && agentStore != null) {
                    tools.add(new TeardownAgentTool(agentFactory, agentStore, sharedCreatedIds, sharedRetainedIds));
                    LOGGER.debugf("[DYNAMIC] TeardownAgentTool enabled for agent='%s'", sanitize(parentAgentId));
                    anyDynamicToolAdded = true;
                }

                // Store tracking lists in memory step data so GroupConversationService
                // can read them from the snapshot after each member turn and propagate
                // to GroupConversation for lifecycle cleanup (Copilot PR review fix).
                // The lists are stored by reference — after tool execution, they'll
                // contain all agent IDs accumulated during this turn.
                if (anyDynamicToolAdded) {
                    memory.getCurrentStep().storeData(
                            new ai.labs.eddi.engine.memory.model.Data<>(KEY_DYNAMIC_CREATED_AGENT_IDS, sharedCreatedIds));
                    memory.getCurrentStep().storeData(
                            new ai.labs.eddi.engine.memory.model.Data<>(KEY_DYNAMIC_RETAINED_AGENT_IDS, sharedRetainedIds));
                }
            }
        } else {
            // No whitelist — add all built-in tools
            tools.add(calculatorTool);
            tools.add(dateTimeTool);
            tools.add(webSearchTool);
            tools.add(dataFormatterTool);
            tools.add(webScraperTool);
            tools.add(textSummarizerTool);
            tools.add(pdfReaderTool);
            tools.add(weatherTool);
            tools.add(fetchToolResponsePageTool);
            // Auto-add user memory tool if agent has it enabled
            addUserMemoryToolIfEnabled(tools, memory);
            // Auto-add conversation recall tool if rolling summary is active
            addConversationRecallToolIfEnabled(tools, task, memory);
        }

        return tools;
    }

    /**
     * Constructs and adds a UserMemoryTool if the agent has persistent user memory
     * enabled. The tool is created per-invocation with conversation-specific
     * context.
     */
    private void addUserMemoryToolIfEnabled(List<Object> tools, IConversationMemory memory) {
        AgentConfiguration.UserMemoryConfig config = memory.getUserMemoryConfig();
        if (config == null || userMemoryStore == null)
            return;

        // Extract groupIds from conversation properties (injected by
        // GroupConversationService)
        List<String> groupIds = List.of();
        var props = memory.getConversationProperties();
        if (props != null) {
            Object groupIdProp = props.get("groupId");
            if (groupIdProp instanceof Property p && p.getValueString() != null) {
                groupIds = List.of(p.getValueString());
            }
        }

        var tool = new UserMemoryTool(userMemoryStore, memory.getUserId(), memory.getAgentId(), memory.getConversationId(), groupIds, config);
        tools.add(tool);
        LOGGER.infof("[MEMORY] UserMemoryTool enabled for agent='%s', user='%s', groups=%s", sanitize(memory.getAgentId()),
                sanitize(memory.getUserId()), groupIds.stream().map(g -> sanitize(g)).toList());
    }

    /**
     * Constructs and adds a ConversationRecallTool if a rolling summary is active.
     * The tool is created per-invocation with the conversation's output list and
     * the summary step boundary.
     */
    private void addConversationRecallToolIfEnabled(List<Object> tools, LlmConfiguration.Task task, IConversationMemory memory) {
        // Only add if rolling summary is configured and a summary exists
        var summaryConfig = task.getConversationSummary();
        if (summaryConfig == null || !summaryConfig.isEnabled())
            return;

        String existingSummary = ConversationSummarizer.readSummary(memory);
        if (existingSummary == null)
            return;

        int throughStep = ConversationSummarizer.readSummaryThroughStep(memory);
        var tool = new ConversationRecallTool(List.copyOf(memory.getConversationOutputs()), throughStep, summaryConfig.getMaxRecallTurns());
        tools.add(tool);
        LOGGER.infof("[RECALL] ConversationRecallTool enabled: summaryThroughStep=%d, maxRecallTurns=%d", throughStep,
                summaryConfig.getMaxRecallTurns());
    }

    /**
     * Resolves the DynamicAgentConfig for the current conversation. If the agent is
     * participating in a group discussion, the group's {@link DynamicAgentConfig}
     * is passed via context variable {@code dynamicAgentConfig} by
     * {@code GroupConversationService}. If no group config is present (standalone
     * agent), a permissive default is used.
     *
     * @param memory
     *            the conversation memory to check for group context
     * @return the resolved DynamicAgentConfig — group-level if available,
     *         permissive default otherwise
     */
    private DynamicAgentConfig resolveDynamicAgentConfig(IConversationMemory memory) {
        // Check if GroupConversationService injected a DynamicAgentConfig via context
        var currentStep = memory.getCurrentStep();
        if (currentStep != null) {
            var contextData = currentStep.getLatestData("context:dynamicAgentConfig");
            if (contextData != null) {
                Object value = contextData.getResult();
                if (value instanceof ai.labs.eddi.engine.model.Context ctx && ctx.getValue() instanceof DynamicAgentConfig groupConfig) {
                    LOGGER.debugf("[DYNAMIC] Using group-level DynamicAgentConfig for agent='%s'", sanitize(memory.getAgentId()));
                    return groupConfig;
                }
            }
        }
        // Fallback: standalone agent — use permissive defaults
        return createDefaultDynamicConfig();
    }

    /**
     * Creates a default DynamicAgentConfig for agents without explicit group
     * config. Used when individual agents have dynamic agent tools in their
     * whitelist.
     */
    private DynamicAgentConfig createDefaultDynamicConfig() {
        var config = new DynamicAgentConfig();
        config.setEnabled(true);
        config.setAllowCreation(true);
        config.setAllowRecruitment(true);
        config.setAllowDelegation(true);
        return config;
    }

    // --- Httpcall auto-discovery from workflow ---

    /**
     * Result of httpcall tool discovery.
     */
    record HttpCallToolsResult(List<ToolSpecification> toolSpecs, Map<String, ToolExecutor> executors) {
    }

    /**
     * Discovers httpcall configurations from the workflow and creates
     * ToolSpecification + ToolExecutor for each ApiCall.
     * <p>
     * Traverses: memory → agentId/version → AgentConfiguration → workflows →
     * WorkflowConfiguration → filter httpcall steps → load ApiCallsConfiguration →
     * create tools from each ApiCall.
     */
    HttpCallToolsResult discoverHttpCallTools(IConversationMemory memory) {
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        Map<String, ToolExecutor> executors = new HashMap<>();

        try {
            LOGGER.infof("Discovering httpcall tools for agent: %s v%s", memory.getAgentId(), memory.getAgentVersion());

            var stepConfigs = WorkflowTraversal.discoverConfigs(memory, HTTPCALLS_TYPE, ApiCallsConfiguration.class, restAgentStore,
                    restWorkflowStore, resourceClientLibrary);

            for (var stepConfig : stepConfigs) {
                ApiCallsConfiguration httpCallsConfig = stepConfig.config();
                String targetServerUrl = httpCallsConfig.getTargetServerUrl();

                for (ApiCall apiCall : httpCallsConfig.getHttpCalls()) {
                    if (apiCall.getName() == null || apiCall.getName().isBlank()) {
                        continue;
                    }

                    ToolSpecification.Builder specBuilder = ToolSpecification.builder().name(apiCall.getName())
                            .description(apiCall.getDescription() != null ? apiCall.getDescription() : "Execute " + apiCall.getName());

                    if (apiCall.getParameters() != null && !apiCall.getParameters().isEmpty()) {
                        var schemaBuilder = dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder();
                        for (var param : apiCall.getParameters().entrySet()) {
                            schemaBuilder.addStringProperty(param.getKey(), param.getValue() != null ? param.getValue() : param.getKey());
                        }
                        schemaBuilder.required(new ArrayList<>(apiCall.getParameters().keySet()));
                        specBuilder.parameters(schemaBuilder.build());
                    }

                    toolSpecs.add(specBuilder.build());

                    executors.put(apiCall.getName(), (toolRequest, memoryId) -> {
                        try {
                            Map<String, Object> templateData = memoryItemConverter.convert(memory);

                            if (toolRequest.arguments() != null && !toolRequest.arguments().isBlank()) {
                                try {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> args = jsonSerialization.deserialize(toolRequest.arguments(), Map.class);
                                    safeTemplateMerge(templateData, args);
                                } catch (IOException e) {
                                    LOGGER.warn("Failed to parse tool arguments: " + toolRequest.arguments(), e);
                                }
                            }

                            Map<String, Object> result = apiCallExecutor.execute(apiCall, memory, templateData, targetServerUrl);

                            String serialized = jsonSerialization.serialize(result);
                            LOGGER.info("Httpcall tool '" + apiCall.getName() + "' result: keys=" + result.keySet() + " size=" + serialized.length());
                            return serialized;
                        } catch (Exception e) {
                            LOGGER.error("Error executing httpcall tool '" + apiCall.getName() + "'", e);
                            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                            String escaped = new String(JsonStringEncoder.getInstance().quoteAsString(errorMsg));
                            return "{\"error\": \"" + escaped + "\"}";
                        }
                    });
                }
            }

            LOGGER.info("Discovered " + toolSpecs.size() + " httpcall tools from workflow");
        } catch (Exception e) {
            LOGGER.warn("Failed to discover httpcall tools from workflow", e);
        }

        return new HttpCallToolsResult(toolSpecs, executors);
    }

    // --- McpCalls auto-discovery from workflow ---

    /**
     * Discovers mcpcalls configurations from the workflow and creates filtered
     * ToolSpecification + ToolExecutor pairs via McpToolProviderManager.
     * <p>
     * Traverses: memory → agentId/version → AgentConfiguration → workflows →
     * WorkflowConfiguration → filter mcpCalls steps → load McpCallsConfiguration →
     * apply whitelist/blacklist → return filtered tools.
     */
    McpToolProviderManager.McpToolsResult discoverMcpCallTools(IConversationMemory memory) {
        List<ToolSpecification> toolSpecs = new ArrayList<>();
        Map<String, ToolExecutor> executors = new HashMap<>();

        try {
            LOGGER.infof("Discovering mcpcalls tools for agent: %s v%s", memory.getAgentId(), memory.getAgentVersion());

            var stepConfigs = WorkflowTraversal.discoverConfigs(memory, MCPCALLS_TYPE, McpCallsConfiguration.class, restAgentStore, restWorkflowStore,
                    resourceClientLibrary);

            for (var stepConfig : stepConfigs) {
                McpCallsConfiguration mcpCallsConfig = stepConfig.config();

                // Build server config from McpCallsConfiguration
                McpServerConfig serverConfig = new McpServerConfig();
                serverConfig.setUrl(mcpCallsConfig.getMcpServerUrl());
                serverConfig.setName(mcpCallsConfig.getName());
                serverConfig.setTransport(mcpCallsConfig.getTransport());
                serverConfig.setApiKey(mcpCallsConfig.getApiKey());
                serverConfig.setTimeoutMs(mcpCallsConfig.getTimeoutMs());

                // Discover tools from this MCP server
                McpToolProviderManager.McpToolsResult result = mcpToolProviderManager.discoverTools(List.of(serverConfig));

                // Apply whitelist/blacklist filtering
                List<String> whitelist = mcpCallsConfig.getToolsWhitelist();
                List<String> blacklist = mcpCallsConfig.getToolsBlacklist();

                for (ToolSpecification spec : result.toolSpecs()) {
                    String name = spec.name();
                    if (whitelist != null && !whitelist.isEmpty() && !whitelist.contains(name))
                        continue;
                    if (blacklist != null && blacklist.contains(name))
                        continue;

                    toolSpecs.add(spec);
                    ToolExecutor executor = result.executors().get(name);
                    if (executor != null) {
                        executors.put(name, executor);
                    }
                }
            }

            LOGGER.info("Discovered " + toolSpecs.size() + " mcpcalls tools from workflow");
        } catch (Exception e) {
            LOGGER.warn("Failed to discover mcpcalls tools from workflow", e);
        }

        return new McpToolProviderManager.McpToolsResult(toolSpecs, executors);
    }

    // ─── Template data protection ───

    /**
     * Keys produced by {@link IMemoryItemConverter#convert} that carry
     * authenticated identity and session state. LLM-provided tool arguments must
     * never override these — a prompt-injection attack could otherwise manipulate
     * userId/agentId in HTTP call templates.
     */
    private static final Set<String> RESERVED_TEMPLATE_KEYS = Set.of(
            "context", "properties", "memory",
            "userInfo", "conversationInfo", "conversationLog");

    /**
     * Merge LLM tool arguments into template data, blocking any keys that collide
     * with internal pipeline data. Blocked keys are logged as warnings so config
     * authors can rename their parameters if needed.
     */
    private static void safeTemplateMerge(Map<String, Object> templateData, Map<String, Object> args) {
        for (var entry : args.entrySet()) {
            if (RESERVED_TEMPLATE_KEYS.contains(entry.getKey())) {
                LOGGER.warnf("Blocked LLM tool argument '%s' — collides with reserved template key. " +
                        "Rename the httpcall parameter to avoid this conflict.", entry.getKey());
                continue;
            }
            templateData.put(entry.getKey(), entry.getValue());
        }
    }
}
