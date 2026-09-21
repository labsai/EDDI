/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import java.util.List;
import java.util.Map;

/**
 * Result of an agent setup operation (both standard and API agent).
 *
 * @param action
 *            the operation type (e.g., "setup_complete", "api_agent_created")
 * @param agentId
 *            the created agent's ID
 * @param agentName
 *            the agent name
 * @param provider
 *            the LLM provider used
 * @param model
 *            the model used
 * @param deployed
 *            whether the agent was successfully deployed
 * @param deploymentStatus
 *            deployment status string (READY, IN_PROGRESS, etc.)
 * @param endpointCount
 *            number of API endpoints (API agent only, null for standard)
 * @param groups
 *            API tag groups (API agent only, null for standard)
 * @param quickRepliesEnabled
 *            whether quick replies are enabled
 * @param sentimentAnalysisEnabled
 *            whether sentiment analysis is enabled
 * @param resources
 *            map of created resource locations
 * @param apiKeyVaultReference
 *            the {@code ${vault:...}} reference the created agent's LLM config
 *            points at — whether this setup vaulted the key or reused an entry
 *            that already held it. Pass it back as {@code vaultKeyName} (or as
 *            {@code apiKey}) on the next setup to put another agent on the same
 *            credential without re-entering it. Null for a provider that needs
 *            no key, and null when the vault is disabled and the key was stored
 *            in plaintext: the plaintext is a secret and is deliberately never
 *            echoed back in a response body.
 *
 * @author ginccc
 */
public record SetupResult(String action, String agentId, String agentName, String provider, String model, Boolean deployed, String deploymentStatus,
        Integer endpointCount, List<String> groups, Boolean quickRepliesEnabled, Boolean sentimentAnalysisEnabled, Map<String, Object> resources,
        String apiKeyVaultReference) {

    /**
     * Builder for fluent construction.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String action;
        private String agentId;
        private String agentName;
        private String provider;
        private String model;
        private Boolean deployed;
        private String deploymentStatus;
        private Integer endpointCount;
        private List<String> groups;
        private Boolean quickRepliesEnabled;
        private Boolean sentimentAnalysisEnabled;
        private Map<String, Object> resources;
        private String apiKeyVaultReference;

        public Builder action(String action) {
            this.action = action;
            return this;
        }
        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }
        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }
        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }
        public Builder model(String model) {
            this.model = model;
            return this;
        }
        public Builder deployed(Boolean deployed) {
            this.deployed = deployed;
            return this;
        }
        public Builder deploymentStatus(String deploymentStatus) {
            this.deploymentStatus = deploymentStatus;
            return this;
        }
        public Builder endpointCount(Integer endpointCount) {
            this.endpointCount = endpointCount;
            return this;
        }
        public Builder groups(List<String> groups) {
            this.groups = groups;
            return this;
        }
        public Builder quickRepliesEnabled(Boolean quickRepliesEnabled) {
            this.quickRepliesEnabled = quickRepliesEnabled;
            return this;
        }
        public Builder sentimentAnalysisEnabled(Boolean sentimentAnalysisEnabled) {
            this.sentimentAnalysisEnabled = sentimentAnalysisEnabled;
            return this;
        }
        public Builder resources(Map<String, Object> resources) {
            this.resources = resources;
            return this;
        }
        public Builder apiKeyVaultReference(String apiKeyVaultReference) {
            this.apiKeyVaultReference = apiKeyVaultReference;
            return this;
        }

        public SetupResult build() {
            return new SetupResult(action, agentId, agentName, provider, model, deployed, deploymentStatus, endpointCount, groups,
                    quickRepliesEnabled, sentimentAnalysisEnabled, resources, apiKeyVaultReference);
        }
    }
}
