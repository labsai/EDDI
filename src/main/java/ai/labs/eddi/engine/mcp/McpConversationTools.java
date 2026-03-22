package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.audit.rest.IRestAuditStore;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.model.*;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.engine.model.LogEntry;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.runtime.BoundedLogStore;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory;
import ai.labs.eddi.utils.RestUtilities;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.engine.mcp.McpToolUtils.*;

/**
 * MCP tools for conversing with EDDI agents.
 * Exposes Agent listing, conversation management, and messaging
 * as MCP-compliant tools via the Quarkus MCP Server extension.
 *
 * <p>
 * Phase 8a — Item 35: Agent Conversations MCP Server (5 SP)
 *
 * @author ginccc
 */
@ApplicationScoped
public class McpConversationTools {

    private static final Logger LOGGER = Logger.getLogger(McpConversationTools.class);
    private static final int CONVERSATION_TIMEOUT_SECONDS = 60;

    private final IConversationService conversationService;
    private final IRestAgentAdministration agentAdmin;
    private final IRestAgentStore agentStore;
    private final IRestInterfaceFactory restInterfaceFactory;
    private final IJsonSerialization jsonSerialization;
    private final BoundedLogStore boundedLogStore;
    private final IRestAuditStore auditStore;
    private final IRestAgentTriggerStore agentTriggerStore;
    private final IUserConversationStore userConversationStore;
    private final IRestAgentEngine restAgentEngine;

    @Inject
    public McpConversationTools(IConversationService conversationService,
            IRestAgentAdministration agentAdmin,
            IRestAgentStore agentStore,
            IRestInterfaceFactory restInterfaceFactory,
            IJsonSerialization jsonSerialization,
            BoundedLogStore boundedLogStore,
            IRestAuditStore auditStore,
            IRestAgentTriggerStore agentTriggerStore,
            IUserConversationStore userConversationStore,
            IRestAgentEngine restAgentEngine) {
        this.conversationService = conversationService;
        this.agentAdmin = agentAdmin;
        this.agentStore = agentStore;
        this.restInterfaceFactory = restInterfaceFactory;
        this.jsonSerialization = jsonSerialization;
        this.boundedLogStore = boundedLogStore;
        this.auditStore = auditStore;
        this.agentTriggerStore = agentTriggerStore;
        this.userConversationStore = userConversationStore;
        this.restAgentEngine = restAgentEngine;
    }

    @Tool(name = "list_agents", description = "List all deployed agents with their status, version, and name. " +
            "Returns a JSON array of Agent deployment statuses.")
    public String listAgents(
            @ToolArg(description = "Environment: 'production' (default), 'restricted', or 'test'") String environment) {
        try {
            var env = parseEnvironment(environment);
            List<AgentDeploymentStatus> statuses = agentAdmin.getDeploymentStatuses(env);
            return jsonSerialization.serialize(statuses);
        } catch (Exception e) {
            LOGGER.error("MCP list_agents failed", e);
            return errorJson("Failed to list agents: " + e.getMessage());
        }
    }

    @Tool(name = "list_agent_configs", description = "List all Agent configurations (including those not yet deployed). "
            +
            "Returns a JSON array of Agent descriptors with name, description, and IDs.")
    public String listAgentConfigs(
            @ToolArg(description = "Optional filter string to search Agent names") String filter,
            @ToolArg(description = "Maximum number of results (default 20)") Integer limit) {
        try {
            int limitInt = limit != null ? limit : 20;
            String filterStr = filter != null ? filter : "";
            List<DocumentDescriptor> descriptors = agentStore.readAgentDescriptors(filterStr, 0, limitInt);
            return jsonSerialization.serialize(descriptors);
        } catch (Exception e) {
            LOGGER.error("MCP list_agent_configs failed", e);
            return errorJson("Failed to list Agent configs: " + e.getMessage());
        }
    }

