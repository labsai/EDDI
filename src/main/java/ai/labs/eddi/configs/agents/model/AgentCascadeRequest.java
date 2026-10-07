/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Body of {@code POST /agentstore/agents/{id}/cascade}: the resource URI
 * changes to wire into the agent in one pass, and what to do afterwards.
 * <p>
 * Every field is a <em>pointer change</em>. There is deliberately no field that
 * carries a document, so nothing in this request can set {@code hitlConfig} or
 * any other agent setting — see
 * {@link ai.labs.eddi.configs.agents.AgentCascadeService}.
 *
 * @param resourceMappings
 *            old to new URI pairs, both with a {@code ?version=N}, both naming
 *            the same kind of resource
 * @param redeploy
 *            deploy the new agent version afterwards (default {@code false})
 * @param environment
 *            {@code production} (default) or {@code test}; only used when
 *            {@code redeploy} is set
 * @param compatible
 *            whether conversations already running switch to the new version on
 *            their next turn (default {@code false}, a breaking change)
 */
public record AgentCascadeRequest(
        @Schema(description = "URI changes to apply, in one pass. Each oldUri must be a reference the agent's current "
                + "workflows actually hold — an agent workflow reference or a workflow step's resource reference. "
                + "Each newUri must be the same kind of resource with a version, e.g. "
                + "oldUri=eddi://ai.labs.llm/llmstore/llms/ID?version=1 newUri=eddi://ai.labs.llm/llmstore/llms/ID?version=2.")
        @JsonProperty(required = true) List<ResourceMapping> resourceMappings,
        @Schema(description = "Redeploy the new agent version afterwards. Default false.") Boolean redeploy,
        @Schema(description = "Environment for the redeploy: production (default) or test.") String environment,
        @Schema(description = "Whether the new agent version is compatible with the one it replaces. true = conversations "
                + "already running switch to it on their next turn once it is deployed — only for changes that cannot "
                + "break one in progress. false (default) = a breaking change; running conversations stay on their "
                + "version.") Boolean compatible) {

    /**
     * One URI change.
     *
     * @param oldUri
     *            the reference to replace, e.g.
     *            {@code eddi://ai.labs.llm/llmstore/llms/ID?version=1}
     * @param newUri
     *            what to replace it with, e.g.
     *            {@code eddi://ai.labs.llm/llmstore/llms/ID?version=2}
     */
    public record ResourceMapping(@JsonProperty(required = true) String oldUri, @JsonProperty(required = true) String newUri) {
    }
}
