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
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * MCP tools for conversing with EDDI bots.
 * Exposes list_bots, create_conversation, talk_to_bot, and read_conversation
 * as MCP-compliant tools via the Quarkus MCP Server extension.
 *
 * <p>Phase 8a — Item 35: Bot Conversations MCP Server (5 SP)
 *
 * @author ginccc
 */
@ApplicationScoped
public class McpConversationTools {

    private static final Logger LOGGER = Logger.getLogger(McpConversationTools.class);
    private static final String DEFAULT_ENV = "unrestricted";
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

    @Tool(description = "List all deployed bots with their status, version, and name. " +
            "Returns a JSON array of bot deployment statuses.")
    public String listBots(
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'")
            String environment) {
        try {
            var env = parseEnvironment(environment);
            List<BotDeploymentStatus> statuses = botAdmin.getDeploymentStatuses(env);
            return jsonSerialization.serialize(statuses);
        } catch (Exception e) {
            LOGGER.error("MCP listBots failed", e);
            return errorJson("Failed to list bots: " + e.getMessage());
        }
    }

    @Tool(description = "List all bot configurations (including those not yet deployed). " +
            "Returns a JSON array of bot descriptors with name, description, and IDs.")
    public String listBotConfigs(
            @ToolArg(description = "Optional filter string to search bot names") String filter,
            @ToolArg(description = "Maximum number of results (default 20)") String limit) {
        try {
            int limitInt = parseIntOrDefault(limit, 20);
            String filterStr = filter != null ? filter : "";
            List<DocumentDescriptor> descriptors = botStore.readBotDescriptors(filterStr, 0, limitInt);
            return jsonSerialization.serialize(descriptors);
        } catch (Exception e) {
            LOGGER.error("MCP listBotConfigs failed", e);
            return errorJson("Failed to list bot configs: " + e.getMessage());
        }
    }

    @Tool(description = "Start a new conversation with a deployed bot. " +
            "Returns the conversationId which you need for subsequent talk_to_bot calls.")
    public String createConversation(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'")
            String environment) {
        try {
            var env = parseEnvironment(environment);
            ConversationResult result = conversationService.startConversation(
                    env, botId, null, Collections.emptyMap());
            return jsonSerialization.serialize(Map.of(
                    "conversationId", result.conversationId(),
                    "conversationUri", result.conversationUri().toString(),
                    "botId", botId,
                    "environment", env.name()
            ));
        } catch (Exception e) {
            LOGGER.error("MCP createConversation failed for bot " + botId, e);
            return errorJson("Failed to create conversation: " + e.getMessage());
        }
    }

    @Tool(description = "Send a message to a bot in an existing conversation and get the bot's response. " +
            "You must first call create_conversation to get a conversationId.")
    public String talkToBot(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Conversation ID from create_conversation (required)") String conversationId,
            @ToolArg(description = "The user message to send to the bot (required)") String message,
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'")
            String environment) {
        try {
            var env = parseEnvironment(environment);

            var inputData = new InputData();
            inputData.setInput(message);
            inputData.setContext(Map.of("mcp", new Context(Context.ContextType.string, "true")));

            // Use CompletableFuture to bridge the async callback
            var responseFuture = new CompletableFuture<SimpleConversationMemorySnapshot>();

            conversationService.say(env, botId, conversationId,
                    false, true, Collections.emptyList(),
                    inputData, false,
                    responseFuture::complete);

            var snapshot = responseFuture.get(CONVERSATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Extract the output from the conversation
            return jsonSerialization.serialize(snapshot);
        } catch (Exception e) {
            LOGGER.error("MCP talkToBot failed for bot " + botId + " conversation " + conversationId, e);
            return errorJson("Failed to talk to bot: " + e.getMessage());
        }
    }

    @Tool(description = "Read conversation history and memory. " +
            "Returns the conversation memory snapshot including all steps and outputs.")
    public String readConversation(
            @ToolArg(description = "Bot ID (required)") String botId,
            @ToolArg(description = "Conversation ID (required)") String conversationId,
            @ToolArg(description = "Environment: 'unrestricted' (default), 'restricted', or 'test'")
            String environment) {
        try {
            var env = parseEnvironment(environment);
            var snapshot = conversationService.readConversation(
                    env, botId, conversationId, false, false, Collections.emptyList());
            return jsonSerialization.serialize(snapshot);
        } catch (Exception e) {
            LOGGER.error("MCP readConversation failed for conversation " + conversationId, e);
            return errorJson("Failed to read conversation: " + e.getMessage());
        }
    }

    @Tool(description = "Read conversation log as formatted text. " +
            "Returns the conversation history in a human-readable format.")
    public String readConversationLog(
            @ToolArg(description = "Conversation ID (required)") String conversationId,
            @ToolArg(description = "Number of recent steps to include (default: all)") String logSize) {
        try {
            int size = parseIntOrDefault(logSize, -1);
            var result = conversationService.readConversationLog(
                    conversationId, "text", size > 0 ? size : null);
            return result.content().toString();
        } catch (Exception e) {
            LOGGER.error("MCP readConversationLog failed for conversation " + conversationId, e);
            return errorJson("Failed to read conversation log: " + e.getMessage());
        }
    }

    // --- helpers ---

    private Environment parseEnvironment(String environment) {
        if (environment == null || environment.isBlank()) {
            return Environment.unrestricted;
        }
        try {
            return Environment.valueOf(environment.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            return Environment.unrestricted;
        }
    }

    private int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String errorJson(String message) {
        return "{\"error\":\"" + message.replace("\"", "'").replace("\n", " ") + "\"}";
    }
}