    @Tool(name = "create_conversation", description = "Start a new conversation with a deployed agent. " +
            "Returns the conversationId which you need for subsequent talk_to_agent calls. " +
            "Tip: Use chat_with_agent instead if you want to send a message immediately.")
    public String createConversation(
            @ToolArg(description = "Agent ID (required)") String agentId,
            @ToolArg(description = "Environment: 'production' (default), 'restricted', or 'test'") String environment) {
        if (agentId == null || agentId.isBlank())
            return errorJson("agentId is required");
        try {
            var env = parseEnvironment(environment);
            ConversationResult result = conversationService.startConversation(
                    env, agentId, null, Collections.emptyMap());
            return jsonSerialization.serialize(Map.of(
                    "conversationId", result.conversationId(),
                    "conversationUri", result.conversationUri().toString(),
                    "agentId", agentId,
                    "environment", env.name()));
        } catch (Exception e) {
            LOGGER.error("MCP create_conversation failed for Agent " + agentId, e);
            return errorJson("Failed to create conversation: " + e.getMessage());
        }
    }

    @Blocking
    @Tool(name = "talk_to_agent", description = "Send a message to a Agent in an existing conversation and get the agent's response. "
            +
            "You must first call create_conversation to get a conversationId, " +
            "or use chat_with_agent for a single-call alternative.")
    public String talkToAgent(
            @ToolArg(description = "Agent ID (required)") String agentId,
            @ToolArg(description = "Conversation ID from create_conversation (required)") String conversationId,
            @ToolArg(description = "The user message to send to the Agent (required)") String message,
            @ToolArg(description = "Environment: 'production' (default), 'restricted', or 'test'") String environment) {
        if (agentId == null || agentId.isBlank())
            return errorJson("agentId is required");
        if (conversationId == null || conversationId.isBlank())
            return errorJson("conversationId is required");
        if (message == null || message.isBlank())
            return errorJson("message is required");
        try {
            var env = parseEnvironment(environment);
            var snapshot = sendMessageAndWait(env, agentId, conversationId, message);
            var result = buildConversationResponse(snapshot, null);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP talk_to_agent failed for Agent " + agentId + " conversation " + conversationId, e);
            return errorJson("Failed to talk to agent: " + e.getMessage());
        }
    }

    @Blocking
    @Tool(name = "chat_with_agent", description = "Send a message to an agent, automatically creating a new conversation if needed. "
            +
            "This is the simplest way to interact with a Agent — combines create_conversation + " +
            "talk_to_agent into a single call. Returns the Agent response and conversationId " +
            "for follow-up messages.")
    public String chatWithAgent(
            @ToolArg(description = "Agent ID (required)") String agentId,
            @ToolArg(description = "The user message to send to the Agent (required)") String message,
            @ToolArg(description = "Conversation ID to continue (optional — creates new if omitted)") String conversationId,
            @ToolArg(description = "Environment: 'production' (default), 'restricted', or 'test'") String environment) {
        if (agentId == null || agentId.isBlank())
            return errorJson("agentId is required");
        if (message == null || message.isBlank())
            return errorJson("message is required");
        try {
            var env = parseEnvironment(environment);

            // Step 1: Create conversation if not provided
            String convId = conversationId;
            if (convId == null || convId.isBlank()) {
                ConversationResult convResult = conversationService.startConversation(
                        env, agentId, null, Collections.emptyMap());
                convId = convResult.conversationId();
            }

            // Step 2: Send the message
            var snapshot = sendMessageAndWait(env, agentId, convId, message);

            // Step 3: Return AI-agent-friendly summary + full snapshot
            var result = buildConversationResponse(snapshot, convId);
            result.putFirst("environment", env.name());
            result.putFirst("agentId", agentId);
            result.putFirst("conversationId", convId);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP chat_with_agent failed for Agent " + agentId, e);
            return errorJson("Failed to chat with agent: " + e.getMessage());
        }
    }

    @Tool(name = "read_conversation", description = "Read conversation history and memory. " +
            "Returns the conversation memory snapshot. Use returningFields to limit " +
            "output size, or use read_conversation_log for a human-readable summary.")
    public String readConversation(
            @ToolArg(description = "Agent ID (required)") String agentId,
            @ToolArg(description = "Conversation ID (required)") String conversationId,
            @ToolArg(description = "Environment: 'production' (default), 'restricted', or 'test'") String environment,
            @ToolArg(description = "Return only the current (latest) step? (default: true)") Boolean currentStepOnly,
            @ToolArg(description = "Return detailed internal data? (default: false)") Boolean returnDetailed,
            @ToolArg(description = "Comma-separated list of fields to return (e.g. 'input,output,actions'). " +
                    "Empty = all fields.") String returningFields) {
        try {
            var env = parseEnvironment(environment);
            boolean stepOnly = currentStepOnly != null ? currentStepOnly : true;
            boolean detailed = returnDetailed != null ? returnDetailed : false;

            List<String> fields = Collections.emptyList();
            if (returningFields != null && !returningFields.isBlank()) {
                fields = List.of(returningFields.split(","));
            }

            var snapshot = conversationService.readConversation(
                    env, agentId, conversationId, detailed, stepOnly, fields);
            return jsonSerialization.serialize(snapshot);
        } catch (Exception e) {
            LOGGER.error("MCP read_conversation failed for conversation " + conversationId, e);
            return errorJson("Failed to read conversation: " + e.getMessage());
        }
    }

