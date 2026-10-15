/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.hitl.tools.SelfUngatingGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L13: the ids an approved call must not write to are read from the stores at
 * gate time.
 */
@DisplayName("ActingAgentResources — resolving the acting agent's workflows and extension resources")
class ActingAgentResourcesTest {

    private static final String AGENT = "68f1c0ffee0000000000a9e7";
    private static final String WORKFLOW = "5f1c0f3a9d8e4b2a7c6d1e01";
    private static final String LLM = "5f1c0f3a9d8e4b2a7c6d1e02";
    private static final String RULES = "5f1c0f3a9d8e4b2a7c6d1e03";

    private static WorkflowConfiguration.WorkflowStep step(String type, String uri) {
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create(type));
        step.setConfig(uri == null ? Map.of() : Map.of("uri", uri));
        return step;
    }

    private static IResourceStore.IResourceId version(int v) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return null;
            }

            @Override
            public Integer getVersion() {
                return v;
            }
        };
    }

    @Test
    @DisplayName("the workflow ids and every step's config uri id are collected; a step without a uri is skipped")
    void collectsWorkflowAndExtensionIds() throws Exception {
        var agentStore = mock(IAgentStore.class);
        var workflowStore = mock(IWorkflowStore.class);
        var agent = new AgentConfiguration();
        agent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW + "?version=3")));
        when(agentStore.read(AGENT, 7)).thenReturn(agent);
        var workflow = new WorkflowConfiguration();
        workflow.setWorkflowSteps(List.of(
                step("eddi://ai.labs.llm", "eddi://ai.labs.llm/llmstore/llms/" + LLM + "?version=1"),
                step("eddi://ai.labs.rules", "eddi://ai.labs.rules/rulestore/rulesets/" + RULES),
                step("eddi://ai.labs.templating", null)));
        when(workflowStore.read(WORKFLOW, 3)).thenReturn(workflow);

        SelfUngatingGuard.Protection protection = ActingAgentResources.resolve(agentStore, workflowStore, AGENT, 7);

        assertEquals(AGENT, protection.actingAgentId());
        assertEquals(Set.of(WORKFLOW, LLM, RULES), protection.ownResourceIds());
        assertFalse(protection.resourcesUnresolved());
        assertNotNull(protection.storedGates());
    }

    @Test
    @DisplayName("without an agent version, the current one is read; a workflow URI without a version reads the current workflow")
    void currentVersions() throws Exception {
        var agentStore = mock(IAgentStore.class);
        var workflowStore = mock(IWorkflowStore.class);
        when(agentStore.getCurrentResourceId(AGENT)).thenReturn(version(2));
        var agent = new AgentConfiguration();
        agent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW)));
        when(agentStore.read(AGENT, 2)).thenReturn(agent);
        when(workflowStore.getCurrentResourceId(WORKFLOW)).thenReturn(version(5));
        when(workflowStore.read(WORKFLOW, 5)).thenReturn(new WorkflowConfiguration());

        var protection = ActingAgentResources.resolve(agentStore, workflowStore, AGENT, null);

        assertEquals(Set.of(WORKFLOW), protection.ownResourceIds());
        assertFalse(protection.resourcesUnresolved());
    }

    @Test
    @DisplayName("a store failure marks the protection unresolved — the gate then refuses every configuration-store write")
    void storeFailureFailsClosed() throws Exception {
        var agentStore = mock(IAgentStore.class);
        when(agentStore.read(any(), anyInt())).thenThrow(new IResourceStore.ResourceStoreException("down"));

        var protection = ActingAgentResources.resolve(agentStore, mock(IWorkflowStore.class), AGENT, 1);

        assertTrue(protection.resourcesUnresolved());
        assertEquals(SelfUngatingGuard.UNRESOLVED_RESOURCES_REASON,
                SelfUngatingGuard.refusal("PUT", "https://eddi/llmstore/llms/" + LLM, "{}", protection));
    }

    @Test
    @DisplayName("without stores nothing can be resolved: unresolved, and no stored-gate lookup")
    void withoutStores() {
        var protection = ActingAgentResources.resolve(null, null, AGENT, 1);
        assertTrue(protection.resourcesUnresolved());
        assertNull(protection.storedGates());
        assertEquals(Set.of(), protection.ownResourceIds());
    }

    @Test
    @DisplayName("no acting agent: nothing to resolve, nothing refused by id, the gate lookup still works")
    void noAgent() {
        var protection = ActingAgentResources.resolve(mock(IAgentStore.class), mock(IWorkflowStore.class), null, null);
        assertFalse(protection.resourcesUnresolved());
        assertEquals(Set.of(), protection.ownResourceIds());
        assertNotNull(protection.storedGates());
    }

    @Test
    @DisplayName("the stored-gate lookup reads the target agent's current hitlConfig as JSON, null when it has none")
    void storedGateLookup() throws Exception {
        var agentStore = mock(IAgentStore.class);
        String other = "aaaabbbbccccddddeeeeffff";
        when(agentStore.getCurrentResourceId(other)).thenReturn(version(9));
        var gated = new AgentConfiguration();
        var hitl = new AgentConfiguration.HitlConfig();
        var approvals = new ToolApprovalsConfig();
        approvals.setRequireApproval(List.of("http:*"));
        hitl.setToolApprovals(approvals);
        gated.setHitlConfig(hitl);
        when(agentStore.read(eq(other), eq(9))).thenReturn(gated);
        String ungated = "bbbbbbbbbbbbbbbbbbbbbbbb";
        when(agentStore.getCurrentResourceId(ungated)).thenReturn(version(1));
        when(agentStore.read(eq(ungated), eq(1))).thenReturn(new AgentConfiguration());

        var lookup = ActingAgentResources.resolve(agentStore, mock(IWorkflowStore.class), AGENT, 1).storedGates();

        assertEquals("http:*", lookup.hitlConfigOf(other).get("toolApprovals").get("requireApproval").get(0).asText());
        assertNull(lookup.hitlConfigOf(ungated));
    }

    @Test
    @DisplayName("id and version parsing")
    void parsing() {
        assertEquals(LLM, ActingAgentResources.idOf("eddi://ai.labs.llm/llmstore/llms/" + LLM + "?version=4"));
        assertEquals(LLM, ActingAgentResources.idOf("eddi://ai.labs.llm/llmstore/llms/" + LLM));
        // PostgreSQL mints UUIDs: both shapes a backend produces must resolve, or every
        // id on a PostgreSQL deployment is silently unprotected.
        String uuid = "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
        assertEquals(uuid, ActingAgentResources.idOf("eddi://ai.labs.llm/llmstore/llms/" + uuid + "?version=2"));
        assertEquals(uuid, ActingAgentResources.idOf("eddi://ai.labs.llm/llmstore/llms/" + uuid));
        assertNull(ActingAgentResources.versionOf("eddi://x/y/" + LLM + "?version=99999999999999999999"), "an overflowing digit run is no version");
        assertNull(ActingAgentResources.idOf("eddi://ai.labs.llm/llmstore/llms/not-an-id"));
        assertNull(ActingAgentResources.idOf(null));
        assertEquals(4, ActingAgentResources.versionOf("eddi://x/y/" + LLM + "?version=4"));
        assertNull(ActingAgentResources.versionOf("eddi://x/y/" + LLM));
        assertNull(ActingAgentResources.versionOf(null));
    }
}
