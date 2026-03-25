package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A CDI bean that provides a generic @Tool for Langchain4j agents.
 * This tool acts as a bridge, allowing AI agents to execute pre-configured EDDI httpcalls.
 * <p>
 * The conversationId is injected via {@link #setCurrentConversationId(String)} before
 * the tool-calling loop — the LLM only needs to provide the {@code httpCallUri}.
 * <p>
 * Security: the agent can ONLY call httpcalls that have been explicitly configured
 * and granted to it, not arbitrary APIs.
 *
 * @author ginccc
 */
@ApplicationScoped
public class EddiToolBridge {
    private static final Logger LOGGER = Logger.getLogger(EddiToolBridge.class);

    /**
     * ThreadLocal to hold the current conversationId during a tool-calling loop.
     * Set by {@link ai.labs.eddi.modules.llm.impl.AgentOrchestrator} before execution,
     * cleared after the loop completes.
     */
    private static final ThreadLocal<String> CURRENT_CONVERSATION_ID = new ThreadLocal<>();

    @Inject
    IConversationMemoryStore conversationMemoryStore;

    @Inject
    IResourceClientLibrary resourceClientLibrary;

    @Inject
    IJsonSerialization jsonSerialization;

    @Inject
    IApiCallExecutor httpCallExecutor;

    @Inject
    ToolExecutionService toolExecutionService;

    // Cache for httpcall configurations to avoid repeated lookups
    private final Map<String, ApiCallsConfiguration> configCache = new ConcurrentHashMap<>();

    /**
     * Set the current conversation context before the agent tool-calling loop.
     * Called by AgentOrchestrator.
     */
    public static void setCurrentConversationId(String conversationId) {
        CURRENT_CONVERSATION_ID.set(conversationId);
    }

    /**
     * Clear the conversation context after the tool-calling loop completes.
     * Called by AgentOrchestrator in a finally block.
     */
    public static void clearCurrentConversationId() {
        CURRENT_CONVERSATION_ID.remove();
    }

    /**
     * Executes a pre-configured EDDI API call.
     * <p>
     * The LLM only needs to provide the httpcall URI — the conversation context
     * is injected automatically by the system.
     *
     * @param httpCallUri URI of the httpcall configuration
     *                    (e.g., "eddi://ai.labs.apicalls/apicallstore/apicalls/abc123?version=1")
     * @return JSON string with the API call result
     */
    @Tool("Executes a pre-configured EDDI API call to fetch real data. " +
            "Pass the exact httpCallUri that was provided to you.")
    public String executeApiCall(
            @P("The httpcall URI to execute, e.g. eddi://ai.labs.apicalls/apicallstore/apicalls/ID?version=1")
            String httpCallUri) {

        String conversationId = CURRENT_CONVERSATION_ID.get();
        if (conversationId == null) {
            return errorResult("No conversation context available for tool execution");
        }

        try {
            LOGGER.info("Agent executing httpcall: " + httpCallUri + " for conversation: " + conversationId);

            // Parse the URI to extract the httpcall configuration reference
            URI uri = URI.create(httpCallUri);

            // Load the ApiCallsConfiguration
            ApiCallsConfiguration config = getOrLoadConfig(uri);
            if (config == null) {
                return errorResult("ApiCalls configuration not found: " + httpCallUri);
            }

            // Find the first ApiCall in the configuration
            ApiCall httpCall = config.getHttpCalls().stream()
                    .findFirst()
                    .orElse(null);

            if (httpCall == null) {
                return errorResult("No httpcalls found in configuration: " + httpCallUri);
            }

            // Load the conversation memory snapshot for template data
            var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            var memory = new ConversationMemory(
                    conversationId, snapshot.getAgentId(),
                    snapshot.getAgentVersion(), snapshot.getUserId());

            // Execute the httpcall
            Map<String, Object> templateData = new HashMap<>();
            Map<String, Object> result = httpCallExecutor.execute(
                    httpCall, memory, templateData, config.getTargetServerUrl());

            return jsonSerialization.serialize(result);

        } catch (IResourceStore.ResourceNotFoundException e) {
            LOGGER.error("ApiCall configuration not found: " + httpCallUri, e);
            return errorResult("Configuration not found: " + httpCallUri);
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.error("Error loading httpcall configuration: " + httpCallUri, e);
            return errorResult("Error loading configuration: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Error executing httpcall: " + httpCallUri, e);
            return errorResult("Execution error: " + e.getMessage());
        }
    }

    /**
     * Loads or retrieves from cache an ApiCallsConfiguration
     */
    private ApiCallsConfiguration getOrLoadConfig(URI uri)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        String cacheKey = uri.toString();
        if (!configCache.containsKey(cacheKey)) {
            try {
                ApiCallsConfiguration config = resourceClientLibrary.getResource(uri, ApiCallsConfiguration.class);
                configCache.put(cacheKey, config);
            } catch (ServiceException e) {
                throw new IResourceStore.ResourceStoreException("Error loading configuration", e);
            }
        }
        return configCache.get(cacheKey);
    }

    /**
     * Creates a JSON error result for the agent
     */
    private String errorResult(String errorMessage) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", true);
        error.put("message", errorMessage);
        try {
            return jsonSerialization.serialize(error);
        } catch (Exception e) {
            return "{\"error\": true, \"message\": \"" + errorMessage + "\"}";
        }
    }
}
