/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * @author ginccc
 */

public class ApiCallsConfiguration {
    private String targetServerUrl;

    /**
     * Optional one-line summary of what this group of calls does (e.g. "Users:
     * find, invite and deactivate user accounts"). Shown to the model by
     * {@code discover_tools} when an LLM task loads its http tools lazily
     * ({@code toolLoadingStrategy: LAZY} with {@code lazySources: ["http"]}), so it
     * can pick a group without seeing every tool. The OpenAPI generator fills it
     * from the tag. Absent: nothing changes, and the group is summarised by its
     * tool names instead.
     */
    private String description;

    @JsonProperty("httpCalls")
    @JsonAlias("apiCalls")
    private List<ApiCall> httpCalls;

    public String getTargetServerUrl() {
        return targetServerUrl;
    }

    public void setTargetServerUrl(String targetServerUrl) {
        this.targetServerUrl = targetServerUrl;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<ApiCall> getHttpCalls() {
        return httpCalls;
    }

    public void setHttpCalls(List<ApiCall> httpCalls) {
        this.httpCalls = httpCalls;
    }
}
