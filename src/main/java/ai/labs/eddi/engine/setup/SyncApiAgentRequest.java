/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Body of {@code POST /administration/agents/{agentId}/setup-api/sync}: the
 * spec-related fields of {@link CreateApiAgentRequest}, so the body a script
 * sent to {@code setup-api} can be sent here unchanged. The fields that belong
 * to the integrator once the agent exists — {@code agentName},
 * {@code systemPrompt}, {@code provider}, {@code model}, {@code apiKey} and the
 * rest — are ignored: sync keeps the agent's own.
 *
 * @param openApiSpec
 *            the new spec, inline or a URL; required
 * @param endpoints
 *            the endpoint filter, as on setup; null for every operation
 * @param apiBaseUrl
 *            the API's base URL; null takes the spec's first server
 * @param apiAuth
 *            the credential header value; null keeps the value the generated
 *            calls already send. A plaintext value is vaulted as on setup
 * @param apiAuthHeader
 *            header for {@code apiAuth}; null keeps the one recorded at setup
 * @param hitlConfig
 *            only its {@code toolApprovals.requireApproval} patterns are used:
 *            they are added to the agent's gate, never removed from it. Every
 *            other field of the agent's {@code hitlConfig} stays as stored
 * @param allowedHosts
 *            destinations for a vaulted {@code apiAuth}, as on setup
 * @param deploy
 *            deploy the new agent version; null means yes, as on setup
 * @param environment
 *            where to deploy
 * @param dryRun
 *            report what would change and write nothing
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncApiAgentRequest(@JsonProperty(required = true) String openApiSpec, String endpoints, String apiBaseUrl, String apiAuth,
        String apiAuthHeader, AgentConfiguration.HitlConfig hitlConfig, List<String> allowedHosts, Boolean deploy, String environment,
        Boolean dryRun) {
}
