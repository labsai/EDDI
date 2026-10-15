/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.hitl.tools.SelfUngatingGuard.Protection;
import ai.labs.eddi.engine.hitl.tools.SelfUngatingGuard.StoredGateLookup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Resolves, at gate time, what an approved tool call must not write to: the
 * acting agent, the workflows it lists and every extension resource those
 * workflows reference (security review 2026-10-15, finding L13).
 * <p>
 * The agent id alone recognised only the loop's hinge. The LLM configuration,
 * rules and api-call documents an agent runs on carry their own ids, and a
 * write to one of them — {@code PUT /llmstore/llms/{own-llm-id}} with a
 * permissive document — rewrites what the agent does without naming the agent
 * anywhere. Those ids are read from the stores here, once per resumed batch,
 * and handed to {@link ai.labs.eddi.engine.hitl.tools.SelfUngatingGuard} as a
 * {@link Protection}.
 * <p>
 * A read that fails does not weaken the gate: the protection is marked
 * unresolved and the guard then refuses every write to a configuration store,
 * because it cannot tell the agent's own documents from another's.
 */
final class ActingAgentResources {

    private static final Logger LOGGER = Logger.getLogger(ActingAgentResources.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The 24-hex id at the end of an {@code eddi://…/{id}?version=n} URI. */
    private static final Pattern RESOURCE_ID = Pattern.compile("/([0-9a-fA-F]{24})(?:\\?|$)");
    private static final Pattern VERSION = Pattern.compile("(?:^|[?&])version=(\\d+)");

    private ActingAgentResources() {
    }

    static Protection resolve(IAgentStore agentStore, IWorkflowStore workflowStore, String agentId, Integer agentVersion) {
        StoredGateLookup storedGates = agentStore == null ? null : targetId -> {
            AgentConfiguration target = agentStore.read(targetId, agentStore.getCurrentResourceId(targetId).getVersion());
            return target.getHitlConfig() == null ? null : MAPPER.valueToTree(target.getHitlConfig());
        };
        if (agentId == null || agentId.isBlank()) {
            return new Protection(agentId, Set.of(), false, storedGates);
        }
        if (agentStore == null || workflowStore == null) {
            LOGGER.warnf("Own resources of agent '%s' cannot be resolved without the agent and workflow stores — every configuration "
                    + "store write in this approved batch is refused", sanitize(agentId));
            return new Protection(agentId, Set.of(), true, storedGates);
        }
        Set<String> ids = new HashSet<>();
        try {
            int version = agentVersion != null && agentVersion > 0 ? agentVersion : agentStore.getCurrentResourceId(agentId).getVersion();
            AgentConfiguration agent = agentStore.read(agentId, version);
            if (agent.getWorkflows() != null) {
                for (URI workflowUri : agent.getWorkflows()) {
                    String workflowId = idOf(workflowUri.toString());
                    if (workflowId == null) {
                        continue;
                    }
                    ids.add(workflowId);
                    Integer workflowVersion = versionOf(workflowUri.toString());
                    WorkflowConfiguration workflow = workflowStore.read(workflowId,
                            workflowVersion != null ? workflowVersion : workflowStore.getCurrentResourceId(workflowId).getVersion());
                    if (workflow.getWorkflowSteps() == null) {
                        continue;
                    }
                    for (WorkflowConfiguration.WorkflowStep step : workflow.getWorkflowSteps()) {
                        Object resourceUri = step.getConfig() != null ? step.getConfig().get("uri") : null;
                        String resourceId = resourceUri != null ? idOf(resourceUri.toString()) : null;
                        if (resourceId != null) {
                            ids.add(resourceId);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("Own resources of agent '%s' could not be resolved (%s) — every configuration store write in this approved "
                    + "batch is refused", sanitize(agentId), e.getClass().getSimpleName());
            return new Protection(agentId, ids, true, storedGates);
        }
        return new Protection(agentId, ids, false, storedGates);
    }

    static String idOf(String uri) {
        if (uri == null) {
            return null;
        }
        Matcher m = RESOURCE_ID.matcher(uri);
        return m.find() ? m.group(1) : null;
    }

    static Integer versionOf(String uri) {
        if (uri == null) {
            return null;
        }
        Matcher m = VERSION.matcher(uri);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** Exposed for the stored-gate comparison test. */
    static JsonNode hitlConfigJson(AgentConfiguration agent) {
        return agent == null || agent.getHitlConfig() == null ? null : MAPPER.valueToTree(agent.getHitlConfig());
    }
}
