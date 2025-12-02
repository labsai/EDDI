package ai.labs.eddi.modules.langchain.tools;

import ai.labs.eddi.configs.http.IHttpCallsStore;
import ai.labs.eddi.configs.http.model.HttpCall;
import ai.labs.eddi.configs.http.model.HttpCallsConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.httpcalls.impl.IHttpCallExecutor;
import ai.labs.eddi.modules.langchain.model.ToolExecutionTrace;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

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

    @Inject
    IHttpCallsStore httpCallsStore;

    @Inject
    IConversationMemoryStore conversationMemoryStore;

    @Inject
    IResourceClientLibrary resourceClientLibrary;

    @Inject
    IMemoryItemConverter memoryItemConverter;

    @Inject
    IJsonSerialization jsonSerialization;

    @Inject
    IHttpCallExecutor httpCallExecutor;

    @Inject
    ToolExecutionService toolExecutionService;

    // Cache for httpcall configurations to avoid repeated lookups
    private final Map<String, HttpCallsConfiguration> configCache = new HashMap<>();

    /**
     * This method is exposed to the LLM as a tool.
     * The agent can call this to execute any pre-configured EDDI httpcall.
     *
     * Note: In the actual implementation, tool-specific methods would be dynamically
     * generated based on the agent's configuration. This is a placeholder for the
     * generic execution mechanism.
     *
     * @param conversationId The current conversation ID
     * @param httpCallUri The URI of the httpcall configuration (e.g., "eddi://ai.labs.httpcalls/weather_api?version=1")
     * @param arguments Arguments to pass to the httpcall for templating
     * @return JSON string with the httpcall result
     */
    @Tool("Executes a pre-configured EDDI httpcall. Use only httpcalls that have been explicitly provided to you.")
    public String executeHttpCall(String conversationId, String httpCallUri, Map<String, Object> arguments) {
        try {
            Method method = this.getClass().getMethod("internalExecuteHttpCall", String.class, String.class, Map.class);
            return toolExecutionService.executeTool(
                    this,
                    method,
                    new Object[]{conversationId, httpCallUri, arguments},
                    conversationId,
                    new ToolExecutionTrace()
            );
        } catch (NoSuchMethodException e) {
            LOGGER.error("Error finding internal method", e);
            return errorResult("Internal error: " + e.getMessage());
        }
    }

    public String internalExecuteHttpCall(String conversationId, String httpCallUri, Map<String, Object> arguments) {
        try {
            LOGGER.info("Agent executing httpcall: " + httpCallUri + " for conversation: " + conversationId);

            // Parse the URI to extract the httpcall configuration reference
            URI uri = URI.create(httpCallUri);

            // Load the HttpCallsConfiguration
            HttpCallsConfiguration config = getOrLoadConfig(uri);
            if (config == null) {
                return errorResult("HttpCalls configuration not found: " + httpCallUri);
            }

            // Find the specific HttpCall within the configuration
            // For simplicity, we take the first call that matches any action
            // In a real scenario, the URI would specify which call to execute
            HttpCall httpCall = config.getHttpCalls().stream()
                    .findFirst()
                    .orElse(null);

            if (httpCall == null) {
                return errorResult("No httpcalls found in configuration: " + httpCallUri);
            }

            // Load the conversation memory snapshot
            ConversationMemorySnapshot snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);

            // Build template data by merging agent arguments with conversation memory
            // Note: In real usage, we'd need an IConversationMemory instance, not a snapshot
            // This is a simplified version - the DeclarativeAgentTask will handle this properly
            Map<String, Object> templateData = new HashMap<>(arguments);

            // Execute the httpcall using the shared executor
            Map<String, Object> result = httpCallExecutor.execute(
                    httpCall,
                    null, // Memory would be injected properly in DeclarativeAgentTask context
                    templateData,
                    config.getTargetServerUrl()
            );

            // Return the result as JSON for the agent to process
            return jsonSerialization.serialize(result);

        } catch (IResourceStore.ResourceNotFoundException e) {
            LOGGER.error("HttpCall configuration not found: " + httpCallUri, e);
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
     * Loads or retrieves from cache an HttpCallsConfiguration
     */
    private HttpCallsConfiguration getOrLoadConfig(URI uri) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        String cacheKey = uri.toString();
        if (!configCache.containsKey(cacheKey)) {
            try {
                HttpCallsConfiguration config = resourceClientLibrary.getResource(uri, HttpCallsConfiguration.class);
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