    @Tool(name = "read_conversation_log", description = "Read conversation log as formatted text. " +
            "Returns the conversation history in a human-readable format. " +
            "This is the preferred tool for reviewing what was said in a conversation.")
    public String readConversationLog(
            @ToolArg(description = "Conversation ID (required)") String conversationId,
            @ToolArg(description = "Number of recent steps to include (default: all)") Integer logSize) {
        try {
            var result = conversationService.readConversationLog(
                    conversationId, "text", logSize != null && logSize > 0 ? logSize : null);
            return result.content().toString();
        } catch (Exception e) {
            LOGGER.error("MCP read_conversation_log failed for conversation " + conversationId, e);
            return errorJson("Failed to read conversation log: " + e.getMessage());
        }
    }

    @Tool(name = "list_conversations", description = "List conversations for a specific agent. " +
            "Returns conversation descriptors with IDs, creation time, and state. " +
            "Useful for finding conversation IDs without knowing them beforehand.")
    public String listConversations(
            @ToolArg(description = "Agent ID (required)") String agentId,
            @ToolArg(description = "Agent version (default: latest)") Integer agentVersion,
            @ToolArg(description = "Filter by state: 'READY', 'IN_PROGRESS', 'ENDED', 'ERROR' (default: all)") String conversationState,
            @ToolArg(description = "Maximum number of results (default: 20, max: 100)") Integer limit) {
        if (agentId == null || agentId.isBlank())
            return errorJson("agentId is required");
        try {
            int limitInt = Math.min(limit != null ? limit : 20, 100);
            int ver = agentVersion != null ? agentVersion : 0;

            // Parse ConversationState enum if provided
            ConversationState state = null;
            if (conversationState != null && !conversationState.isBlank()) {
                try {
                    state = ConversationState.valueOf(conversationState.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    return errorJson("Invalid conversationState: " + conversationState +
                            ". Valid values: READY, IN_PROGRESS, ENDED, ERROR");
                }
            }

            IRestConversationStore convStore;
            try {
                convStore = restInterfaceFactory.get(IRestConversationStore.class);
            } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
                return errorJson("Failed to get conversation store: " + e.getMessage());
            }

            List<ConversationDescriptor> descriptors = convStore.readConversationDescriptors(
                    0, limitInt, null, null, agentId, ver == 0 ? null : ver, state, null);

            var result = new LinkedHashMap<String, Object>();
            result.put("agentId", agentId);
            result.put("count", descriptors.size());
            result.put("conversations", descriptors);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP list_conversations failed for Agent " + agentId, e);
            return errorJson("Failed to list conversations: " + e.getMessage());
        }
    }

    @Tool(name = "get_agent", description = "Get an agent's full configuration including its packages, name, and description. "
            +
            "Returns the AgentConfiguration JSON with all package references.")
    public String getAgent(
            @ToolArg(description = "Agent ID (required)") String agentId,
            @ToolArg(description = "Version number (default: latest)") Integer version) {
        if (agentId == null || agentId.isBlank())
            return errorJson("agentId is required");
        try {
            int ver = version != null ? version : 1;
            AgentConfiguration config = agentStore.readAgent(agentId, ver);
            if (config == null) {
                return errorJson("Agent not found: " + agentId + " version " + ver);
            }

            // Read descriptor for name/description (direct by ID, not N+1)
            var result = new LinkedHashMap<String, Object>();
            result.put("agentId", agentId);
            result.put("version", ver);
            try {
                DocumentDescriptor descriptor = McpToolUtils.getRestStore(
                        restInterfaceFactory, IRestDocumentDescriptorStore.class)
                        .readDescriptor(agentId, ver);
                if (descriptor != null) {
                    result.put("name", descriptor.getName());
                    result.put("description", descriptor.getDescription());
                }
            } catch (Exception e) {
                LOGGER.debug("Could not read Agent descriptor", e);
            }
            result.put("configuration", config);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP get_agent failed for Agent " + agentId, e);
            return errorJson("Failed to get agent: " + e.getMessage());
        }
    }

