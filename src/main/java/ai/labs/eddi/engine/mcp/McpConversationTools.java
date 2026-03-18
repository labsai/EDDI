package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.bots.IRestBotStore;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.IConversationService;
import ai.labs.eddi.engine.IConversationService.ConversationResult;
import ai.labs.eddi.engine.IRestBotAdministration;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.BotDeploymentStatus;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.InputData;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final IJsonSerialization jsonSerialization;

    @Inject
    public McpConversationTools(IConversationService conversationService,
            IRestBotAdministration botAdmin,
            IRestBotStore botStore,
            IJsonSerialization jsonSerialization) {
        this.conversationService = conversationService;
        this.botAdmin = botAdmin;
        this.botStore = botStore;
        this.jsonSerialization = jsonSerialization;
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
                    result.put("botResponse", texts);
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
