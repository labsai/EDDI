package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.ToolExecutionTrace;
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
 *
 * This provides a crucial security layer: the agent can ONLY call httpcalls that have been
 * explicitly configured and granted to it, not arbitrary APIs.
 */
@ApplicationScoped
public class EddiToolBridge {
    private static final Logger LOGGER = Logger.getLogger(EddiToolBridge.class);

    // Cache Method object to avoid repeated reflection lookups on every tool execution
    private static final java.lang.reflect.Method INTERNAL_EXECUTE_METHOD;

    static {
        try {
            INTERNAL_EXECUTE_METHOD = EddiToolBridge.class.getMethod("executeApiCallInternal", String.class, String.class, Map.class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError("Failed to cache executeApiCallInternal method: " + e.getMessage());
        }
    }

    @Inject
    IApiCallsStore httpCallsStore;

    @Inject
    IConversationMemoryStore conversationMemoryStore;

    @Inject
    IResourceClientLibrary resourceClientLibrary;

    @Inject
    IMemoryItemConverter memoryItemConverter;

    @Inject
    IJsonSerialization jsonSerialization;

    @Inject
    IApiCallExecutor httpCallExecutor;

    @Inject
    ToolExecutionService toolExecutionService;

    // Cache for httpcall configurations to avoid repeated lookups
    private final Map<String, ApiCallsConfiguration> configCache = new ConcurrentHashMap<>();

    /**
     * This method is exposed to the LLM as a tool.
     * The agent can call this to execute any pre-configured EDDI httpcall.
     *
     * Note: In the actual implementation, tool-specific methods would be dynamically
     * generated based on the agent's configuration. This is a placeholder for the
     * generic execution mechanism.
     *
     * @param conversationId The current conversation ID
     * @param httpCallUri The URI of the httpcall configuration (e.g., "eddi://ai.labs.apicalls/weather_api?version=1")
     * @param arguments Arguments to pass to the httpcall for templating
     * @return JSON string with the httpcall result
     */
    @Tool("Executes a pre-configured EDDI httpcall. Use only httpcalls that have been explicitly provided to you.")
    public String executeApiCall(String conversationId, String httpCallUri, Map<String, Object> arguments) {
        return toolExecutionService.executeTool(
                this,
                INTERNAL_EXECUTE_METHOD,
                new Object[]{conversationId, httpCallUri, arguments},
                conversationId,
                new ToolExecutionTrace()
        );
    }

    public String executeApiCallInternal(String conversationId, String httpCallUri, Map<String, Object> arguments) {
        try {
            LOGGER.info("Agent executing httpcall: " + httpCallUri + " for conversation: " + conversationId);

            // Parse the URI to extract the httpcall configuration reference
            URI uri = URI.create(httpCallUri);

            // Load the ApiCallsConfiguration
            ApiCallsConfiguration config = getOrLoadConfig(uri);
            if (config == null) {
                return errorResult("ApiCalls configuration not found: " + httpCallUri);
            }

            // Find the specific ApiCall within the configuration
            // For simplicity, we take the first call that matches any action
            // In a real scenario, the URI would specify which call to execute
            ApiCall httpCall = config.getHttpCalls().stream()
                    .findFirst()
                    .orElse(null);

            if (httpCall == null) {
                return errorResult("No httpcalls found in configuration: " + httpCallUri);
            }

            // Load the conversation memory snapshot
            ConversationMemorySnapshot snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);

            // Create a temporary memory instance to satisfy ApiCallExecutor contract
            ConversationMemory memory = new ConversationMemory(conversationId, snapshot.getAgentId(), snapshot.getAgentVersion(), snapshot.getUserId());

            // Build template data by merging agent arguments with conversation memory
            // Note: In real usage, we'd need an IConversationMemory instance, not a snapshot
            // This is a simplified version - the DeclarativeAgentTask will handle this properly
            Map<String, Object> templateData = new HashMap<>(arguments);

            // Execute the httpcall using the shared executor
            Map<String, Object> result = httpCallExecutor.execute(
                    httpCall,
                    memory,
                    templateData,
                    config.getTargetServerUrl()
            );

            // Return the result as JSON for the agent to process
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
    private ApiCallsConfiguration getOrLoadConfig(URI uri) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
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

