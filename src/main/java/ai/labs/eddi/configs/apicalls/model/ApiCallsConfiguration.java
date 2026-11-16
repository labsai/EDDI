/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
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

    /**
     * Provenance stamped by {@code POST /administration/agents/setup-api} on every
     * configuration it generates from an OpenAPI spec, and read by {@code POST
     * /administration/agents/{agentId}/setup-api/sync} to know which of an agent's
     * httpcalls configurations it may regenerate.
     * <p>
     * <b>Absence is a switch, off:</b> {@code null} on every hand-written
     * configuration and on every one generated before the field existed, and sync
     * never touches a configuration without it. An agent with no marked
     * configuration is refused by sync with a message saying how to add the marker.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private OpenApiOrigin openApiOrigin;

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

    public OpenApiOrigin getOpenApiOrigin() {
        return openApiOrigin;
    }

    public void setOpenApiOrigin(OpenApiOrigin openApiOrigin) {
        this.openApiOrigin = openApiOrigin;
    }

    /**
     * Which part of a spec a generated configuration holds.
     */
    public static class OpenApiOrigin {
        /** The OpenAPI tag the configuration's operations were grouped under. */
        private String group;

        /**
         * The header the generated calls send {@code apiAuth} in, or {@code null} when
         * the setup had no {@code apiAuth}. A name, never a value: sync reads the value
         * from that header of the existing calls when its request carries no
         * {@code apiAuth}.
         */
        private String authHeader;

        public OpenApiOrigin() {
        }

        public OpenApiOrigin(String group, String authHeader) {
            this.group = group;
            this.authHeader = authHeader;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public String getAuthHeader() {
            return authHeader;
        }

        public void setAuthHeader(String authHeader) {
            this.authHeader = authHeader;
        }
    }
}
