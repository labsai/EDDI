package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.botmanagement.IRestBotTriggerStore;
import ai.labs.eddi.configs.botmanagement.IUserConversationStore;
import ai.labs.eddi.configs.bots.IRestBotStore;
import ai.labs.eddi.configs.bots.model.BotConfiguration;
import ai.labs.eddi.configs.documentdescriptor.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.IConversationService;
import ai.labs.eddi.engine.IConversationService.ConversationResult;
import ai.labs.eddi.engine.IRestBotAdministration;
import ai.labs.eddi.engine.IRestBotEngine;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.audit.rest.IRestAuditStore;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.model.*;
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
 * MCP tools for conversing with EDDI bots.
 * Exposes bot listing, conversation management, and messaging
 * as MCP-compliant tools via the Quarkus MCP Server extension.
 *
 * <p>
 * Phase 8a — Item 35: Bot Conversations MCP Server (5 SP)
 *
 * @author ginccc
 */
@ApplicationScoped
public class McpConversationTools {

    private static final Logger LOGGER = Logger.getLogger(McpConversationTools.class);
    private static final int CONVERSATION_TIMEOUT_SECONDS = 60;

    private final IConversationService conversationService;
    private final IRestBotAdministration botAdmin;
    private final IRestBotStore botStore;
    private final IRestInterfaceFactory restInterfaceFactory;
    private final IJsonSerialization jsonSerialization;
    private final BoundedLogStore boundedLogStore;
    private final IRestAuditStore auditStore;
    private final IRestBotTriggerStore botTriggerStore;
    private final IUserConversationStore userConversationStore;
    private final IRestBotEngine restBotEngine;

    @Inject
    public McpConversationTools(IConversationService conversationService,
            IRestBotAdministration botAdmin,
            IRestBotStore botStore,
            IRestInterfaceFactory restInterfaceFactory,
            IJsonSerialization jsonSerialization,
            BoundedLogStore boundedLogStore,
            IRestAuditStore auditStore,
            IRestBotTriggerStore botTriggerStore,
            IUserConversationStore userConversationStore,
            IRestBotEngine restBotEngine) {
        this.conversationService = conversationService;
        this.botAdmin = botAdmin;
        this.botStore = botStore;
        this.restInterfaceFactory = restInterfaceFactory;
        this.jsonSerialization = jsonSerialization;
        this.boundedLogStore = boundedLogStore;
        this.auditStore = auditStore;
        this.botTriggerStore = botTriggerStore;
        this.userConversationStore = userConversationStore;
        this.restBotEngine = restBotEngine;
    }

    @Tool(name = "list_bots", description = "List all deployed bots with their status, version, and name. " +
            "Returns a JSON array of bot deployment statuses.")
    public String listBots(
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'") String environment) {
        try {
            var env = parseEnvironment(environment);
            List<BotDeploymentStatus> statuses = botAdmin.getDeploymentStatuses(env);
            return jsonSerialization.serialize(statuses);
        } catch (Exception e) {
            LOGGER.error("MCP list_bots failed", e);
            return errorJson("Failed to list bots: " + e.getMessage());
        }
    }

    @Tool(name = "list_bot_configs", description = "List all bot configurations (including those not yet deployed). " +
            "Returns a JSON array of bot descriptors with name, description, and IDs.")
    public String listBotConfigs(
            @ToolArg(description = "Optional filter string to search bot names") String filter,
            @ToolArg(description = "Maximum number of results (default 20)") Integer limit) {
        try {
            int limitInt = limit != null ? limit : 20;
            String filterStr = filter != null ? filter : "";
            List<DocumentDescriptor> descriptors = botStore.readBotDescriptors(filterStr, 0, limitInt);
            return jsonSerialization.serialize(descriptors);
        } catch (Exception e) {
            LOGGER.error("MCP list_bot_configs failed", e);
            return errorJson("Failed to list bot configs: " + e.getMessage());
        }
    }