    // ==================== Phase 8a.2: Diagnostic Tools ====================

    @Tool(name = "read_agent_logs", description = "Read recent server-side logs for a Agent or conversation. " +
            "Returns workflow execution logs, LLM provider errors, timeouts, and internal diagnostics " +
            "that are NOT visible in conversation memory. Essential for debugging 'why did the Agent fail?' " +
            "Filter by agentId, conversationId, and/or log level.")
    public String readAgentLogs(
            @ToolArg(description = "Filter by Agent ID (optional)") String agentId,
            @ToolArg(description = "Filter by conversation ID (optional)") String conversationId,
            @ToolArg(description = "Filter by log level: 'ERROR', 'WARN', 'INFO', 'DEBUG' (optional)") String level,
            @ToolArg(description = "Maximum number of log entries to return (default: 50)") Integer limit) {
        try {
            int limitInt = limit != null ? limit : 50;
            String agentFilter = (agentId != null && !agentId.isBlank()) ? agentId : null;
            String convFilter = (conversationId != null && !conversationId.isBlank()) ? conversationId : null;
            String levelFilter = (level != null && !level.isBlank()) ? level.toUpperCase() : null;

            List<LogEntry> entries = boundedLogStore.getEntries(agentFilter, convFilter, levelFilter, limitInt);

            var result = new LinkedHashMap<String, Object>();
            result.put("count", entries.size());
            result.put("limit", limitInt);
            if (agentFilter != null)
                result.put("agentId", agentFilter);
            if (convFilter != null)
                result.put("conversationId", convFilter);
            if (levelFilter != null)
                result.put("level", levelFilter);
            result.put("entries", entries);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP read_agent_logs failed", e);
            return errorJson("Failed to read Agent logs: " + e.getMessage());
        }
    }

    @Tool(name = "read_audit_trail", description = "Read the audit trail for a conversation. " +
            "Returns per-task execution records including: taskId, taskType, input/output data, " +
            "LLM details (model, prompt, tokens, cost), tool calls, actions emitted, and timing. " +
            "This shows EXACTLY what happened at each workflow step — essential for optimizing Agent behavior.")
    public String readAuditTrail(
            @ToolArg(description = "Conversation ID (required)") String conversationId,
            @ToolArg(description = "Maximum number of entries to return (default: 20)") Integer limit) {
        if (conversationId == null || conversationId.isBlank())
            return errorJson("conversationId is required");
        try {
            int limitInt = limit != null ? limit : 20;
            List<AuditEntry> entries = auditStore.getAuditTrail(conversationId, 0, limitInt);

            var result = new LinkedHashMap<String, Object>();
            result.put("conversationId", conversationId);
            result.put("count", entries.size());
            result.put("entries", entries);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP read_audit_trail failed for conversation " + conversationId, e);
            return errorJson("Failed to read audit trail: " + e.getMessage());
        }
    }

