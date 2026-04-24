/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.mcpcalls.model;

import ai.labs.eddi.configs.apicalls.model.PostResponse;
import ai.labs.eddi.configs.apicalls.model.PreRequest;

import java.util.List;
import java.util.Map;

/**
 * A deterministic MCP tool call binding — maps behavior-rule actions to a
 * specific MCP tool invocation.
 *
 * <p>
 * Analogous to {@link ai.labs.eddi.configs.apicalls.model.ApiCall} but for MCP
 * protocol calls. Much simpler because MCP handles the transport — you only
 * specify the tool name and its arguments (templated from conversation memory).
 * </p>
 *
 * <p>
 * Uses the base {@link PreRequest} and {@link PostResponse} classes (not the
 * HTTP-specific subclasses) since MCP doesn't need batch/retry/delay.
 * </p>
 */
public class McpCall {

    /** Human-readable name for logging and debugging */
    private String name;

    /**
     * Natural language description. Used as tool description when this config is
     * auto-discovered by the LLM agent.
     */
    private String description;

    /**
     * Actions that trigger this call (from behavior rules). Supports "*" wildcard.
     */
    private List<String> actions;

    /** Name of the MCP tool to invoke (as reported by the server's tools/list) */
    private String toolName;

    /**
     * Tool arguments, templated from conversation memory. Values can use Qute
     * template expressions (e.g., "[[${properties.userId}]]").
     */
    private Map<String, Object> toolArguments;

    /**
     * Pre-request property instructions — prepare template variables before the
     * call
     */
    private PreRequest preRequest;

    /** Whether to persist the tool result in conversation memory */
    private Boolean saveResponse = true;

    /** Key name for storing the result in template data (e.g., "githubRepos") */
    private String responseObjectName;

    /**
     * Post-response processing — property instructions, output building, quick
     * replies
     */
    private PostResponse postResponse;

    // --- Getters and Setters ---

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getActions() {
        return actions;
    }

    public void setActions(List<String> actions) {
        this.actions = actions;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Map<String, Object> getToolArguments() {
        return toolArguments;
    }

    public void setToolArguments(Map<String, Object> toolArguments) {
        this.toolArguments = toolArguments;
    }

    public PreRequest getPreRequest() {
        return preRequest;
    }

    public void setPreRequest(PreRequest preRequest) {
        this.preRequest = preRequest;
    }

    public Boolean getSaveResponse() {
        return saveResponse;
    }

    public void setSaveResponse(Boolean saveResponse) {
        this.saveResponse = saveResponse;
    }

    public String getResponseObjectName() {
        return responseObjectName;
    }

    public void setResponseObjectName(String responseObjectName) {
        this.responseObjectName = responseObjectName;
    }

    public PostResponse getPostResponse() {
        return postResponse;
    }

    public void setPostResponse(PostResponse postResponse) {
        this.postResponse = postResponse;
    }
}