    @Tool(name = "create_conversation", description = "Start a new conversation with a deployed bot. " +
            "Returns the conversationId which you need for subsequent talk_to_bot calls. " +
            "Tip: Use chat_with_bot instead if you want to send a message immediately.")
    public String createConversation(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'") String environment) {
        if (botId == null || botId.isBlank()) return errorJson("botId is required");
        try {
            var env = parseEnvironment(environment);
            ConversationResult result = conversationService.startConversation(
                    env, botId, null, Collections.emptyMap());
            return jsonSerialization.serialize(Map.of(
                    "conversationId", result.conversationId(),
                    "conversationUri", result.conversationUri().toString(),
                    "botId", botId,
                    "environment", env.name()));
        } catch (Exception e) {
            LOGGER.error("MCP create_conversation failed for bot " + botId, e);
            return errorJson("Failed to create conversation: " + e.getMessage());
        }
    }

    @Blocking
    @Tool(name = "talk_to_bot", description = "Send a message to a bot in an existing conversation and get the bot's response. "
            +
            "You must first call create_conversation to get a conversationId, " +
            "or use chat_with_bot for a single-call alternative.")
    public String talkToBot(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Conversation ID from create_conversation (required)") String conversationId,
            @ToolArg(description = "The user message to send to the bot (required)") String message,
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'") String environment) {
        if (botId == null || botId.isBlank()) return errorJson("botId is required");
        if (conversationId == null || conversationId.isBlank()) return errorJson("conversationId is required");
        if (message == null || message.isBlank()) return errorJson("message is required");
        try {
            var env = parseEnvironment(environment);
            var snapshot = sendMessageAndWait(env, botId, conversationId, message);
            var result = buildConversationResponse(snapshot, null);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP talk_to_bot failed for bot " + botId + " conversation " + conversationId, e);
            return errorJson("Failed to talk to bot: " + e.getMessage());
        }
    }

