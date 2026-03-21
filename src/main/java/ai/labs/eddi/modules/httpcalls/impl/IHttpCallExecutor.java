package ai.labs.eddi.modules.httpcalls.impl;

import ai.labs.eddi.configs.httpcalls.model.HttpCall;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;

import java.util.Map;

/**
 * Interface for executing HTTP calls configured in EDDI.
 * This abstraction allows reuse of HTTP call execution logic
 * across different tasks (e.g., HttpCallsTask, Agent tools).
 */
public interface IHttpCallExecutor {
    /**
     * Executes a configured HTTP call.
     *
     * @param httpCall The HTTP call configuration
     * @param memory   The conversation memory for templating and state
     * @param templateDataObjects The template data objects for variable substitution
     * @param targetServerUrl The target server URL
     * @return The response object (parsed JSON or raw string)
     * @throws LifecycleException if execution fails
     */
    Map<String, Object> execute(HttpCall httpCall,
                                 IConversationMemory memory,
                                 Map<String, Object> templateDataObjects,
                                 String targetServerUrl) throws LifecycleException;
}