    @Tool(name = "discover_agents", description = "Discover deployed agents with their capabilities. " +
            "Returns an enriched list of deployed agents, cross-referenced with intent mappings " +
            "from Agent triggers. Each Agent entry includes: agentId, name, description, version, status, " +
            "and any intents it serves. This is the best way to find agents by purpose.")
    public String discoverAgents(
            @ToolArg(description = "Optional filter string to search Agent names") String filter,
            @ToolArg(description = "Environment: 'production' (default), 'restricted', or 'test'") String environment) {
        try {
            var env = parseEnvironment(environment);
            List<AgentDeploymentStatus> statuses = agentAdmin.getDeploymentStatuses(env);

            // Build agentId -> intents mapping from triggers
            Map<String, List<String>> agentIntents = new LinkedHashMap<>();
            try {
                List<AgentTriggerConfiguration> triggers = agentTriggerStore.readAllAgentTriggers();
                for (var trigger : triggers) {
                    for (var deployment : trigger.getAgentDeployments()) {
                        agentIntents.computeIfAbsent(deployment.getAgentId(), k -> new ArrayList<>())
                                .add(trigger.getIntent());
                    }
                }
            } catch (Exception e) {
                LOGGER.warnv("Could not read Agent triggers for discovery: {0}", e.getMessage());
            }

            // Build enriched results
            var agents = new ArrayList<Map<String, Object>>();
            for (var status : statuses) {
                if (status.getDescriptor() != null && status.getDescriptor().isDeleted())
                    continue;

                String name = status.getDescriptor() != null ? status.getDescriptor().getName() : "";
                String desc = status.getDescriptor() != null ? status.getDescriptor().getDescription() : "";

                // Apply name filter if provided
                if (filter != null && !filter.isBlank()) {
                    boolean matches = (name != null && name.toLowerCase().contains(filter.toLowerCase())) ||
                            (desc != null && desc.toLowerCase().contains(filter.toLowerCase()));
                    if (!matches)
                        continue;
                }

                var agentMap = new LinkedHashMap<String, Object>();
                agentMap.put("agentId", status.getAgentId());
                agentMap.put("name", name);
                agentMap.put("description", desc);
                agentMap.put("version", status.getAgentVersion());
                agentMap.put("status", status.getStatus().name());
                agentMap.put("environment", status.getEnvironment().name());
                List<String> intents = agentIntents.get(status.getAgentId());
                if (intents != null && !intents.isEmpty()) {
                    agentMap.put("intents", intents);
                }
                agents.add(agentMap);
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("count", agents.size());
            result.put("agents", agents);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP discover_agents failed", e);
            return errorJson("Failed to discover agents: " + e.getMessage());
        }
    }

    @Tool(name = "chat_managed", description = "Send a message to a Agent using intent-based managed conversations. " +
            "Unlike chat_with_agent (which requires a agentId and creates multiple conversations), this " +
            "tool uses an 'intent' to find the right Agent and maintains exactly ONE conversation " +
            "per intent+userId — like a single chat window. The conversation is auto-created on " +
            "first message and reused on subsequent calls. Requires a Agent trigger to be configured " +
            "for the intent (see list_agent_triggers / create_agent_trigger).")
    public String chatManaged(
            @ToolArg(description = "Intent that maps to a Agent trigger (required). E.g. 'customer_support'") String intent,
            @ToolArg(description = "User ID for conversation management (required)") String userId,
            @ToolArg(description = "The user message to send (required)") String message,
            @ToolArg(description = "Environment: 'production' (default), 'restricted', or 'test'") String environment) {
        if (intent == null || intent.isBlank())
            return errorJson("intent is required");
        if (userId == null || userId.isBlank())
            return errorJson("userId is required");
        if (message == null || message.isBlank())
            return errorJson("message is required");

        try {
            var env = parseEnvironment(environment);

            // Step 1: Get or create the user conversation for this intent
            var userConversation = getOrCreateManagedConversation(intent, userId, env);

            // Step 2: Send the message using the existing sendMessageAndWait helper
            var snapshot = sendMessageAndWait(
                    userConversation.getEnvironment(),
                    userConversation.getAgentId(),
                    userConversation.getConversationId(),
                    message);

            // Step 3: Build response
            var result = buildConversationResponse(snapshot, userConversation.getConversationId());
            result.putFirst("intent", intent);
            result.putFirst("userId", userId);
            result.putFirst("agentId", userConversation.getAgentId());
            result.putFirst("conversationId", userConversation.getConversationId());
            result.putFirst("environment", userConversation.getEnvironment().name());
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.errorv("MCP chat_managed failed for intent={0}, userId={1}: {2}", intent, userId, e.getMessage());
            return errorJson("Failed to chat via managed agent: " + e.getMessage());
        }
    }

    /**
     * Get an existing user conversation for the intent+userId, or create a new one.
     * Mirrors the logic from {@code RestAgentManagement.initUserConversation}.
     */
    private UserConversation getOrCreateManagedConversation(
            String intent, String userId, Deployment.Environment env) throws Exception {

        // Try to read existing conversation
        UserConversation existing = null;
        try {
            existing = userConversationStore.readUserConversation(intent, userId);
        } catch (IResourceStore.ResourceStoreException ignored) {
            // Not found — will create
        }

        if (existing != null) {
            // Check if conversation has ended
            ConversationState state = restAgentEngine.getConversationState(
                    existing.getEnvironment(), existing.getConversationId());
            if (!ConversationState.ENDED.equals(state)) {
                return existing;
            }
            // Ended — delete and create fresh
            userConversationStore.deleteUserConversation(intent, userId);
        }

        // Create a new conversation from the Agent trigger
        AgentTriggerConfiguration trigger = agentTriggerStore.readAgentTrigger(intent);
        if (trigger == null || trigger.getAgentDeployments().isEmpty()) {
            throw new RuntimeException("No Agent trigger configured for intent: " + intent);
        }

        // Pick first deployment
        AgentDeployment deployment = trigger.getAgentDeployments().getFirst();
        String agentId = deployment.getAgentId();
        var usedEnv = deployment.getEnvironment() != null ? deployment.getEnvironment() : env;

        // Start a new conversation
        var initialContext = new HashMap<>(deployment.getInitialContext());
        jakarta.ws.rs.core.Response agentResponse = restAgentEngine.startConversationWithContext(
                usedEnv, agentId, userId, initialContext);

        if (agentResponse.getStatus() != 201) {
            throw new RuntimeException("Failed to create conversation for intent=" + intent +
                    ", agentId=" + agentId + ", status=" + agentResponse.getStatus());
        }

        var locationUri = URI.create(agentResponse.getHeaders().get("location").getFirst().toString());
        var resourceId = RestUtilities.extractResourceId(locationUri);
        String conversationId = resourceId.getId();

        // Store the user conversation mapping
        var userConversation = new UserConversation(intent, userId, usedEnv, agentId, conversationId);
        userConversationStore.createUserConversation(userConversation);

        return userConversation;
    }

    /**
     * Send a message to a Agent synchronously and wait for the response.
     * Bridges the async callback pattern to a blocking call.
     */
    private SimpleConversationMemorySnapshot sendMessageAndWait(
            Deployment.Environment env, String agentId, String conversationId, String message)
            throws Exception {

        var inputData = new InputData();
        inputData.setInput(message);
        inputData.setContext(Map.of("mcp", new Context(Context.ContextType.string, "true")));

        var responseFuture = new CompletableFuture<SimpleConversationMemorySnapshot>();

        conversationService.say(env, agentId, conversationId,
                false, true, Collections.emptyList(),
                inputData, false,
                snapshot -> {
                    if (snapshot != null) {
                        responseFuture.complete(snapshot);
                    } else {
                        responseFuture.completeExceptionally(
                                new RuntimeException("Agent returned null response"));
                    }
                });

        return responseFuture.get(CONVERSATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Build an AI-agent-friendly response from a conversation snapshot.
     * Extracts top-level fields: agentResponse (text), quickReplies, actions,
     * conversationState — so AI agents don't need to dig into the raw snapshot.
     */
    private LinkedHashMap<String, Object> buildConversationResponse(
            SimpleConversationMemorySnapshot snapshot, String conversationId) {

        var result = new LinkedHashMap<String, Object>();

        // Extract from the LAST conversationOutput (the current step's output)
        var outputs = snapshot.getConversationOutputs();
        if (outputs != null && !outputs.isEmpty()) {
            var lastOutput = outputs.get(outputs.size() - 1);

            // Agent response text — extract text strings from output items
            var outputItems = lastOutput.get("output");
            if (outputItems instanceof List<?> items) {
                var texts = new java.util.ArrayList<String>();
                for (var item : items) {
                    if (item instanceof Map<?, ?> map && map.containsKey("text")) {
                        texts.add(String.valueOf(map.get("text")));
                    }
                }
                if (!texts.isEmpty()) {
                    result.put("agentResponse", String.join(" ", texts));
                    if (texts.size() > 1) {
                        result.put("agentResponseParts", texts);
                    }
                }
            }

            // QuickReplies — extract value strings for easy AI consumption
            var quickReplies = lastOutput.get("quickReplies");
            if (quickReplies instanceof List<?> qrList && !qrList.isEmpty()) {
                var qrValues = new java.util.ArrayList<String>();
                for (var qr : qrList) {
                    if (qr instanceof Map<?, ?> map && map.containsKey("value")) {
                        qrValues.add(String.valueOf(map.get("value")));
                    }
                }
                if (!qrValues.isEmpty()) {
                    result.put("quickReplies", qrValues);
                }
            }

            // Actions
            var actions = lastOutput.get("actions");
            if (actions != null) {
                result.put("actions", actions);
            }
        }

        // Conversation state
        if (snapshot.getConversationState() != null) {
            result.put("conversationState", snapshot.getConversationState().name());
        }

        // Full snapshot for detailed access
        result.put("response", snapshot);

        return result;
    }
}