    @Blocking
    @Tool(name = "chat_with_bot", description = "Send a message to a bot, automatically creating a new conversation if needed. "
            +
            "This is the simplest way to interact with a bot — combines create_conversation + " +
            "talk_to_bot into a single call. Returns the bot response and conversationId " +
            "for follow-up messages.")
    public String chatWithBot(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "The user message to send to the bot (required)") String message,
            @ToolArg(description = "Conversation ID to continue (optional — creates new if omitted)") String conversationId,
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'") String environment) {
        if (botId == null || botId.isBlank()) return errorJson("botId is required");
        if (message == null || message.isBlank()) return errorJson("message is required");
        try {
            var env = parseEnvironment(environment);

            // Step 1: Create conversation if not provided
            String convId = conversationId;
            if (convId == null || convId.isBlank()) {
                ConversationResult convResult = conversationService.startConversation(
                        env, botId, null, Collections.emptyMap());
                convId = convResult.conversationId();
            }

            // Step 2: Send the message
            var snapshot = sendMessageAndWait(env, botId, convId, message);

            // Step 3: Return AI-agent-friendly summary + full snapshot
            var result = buildConversationResponse(snapshot, convId);
            result.putFirst("environment", env.name());
            result.putFirst("botId", botId);
            result.putFirst("conversationId", convId);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP chat_with_bot failed for bot " + botId, e);
            return errorJson("Failed to chat with bot: " + e.getMessage());
        }
    }

    @Tool(name = "read_conversation", description = "Read conversation history and memory. " +
            "Returns the conversation memory snapshot. Use returningFields to limit " +
            "output size, or use read_conversation_log for a human-readable summary.")
    public String readConversation(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Conversation ID (required)") String conversationId,
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'") String environment,
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
                    env, botId, conversationId, detailed, stepOnly, fields);
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

    @Tool(name = "list_conversations", description = "List conversations for a specific bot. " +
            "Returns conversation descriptors with IDs, creation time, and state. " +
            "Useful for finding conversation IDs without knowing them beforehand.")
    public String listConversations(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Bot version (default: latest)") Integer botVersion,
            @ToolArg(description = "Filter by state: 'READY', 'IN_PROGRESS', 'ENDED', 'ERROR' (default: all)") String conversationState,
            @ToolArg(description = "Maximum number of results (default: 20, max: 100)") Integer limit) {
        if (botId == null || botId.isBlank()) return errorJson("botId is required");
        try {
            int limitInt = Math.min(limit != null ? limit : 20, 100);
            int ver = botVersion != null ? botVersion : 0;

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
                    0, limitInt, null, null, botId, ver == 0 ? null : ver, state, null);

            var result = new LinkedHashMap<String, Object>();
            result.put("botId", botId);
            result.put("count", descriptors.size());
            result.put("conversations", descriptors);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP list_conversations failed for bot " + botId, e);
            return errorJson("Failed to list conversations: " + e.getMessage());
        }
    }

    @Tool(name = "get_bot", description = "Get a bot's full configuration including its packages, name, and description. " +
            "Returns the BotConfiguration JSON with all package references.")
    public String getBot(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Version number (default: latest)") Integer version) {
        if (botId == null || botId.isBlank()) return errorJson("botId is required");
        try {
            int ver = version != null ? version : 1;
            BotConfiguration config = botStore.readBot(botId, ver);
            if (config == null) {
                return errorJson("Bot not found: " + botId + " version " + ver);
            }

            // Read descriptor for name/description (direct by ID, not N+1)
            var result = new LinkedHashMap<String, Object>();
            result.put("botId", botId);
            result.put("version", ver);
            try {
                DocumentDescriptor descriptor = McpToolUtils.getRestStore(
                        restInterfaceFactory, IRestDocumentDescriptorStore.class)
                        .readDescriptor(botId, ver);
                if (descriptor != null) {
                    result.put("name", descriptor.getName());
                    result.put("description", descriptor.getDescription());
                }
            } catch (Exception e) {
                LOGGER.debug("Could not read bot descriptor", e);
            }
            result.put("configuration", config);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP get_bot failed for bot " + botId, e);
            return errorJson("Failed to get bot: " + e.getMessage());
        }
    }

    // ==================== Phase 8a.2: Diagnostic Tools ====================

    @Tool(name = "read_bot_logs", description = "Read recent server-side logs for a bot or conversation. " +
            "Returns pipeline execution logs, LLM provider errors, timeouts, and internal diagnostics " +
            "that are NOT visible in conversation memory. Essential for debugging 'why did the bot fail?' " +
            "Filter by botId, conversationId, and/or log level.")
    public String readBotLogs(
            @ToolArg(description = "Filter by bot ID (optional)") String botId,
            @ToolArg(description = "Filter by conversation ID (optional)") String conversationId,
            @ToolArg(description = "Filter by log level: 'ERROR', 'WARN', 'INFO', 'DEBUG' (optional)") String level,
            @ToolArg(description = "Maximum number of log entries to return (default: 50)") Integer limit) {
        try {
            int limitInt = limit != null ? limit : 50;
            String botFilter = (botId != null && !botId.isBlank()) ? botId : null;
            String convFilter = (conversationId != null && !conversationId.isBlank()) ? conversationId : null;
            String levelFilter = (level != null && !level.isBlank()) ? level.toUpperCase() : null;

            List<LogEntry> entries = boundedLogStore.getEntries(botFilter, convFilter, levelFilter, limitInt);

            var result = new LinkedHashMap<String, Object>();
            result.put("count", entries.size());
            result.put("limit", limitInt);
            if (botFilter != null) result.put("botId", botFilter);
            if (convFilter != null) result.put("conversationId", convFilter);
            if (levelFilter != null) result.put("level", levelFilter);
            result.put("entries", entries);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP read_bot_logs failed", e);
            return errorJson("Failed to read bot logs: " + e.getMessage());
        }
    }

    @Tool(name = "read_audit_trail", description = "Read the audit trail for a conversation. " +
            "Returns per-task execution records including: taskId, taskType, input/output data, " +
            "LLM details (model, prompt, tokens, cost), tool calls, actions emitted, and timing. " +
            "This shows EXACTLY what happened at each pipeline step — essential for optimizing bot behavior.")
    public String readAuditTrail(
            @ToolArg(description = "Conversation ID (required)") String conversationId,
            @ToolArg(description = "Maximum number of entries to return (default: 20)") Integer limit) {
        if (conversationId == null || conversationId.isBlank()) return errorJson("conversationId is required");
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

    @Tool(name = "discover_bots", description = "Discover deployed bots with their capabilities. " +
            "Returns an enriched list of deployed bots, cross-referenced with intent mappings " +
            "from bot triggers. Each bot entry includes: botId, name, description, version, status, " +
            "and any intents it serves. This is the best way to find bots by purpose.")
    public String discoverBots(
            @ToolArg(description = "Optional filter string to search bot names") String filter,
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'") String environment) {
        try {
            var env = parseEnvironment(environment);
            List<BotDeploymentStatus> statuses = botAdmin.getDeploymentStatuses(env);

            // Build botId -> intents mapping from triggers
            Map<String, List<String>> botIntents = new LinkedHashMap<>();
            try {
                List<BotTriggerConfiguration> triggers = botTriggerStore.readAllBotTriggers();
                for (var trigger : triggers) {
                    for (var deployment : trigger.getBotDeployments()) {
                        botIntents.computeIfAbsent(deployment.getBotId(), k -> new ArrayList<>())
                                .add(trigger.getIntent());
                    }
                }
            } catch (Exception e) {
                LOGGER.warnv("Could not read bot triggers for discovery: {0}", e.getMessage());
            }

            // Build enriched results
            var bots = new ArrayList<Map<String, Object>>();
            for (var status : statuses) {
                if (status.getDescriptor() != null && status.getDescriptor().isDeleted()) continue;

                String name = status.getDescriptor() != null ? status.getDescriptor().getName() : "";
                String desc = status.getDescriptor() != null ? status.getDescriptor().getDescription() : "";

                // Apply name filter if provided
                if (filter != null && !filter.isBlank()) {
                    boolean matches = (name != null && name.toLowerCase().contains(filter.toLowerCase())) ||
                            (desc != null && desc.toLowerCase().contains(filter.toLowerCase()));
                    if (!matches) continue;
                }

                var bot = new LinkedHashMap<String, Object>();
                bot.put("botId", status.getBotId());
                bot.put("name", name);
                bot.put("description", desc);
                bot.put("version", status.getBotVersion());
                bot.put("status", status.getStatus().name());
                bot.put("environment", status.getEnvironment().name());
                List<String> intents = botIntents.get(status.getBotId());
                if (intents != null && !intents.isEmpty()) {
                    bot.put("intents", intents);
                }
                bots.add(bot);
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("count", bots.size());
            result.put("bots", bots);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP discover_bots failed", e);
            return errorJson("Failed to discover bots: " + e.getMessage());
        }
    }

    @Tool(name = "chat_managed", description = "Send a message to a bot using intent-based managed conversations. " +
            "Unlike chat_with_bot (which requires a botId and creates multiple conversations), this " +
            "tool uses an 'intent' to find the right bot and maintains exactly ONE conversation " +
            "per intent+userId — like a single chat window. The conversation is auto-created on " +
            "first message and reused on subsequent calls. Requires a bot trigger to be configured " +
            "for the intent (see list_bot_triggers / create_bot_trigger).")
    public String chatManaged(
            @ToolArg(description = "Intent that maps to a bot trigger (required). E.g. 'customer_support'") String intent,
            @ToolArg(description = "User ID for conversation management (required)") String userId,
            @ToolArg(description = "The user message to send (required)") String message,
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'") String environment) {
        if (intent == null || intent.isBlank()) return errorJson("intent is required");
        if (userId == null || userId.isBlank()) return errorJson("userId is required");
        if (message == null || message.isBlank()) return errorJson("message is required");

        try {
            var env = parseEnvironment(environment);

            // Step 1: Get or create the user conversation for this intent
            var userConversation = getOrCreateManagedConversation(intent, userId, env);

            // Step 2: Send the message using the existing sendMessageAndWait helper
            var snapshot = sendMessageAndWait(
                    userConversation.getEnvironment(),
                    userConversation.getBotId(),
                    userConversation.getConversationId(),
                    message);

            // Step 3: Build response
            var result = buildConversationResponse(snapshot, userConversation.getConversationId());
            result.putFirst("intent", intent);
            result.putFirst("userId", userId);
            result.putFirst("botId", userConversation.getBotId());
            result.putFirst("conversationId", userConversation.getConversationId());
            result.putFirst("environment", userConversation.getEnvironment().name());
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.errorv("MCP chat_managed failed for intent={0}, userId={1}: {2}", intent, userId, e.getMessage());
            return errorJson("Failed to chat via managed bot: " + e.getMessage());
        }
    }

    /**
     * Get an existing user conversation for the intent+userId, or create a new one.
     * Mirrors the logic from {@code RestBotManagement.initUserConversation}.
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
            ConversationState state = restBotEngine.getConversationState(
                    existing.getEnvironment(), existing.getConversationId());
            if (!ConversationState.ENDED.equals(state)) {
                return existing;
            }
            // Ended — delete and create fresh
            userConversationStore.deleteUserConversation(intent, userId);
        }

        // Create a new conversation from the bot trigger
        BotTriggerConfiguration trigger = botTriggerStore.readBotTrigger(intent);
        if (trigger == null || trigger.getBotDeployments().isEmpty()) {
            throw new RuntimeException("No bot trigger configured for intent: " + intent);
        }

        // Pick first deployment
        BotDeployment deployment = trigger.getBotDeployments().getFirst();
        String botId = deployment.getBotId();
        var usedEnv = deployment.getEnvironment() != null ? deployment.getEnvironment() : env;

        // Start a new conversation
        var initialContext = new HashMap<>(deployment.getInitialContext());
        jakarta.ws.rs.core.Response botResponse = restBotEngine.startConversationWithContext(
                usedEnv, botId, userId, initialContext);

        if (botResponse.getStatus() != 201) {
            throw new RuntimeException("Failed to create conversation for intent=" + intent +
                    ", botId=" + botId + ", status=" + botResponse.getStatus());
        }

        var locationUri = URI.create(botResponse.getHeaders().get("location").getFirst().toString());
        var resourceId = RestUtilities.extractResourceId(locationUri);
        String conversationId = resourceId.getId();

        // Store the user conversation mapping
        var userConversation = new UserConversation(intent, userId, usedEnv, botId, conversationId);
        userConversationStore.createUserConversation(userConversation);

        return userConversation;
    }

    /**
     * Send a message to a bot synchronously and wait for the response.
     * Bridges the async callback pattern to a blocking call.
     */
    private SimpleConversationMemorySnapshot sendMessageAndWait(
            Deployment.Environment env, String botId, String conversationId, String message)
            throws Exception {

        var inputData = new InputData();
        inputData.setInput(message);
        inputData.setContext(Map.of("mcp", new Context(Context.ContextType.string, "true")));

        var responseFuture = new CompletableFuture<SimpleConversationMemorySnapshot>();

        conversationService.say(env, botId, conversationId,
                false, true, Collections.emptyList(),
                inputData, false,
                snapshot -> {
                    if (snapshot != null) {
                        responseFuture.complete(snapshot);
                    } else {
                        responseFuture.completeExceptionally(
                                new RuntimeException("Bot returned null response"));
                    }
                });

        return responseFuture.get(CONVERSATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Build an AI-agent-friendly response from a conversation snapshot.
     * Extracts top-level fields: botResponse (text), quickReplies, actions,
     * conversationState — so AI agents don't need to dig into the raw snapshot.
     */
    private LinkedHashMap<String, Object> buildConversationResponse(
            SimpleConversationMemorySnapshot snapshot, String conversationId) {

        var result = new LinkedHashMap<String, Object>();

        // Extract from the LAST conversationOutput (the current step's output)
        var outputs = snapshot.getConversationOutputs();
        if (outputs != null && !outputs.isEmpty()) {
            var lastOutput = outputs.get(outputs.size() - 1);

            // Bot response text — extract text strings from output items
            var outputItems = lastOutput.get("output");
            if (outputItems instanceof List<?> items) {
                var texts = new java.util.ArrayList<String>();
                for (var item : items) {
                    if (item instanceof Map<?, ?> map && map.containsKey("text")) {
                        texts.add(String.valueOf(map.get("text")));
                    }
                }
                if (!texts.isEmpty()) {
                    result.put("botResponse", String.join(" ", texts));
                    if (texts.size() > 1) {
                        result.put("botResponseParts", texts);
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
