/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.AgentCascadeService.CascadeException;
import ai.labs.eddi.configs.agents.model.AgentCascadeRequest.ResourceMapping;
import ai.labs.eddi.configs.agents.model.AgentCascadeResult;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCascadeServiceTest {

    // Hex ids of 18+ characters: shorter or non-hex ids make extractResourceId
    // answer null, which silently turns every verify(never()...) here vacuous.
    private static final String AGENT_ID = "aabbccddee1122334455";
    private static final String WF1_ID = "ff00112233445566aa77";
    private static final String WF2_ID = "bb99887766554433cc22";
    private static final String LLM_ID = "112233445566778899aabbcc";
    private static final String RULES_ID = "99887766554433221100aabb";
    private static final String RAG_ID = "0123456789abcdef01234567";

    private static final String WF1_V1 = "eddi://ai.labs.workflow/workflowstore/workflows/" + WF1_ID + "?version=1";
    private static final String WF1_V2 = "eddi://ai.labs.workflow/workflowstore/workflows/" + WF1_ID + "?version=2";
    private static final String WF2_V1 = "eddi://ai.labs.workflow/workflowstore/workflows/" + WF2_ID + "?version=1";
    private static final String LLM_V1 = "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=1";
    private static final String LLM_V2 = "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=2";
    private static final String RULES_V1 = "eddi://ai.labs.rules/rulestore/rulesets/" + RULES_ID + "?version=1";
    private static final String RULES_V2 = "eddi://ai.labs.rules/rulestore/rulesets/" + RULES_ID + "?version=2";

    private IRestInterfaceFactory factory;
    private IRestAgentStore agentStore;
    private IRestWorkflowStore workflowStore;
    private IRestAgentAdministration agentAdmin;
    private AgentCascadeService service;

    @BeforeEach
    void setUp() throws Exception {
        factory = mock(IRestInterfaceFactory.class);
        agentStore = mock(IRestAgentStore.class);
        workflowStore = mock(IRestWorkflowStore.class);
        agentAdmin = mock(IRestAgentAdministration.class);
        when(factory.get(IRestAgentStore.class)).thenReturn(agentStore);
        when(factory.get(IRestWorkflowStore.class)).thenReturn(workflowStore);
        when(agentStore.getCurrentVersion(AGENT_ID)).thenReturn(1);
        service = new AgentCascadeService(factory, agentAdmin);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static WorkflowConfiguration workflow(String... stepUris) {
        var steps = new ArrayList<WorkflowConfiguration.WorkflowStep>();
        for (String uri : stepUris) {
            var step = new WorkflowConfiguration.WorkflowStep();
            step.setType(URI.create(uri.substring(0, uri.indexOf('/', "eddi://".length()))));
            step.setConfig(new HashMap<>(Map.of("uri", uri)));
            steps.add(step);
        }
        var config = new WorkflowConfiguration();
        config.setWorkflowSteps(steps);
        return config;
    }

    private AgentConfiguration agentReferencing(String... workflowUris) throws Exception {
        var agent = new AgentConfiguration();
        var uris = new ArrayList<URI>();
        for (String uri : workflowUris) {
            uris.add(URI.create(uri));
        }
        agent.setWorkflows(uris);
        when(agentStore.readAgent(AGENT_ID, 1)).thenReturn(agent);
        return agent;
    }

    private void workflowUpdateAnswers(String workflowId, int fromVersion) {
        when(workflowStore.updateWorkflow(eq(workflowId), eq(fromVersion), any()))
                .thenReturn(Response.ok()
                        .header("Location", "eddi://ai.labs.workflow/workflowstore/workflows/" + workflowId + "?version=" + (fromVersion + 1))
                        .build());
    }

    private void agentUpdateAnswers(int newVersion) {
        when(agentStore.updateAgent(eq(AGENT_ID), eq(1), any(), anyBoolean()))
                .thenReturn(
                        Response.ok().header("Location", "eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID + "?version=" + newVersion).build());
    }

    private static ResourceMapping map(String oldUri, String newUri) {
        return new ResourceMapping(oldUri, newUri);
    }

    private AgentCascadeResult cascade(List<ResourceMapping> mappings) {
        return service.cascade(AGENT_ID, 1, mappings, false, Deployment.Environment.production, false);
    }

    // ── what it does ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("applying mappings")
    class Applying {

        @Test
        @DisplayName("a step mapping rewrites the step, saving the workflow once and the agent once")
        void stepMapping() throws Exception {
            agentReferencing(WF1_V1);
            var wf = workflow(LLM_V1, RULES_V1);
            when(workflowStore.readWorkflow(WF1_ID, 1)).thenReturn(wf);
            workflowUpdateAnswers(WF1_ID, 1);
            agentUpdateAnswers(2);

            var result = cascade(List.of(map(LLM_V1, LLM_V2)));

            assertEquals(2, result.newAgentVersion());
            assertEquals(1, result.previousAgentVersion());
            assertEquals(1, result.mappingsApplied());
            assertEquals(1, result.workflows().size());
            var change = result.workflows().get(0);
            assertEquals(WF1_ID, change.workflowId());
            assertEquals(1, change.previousVersion());
            assertEquals(2, change.newVersion());
            assertTrue(change.stepsUpdated());
            assertFalse(change.repointed());
            // The step that was named moved; the one that was not stayed.
            assertEquals(LLM_V2, wf.getWorkflowSteps().get(0).getConfig().get("uri"));
            assertEquals(RULES_V1, wf.getWorkflowSteps().get(1).getConfig().get("uri"));
            verify(workflowStore, times(1)).updateWorkflow(eq(WF1_ID), eq(1), any());
            ArgumentCaptor<AgentConfiguration> saved = ArgumentCaptor.forClass(AgentConfiguration.class);
            verify(agentStore, times(1)).updateAgent(eq(AGENT_ID), eq(1), saved.capture(), eq(false));
            assertEquals(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WF1_ID + "?version=2")),
                    saved.getValue().getWorkflows());
        }

        @Test
        @DisplayName("several mappings hitting one workflow still save it once")
        void severalMappingsOneWorkflow() throws Exception {
            agentReferencing(WF1_V1);
            when(workflowStore.readWorkflow(WF1_ID, 1)).thenReturn(workflow(LLM_V1, RULES_V1));
            workflowUpdateAnswers(WF1_ID, 1);
            agentUpdateAnswers(2);

            var result = cascade(List.of(map(LLM_V1, LLM_V2), map(RULES_V1, RULES_V2)));

            assertEquals(2, result.mappingsApplied());
            verify(workflowStore, times(1)).updateWorkflow(eq(WF1_ID), anyInt(), any());
            verify(agentStore, times(1)).updateAgent(eq(AGENT_ID), anyInt(), any(), anyBoolean());
        }

        @Test
        @DisplayName("only workflows that changed are written; the others keep their reference")
        void onlyChangedWorkflowsWritten() throws Exception {
            agentReferencing(WF1_V1, WF2_V1);
            when(workflowStore.readWorkflow(WF1_ID, 1)).thenReturn(workflow(LLM_V1));
            when(workflowStore.readWorkflow(WF2_ID, 1)).thenReturn(workflow(RULES_V1));
            workflowUpdateAnswers(WF1_ID, 1);
            agentUpdateAnswers(2);

            var result = cascade(List.of(map(LLM_V1, LLM_V2)));

            assertEquals(2, result.totalWorkflows());
            assertEquals(1, result.workflows().size());
            verify(workflowStore, never()).updateWorkflow(eq(WF2_ID), anyInt(), any());
            ArgumentCaptor<AgentConfiguration> saved = ArgumentCaptor.forClass(AgentConfiguration.class);
            verify(agentStore).updateAgent(eq(AGENT_ID), eq(1), saved.capture(), anyBoolean());
            assertEquals(URI.create(WF2_V1), saved.getValue().getWorkflows().get(1));
        }

        @Test
        @DisplayName("the knowledge-base shape: a workflow mapping, then step mappings applied to THAT version, written once")
        void workflowMappingThenStepMapping() throws Exception {
            // The operator patched the workflow (v1 -> v2, now with a rag step) and the LLM
            // config (v1 -> v2) separately. One cascade must land BOTH: the agent moves to
            // workflow v2, and v2's llm step moves to llm v2 — one new workflow version
            // (v3),
            // not a v3 rewritten from the stale v1 that would lose the rag step.
            agentReferencing(WF1_V1);
            String ragUri = "eddi://ai.labs.rag/ragstore/rags/" + RAG_ID + "?version=1";
            var wfV2 = workflow(LLM_V1, ragUri);
            when(workflowStore.readWorkflow(WF1_ID, 2)).thenReturn(wfV2);
            workflowUpdateAnswers(WF1_ID, 2);
            agentUpdateAnswers(2);

            var result = cascade(List.of(map(LLM_V1, LLM_V2), map(WF1_V1, WF1_V2)));

            verify(workflowStore, never()).readWorkflow(WF1_ID, 1);
            verify(workflowStore, times(1)).updateWorkflow(eq(WF1_ID), eq(2), any());
            assertEquals(LLM_V2, wfV2.getWorkflowSteps().get(0).getConfig().get("uri"));
            assertEquals(ragUri, wfV2.getWorkflowSteps().get(1).getConfig().get("uri"), "the rag step the patch added must survive");
            var change = result.workflows().get(0);
            assertEquals(1, change.previousVersion());
            assertEquals(3, change.newVersion());
            assertTrue(change.repointed());
            assertTrue(change.stepsUpdated());
            assertEquals(2, result.mappingsApplied());
        }

        @Test
        @DisplayName("a workflow mapping alone repoints the agent and writes no workflow")
        void workflowMappingAlone() throws Exception {
            agentReferencing(WF1_V1);
            when(workflowStore.readWorkflow(WF1_ID, 2)).thenReturn(workflow(LLM_V2));
            agentUpdateAnswers(2);

            var result = cascade(List.of(map(WF1_V1, WF1_V2)));

            verify(workflowStore, never()).updateWorkflow(any(), anyInt(), any());
            assertEquals(2, result.newAgentVersion());
            assertTrue(result.workflows().get(0).repointed());
            assertFalse(result.workflows().get(0).stepsUpdated());
            assertEquals(2, result.workflows().get(0).newVersion());
        }

        @Test
        @DisplayName("compatible is passed to the agent store and reported")
        void compatiblePassedThrough() throws Exception {
            agentReferencing(WF1_V1);
            when(workflowStore.readWorkflow(WF1_ID, 1)).thenReturn(workflow(LLM_V1));
            workflowUpdateAnswers(WF1_ID, 1);
            agentUpdateAnswers(2);

            var result = service.cascade(AGENT_ID, 1, List.of(map(LLM_V1, LLM_V2)), false, Deployment.Environment.production, true);

            verify(agentStore).updateAgent(eq(AGENT_ID), eq(1), any(), eq(true));
            assertTrue(result.compatibleWithPreviousVersion());
        }
    }

    // ── what it refuses, before writing anything ────────────────────────────

    @Nested
    @DisplayName("refusals write nothing")
    class Refusals {

        private void assertNothingWritten() {
            verify(workflowStore, never()).updateWorkflow(any(), anyInt(), any());
            verify(agentStore, never()).updateAgent(any(), anyInt(), any(), anyBoolean());
            verify(agentAdmin, never()).deployAgent(any(), any(), any(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("an oldUri the agent does not hold is a 400-class refusal, even when another mapping matches")
        void unknownOldUri() throws Exception {
            agentReferencing(WF1_V1);
            when(workflowStore.readWorkflow(WF1_ID, 1)).thenReturn(workflow(LLM_V1));

            // The first pair WOULD match. Applying it and ignoring the second is the
            // half-change this refuses.
            var ex = assertThrows(CascadeException.class, () -> cascade(List.of(map(LLM_V1, LLM_V2), map(RULES_V1, RULES_V2))));

            assertEquals(CascadeException.Kind.BAD_REQUEST, ex.kind());
            assertTrue(ex.getMessage().contains(RULES_V1), ex.getMessage());
            assertNothingWritten();
        }

        @Test
        @DisplayName("a stale agent version is a conflict, detected before any workflow is read or written")
        void staleAgentVersion() throws Exception {
            when(agentStore.getCurrentVersion(AGENT_ID)).thenReturn(3);

            var ex = assertThrows(CascadeException.class, () -> cascade(List.of(map(LLM_V1, LLM_V2))));

            assertEquals(CascadeException.Kind.CONFLICT, ex.kind());
            verify(agentStore, never()).readAgent(any(), anyInt());
            verify(workflowStore, never()).readWorkflow(any(), anyInt());
            assertNothingWritten();
        }

        @Test
        @DisplayName("an unknown agent is a not-found")
        void agentNotFound() throws Exception {
            when(agentStore.readAgent(AGENT_ID, 1)).thenReturn(null);

            var ex = assertThrows(CascadeException.class, () -> cascade(List.of(map(LLM_V1, LLM_V2))));

            assertEquals(CascadeException.Kind.NOT_FOUND, ex.kind());
            assertNothingWritten();
        }

        @Test
        @DisplayName("a mapping across resource types is refused: a step cannot be re-aimed at a different kind of document")
        void differentResourceType() throws Exception {
            agentReferencing(WF1_V1);
            when(workflowStore.readWorkflow(WF1_ID, 1)).thenReturn(workflow(LLM_V1));

            var ex = assertThrows(CascadeException.class, () -> cascade(List.of(map(LLM_V1, RULES_V2))));

            assertEquals(CascadeException.Kind.BAD_REQUEST, ex.kind());
            assertTrue(ex.getMessage().contains("same kind"), ex.getMessage());
            verify(agentStore, never()).readAgent(any(), anyInt());
            assertNothingWritten();
        }

        @Test
        @DisplayName("an agent workflow reference cannot be mapped to a different store's document")
        void workflowReferenceToOtherType() throws Exception {
            var ex = assertThrows(CascadeException.class, () -> cascade(List.of(map(WF1_V1, LLM_V2))));

            assertEquals(CascadeException.Kind.BAD_REQUEST, ex.kind());
            assertNothingWritten();
        }

        @Test
        @DisplayName("both URIs must carry a version")
        void versionsRequired() throws Exception {
            String versionless = "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID;
            assertEquals(CascadeException.Kind.BAD_REQUEST,
                    assertThrows(CascadeException.class, () -> cascade(List.of(map(versionless, LLM_V2)))).kind());
            assertEquals(CascadeException.Kind.BAD_REQUEST,
                    assertThrows(CascadeException.class, () -> cascade(List.of(map(LLM_V1, versionless)))).kind());
            // A version of 0 and a non-numeric one are not versions either.
            assertEquals(CascadeException.Kind.BAD_REQUEST, assertThrows(CascadeException.class,
                    () -> cascade(List.of(map(LLM_V1, "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=0")))).kind());
            assertEquals(CascadeException.Kind.BAD_REQUEST, assertThrows(CascadeException.class,
                    () -> cascade(List.of(map(LLM_V1, "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=abc")))).kind());
            assertNothingWritten();
        }

        @Test
        @DisplayName("only eddi:// resource URIs are accepted, with nothing smuggled in the query")
        void onlyPlainEddiUris() throws Exception {
            for (String bad : List.of("https://evil.example/llmstore/llms/" + LLM_ID + "?version=2",
                    "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=2&hitlConfig=none",
                    "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=2#x", "eddi://ai.labs.llm/llmstore/llms/ ?version=2",
                    "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=2\n")) {
                var ex = assertThrows(CascadeException.class, () -> cascade(List.of(map(LLM_V1, bad))), bad);
                assertEquals(CascadeException.Kind.BAD_REQUEST, ex.kind(), bad);
            }
            assertNothingWritten();
        }

        @Test
        @DisplayName("empty, null and incomplete mapping lists are refused")
        void emptyMappings() {
            assertThrows(CascadeException.class, () -> cascade(List.of()));
            assertThrows(CascadeException.class, () -> cascade(null));
            assertThrows(CascadeException.class, () -> cascade(List.of(map(null, LLM_V2))));
            assertThrows(CascadeException.class, () -> cascade(List.of(map(LLM_V1, " "))));
            assertThrows(CascadeException.class, () -> cascade(List.of(map(LLM_V1, LLM_V1))));
            assertNothingWritten();
        }

        @Test
        @DisplayName("one oldUri with two different targets is ambiguous")
        void ambiguousMapping() {
            assertThrows(CascadeException.class, () -> cascade(List.of(map(LLM_V1, LLM_V2),
                    map(LLM_V1, "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=3"))));
            assertNothingWritten();
        }
    }

    // ── the security property ───────────────────────────────────────────────

    @Nested
    @DisplayName("what a cascade can change")
    class WhatItCanChange {

        private final ObjectMapper mapper = new ObjectMapper();

        private AgentConfiguration richAgent() throws Exception {
            var agent = agentReferencing(WF1_V1);
            agent.setDescription("Support agent");
            agent.setA2aEnabled(true);
            agent.setCompatibilityGeneration(4);
            agent.setEnableMemoryTools(true);
            var security = new AgentConfiguration.SecurityConfig();
            security.setSignInterAgentMessages(true);
            agent.setSecurity(security);
            var conversationReview = new AgentConfiguration.ConversationReview();
            conversationReview.setEnabled(true);
            conversationReview.setNotice("recorded");
            agent.setConversationReview(conversationReview);
            // The gate. If a cascade could touch ANY of this, the approval mechanism
            // reviewing the operator's writes could be switched off by one approved call.
            var tool = new ToolApprovalsConfig();
            tool.setRequireApproval(List.of("http.post:*", "http.put:*"));
            tool.setExempt(List.of("http.get:*"));
            tool.setMaxPausesPerTurn(3);
            var hitl = new AgentConfiguration.HitlConfig();
            hitl.setTimeoutPolicy(HitlTimeoutPolicy.WAIT_INDEFINITELY);
            hitl.setApprovalTimeout("PT1H");
            hitl.setPauseReason("operator write");
            hitl.setToolApprovals(tool);
            agent.setHitlConfig(hitl);
            return agent;
        }

        @Test
        @DisplayName("the saved agent differs from the stored one in its workflow list and nowhere else — hitlConfig above all")
        void onlyTheWorkflowListChanges() throws Exception {
            var agent = richAgent();
            JsonNode before = mapper.valueToTree(agent);
            assertTrue(before.hasNonNull("hitlConfig"), "fixture must actually carry a gate");
            when(workflowStore.readWorkflow(WF1_ID, 1)).thenReturn(workflow(LLM_V1));
            workflowUpdateAnswers(WF1_ID, 1);
            agentUpdateAnswers(2);

            cascade(List.of(map(LLM_V1, LLM_V2)));

            ArgumentCaptor<AgentConfiguration> saved = ArgumentCaptor.forClass(AgentConfiguration.class);
            verify(agentStore).updateAgent(eq(AGENT_ID), eq(1), saved.capture(), anyBoolean());
            JsonNode after = mapper.valueToTree(saved.getValue());

            assertTrue(before.get("workflows").toString().contains("version=1"));
            assertTrue(after.get("workflows").toString().contains("version=2"), "the workflow reference must have moved");
            ObjectNode beforeRest = ((ObjectNode) before).deepCopy();
            ObjectNode afterRest = ((ObjectNode) after).deepCopy();
            beforeRest.remove("workflows");
            afterRest.remove("workflows");
            assertEquals(beforeRest, afterRest, "a cascade changed an agent field other than the workflow list");
            assertEquals(before.get("hitlConfig"), after.get("hitlConfig"));
        }

        @Test
        @DisplayName("the same holds when the agent is only repointed at a newer workflow")
        void onlyTheWorkflowListChangesOnRepoint() throws Exception {
            var agent = richAgent();
            JsonNode before = mapper.valueToTree(agent);
            when(workflowStore.readWorkflow(WF1_ID, 2)).thenReturn(workflow(LLM_V1));
            agentUpdateAnswers(2);

            cascade(List.of(map(WF1_V1, WF1_V2)));

            ArgumentCaptor<AgentConfiguration> saved = ArgumentCaptor.forClass(AgentConfiguration.class);
            verify(agentStore).updateAgent(eq(AGENT_ID), eq(1), saved.capture(), anyBoolean());
            ObjectNode beforeRest = ((ObjectNode) before).deepCopy();
            ObjectNode afterRest = ((ObjectNode) mapper.valueToTree(saved.getValue())).deepCopy();
            beforeRest.remove("workflows");
            afterRest.remove("workflows");
            assertEquals(beforeRest, afterRest);
        }

        @Test
        @DisplayName("workflow documents change only in step resource references")
        void workflowsChangeOnlyInStepUris() throws Exception {
            agentReferencing(WF1_V1);
            var wf = workflow(LLM_V1, RULES_V1);
            wf.getWorkflowSteps().get(0).getConfig().put("temperature", "0.2");
            wf.getWorkflowSteps().get(0).getExtensions().put("keep", "me");
            JsonNode before = mapper.valueToTree(wf);
            when(workflowStore.readWorkflow(WF1_ID, 1)).thenReturn(wf);
            workflowUpdateAnswers(WF1_ID, 1);
            agentUpdateAnswers(2);

            cascade(List.of(map(LLM_V1, LLM_V2)));

            JsonNode after = mapper.valueToTree(wf);
            // Normalise the one reference that is meant to move, then everything must
            // match.
            String normalised = after.toString().replace(LLM_V2, LLM_V1);
            assertEquals(before.toString(), normalised);
        }
    }

    // ── redeploy ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("redeploy")
    class Redeploy {

        private void cascadedAgent() throws Exception {
            agentReferencing(WF1_V1);
            when(workflowStore.readWorkflow(WF1_ID, 1)).thenReturn(workflow(LLM_V1));
            workflowUpdateAnswers(WF1_ID, 1);
            agentUpdateAnswers(2);
        }

        private Response deployed(String status, String error) {
            var body = new HashMap<String, Object>();
            body.put("status", status);
            if (error != null) {
                body.put("error", error);
            }
            return Response.ok(body).build();
        }

        @Test
        @DisplayName("goes through the normal deploy endpoint, for the NEW agent version, waiting for the result")
        void deploysNewVersion() throws Exception {
            cascadedAgent();
            when(agentAdmin.deployAgent(Deployment.Environment.test, AGENT_ID, 2, true, true)).thenReturn(deployed("READY", null));

            var result = service.cascade(AGENT_ID, 1, List.of(map(LLM_V1, LLM_V2)), true, Deployment.Environment.test, false);

            verify(agentAdmin).deployAgent(Deployment.Environment.test, AGENT_ID, 2, true, true);
            assertNotNull(result.deployment());
            assertTrue(result.deployment().redeployed());
            assertEquals("READY", result.deployment().status());
            assertEquals("test", result.deployment().environment());
            assertTrue(result.deployment().previousVersionStillDeployed());
        }

        @Test
        @DisplayName("a deploy that answers 200 with an error body is not reported as redeployed")
        void failedDeployInBody() throws Exception {
            cascadedAgent();
            when(agentAdmin.deployAgent(any(), eq(AGENT_ID), eq(2), anyBoolean(), anyBoolean())).thenReturn(deployed("ERROR", "Deployment failed."));

            var result = service.cascade(AGENT_ID, 1, List.of(map(LLM_V1, LLM_V2)), true, Deployment.Environment.production, false);

            assertFalse(result.deployment().redeployed());
            assertEquals("Deployment failed.", result.deployment().error());
            // The documents are written either way; the result must say so.
            assertEquals(2, result.newAgentVersion());
        }

        @Test
        @DisplayName("a refused deploy (quota, vault grants) is reported, not thrown — the documents are already written")
        void refusedDeploy() throws Exception {
            cascadedAgent();
            when(agentAdmin.deployAgent(any(), eq(AGENT_ID), eq(2), anyBoolean(), anyBoolean()))
                    .thenThrow(new ForbiddenException("agent is not granted vault key 'k'"));

            var result = service.cascade(AGENT_ID, 1, List.of(map(LLM_V1, LLM_V2)), true, Deployment.Environment.production, false);

            assertFalse(result.deployment().redeployed());
            assertTrue(result.deployment().error().contains("vault key"), result.deployment().error());
            assertNull(result.deployment().environment());
        }

        @Test
        @DisplayName("no redeploy is attempted unless asked for")
        void notRequested() throws Exception {
            cascadedAgent();

            var result = cascade(List.of(map(LLM_V1, LLM_V2)));

            verify(agentAdmin, never()).deployAgent(any(), any(), any(), anyBoolean(), anyBoolean());
            assertNull(result.deployment());
            assertFalse(result.toMap().containsKey("redeployed"));
        }

        @Test
        @DisplayName("toMap keeps the flat shape the MCP tool has always returned")
        void legacyShape() throws Exception {
            cascadedAgent();
            when(agentAdmin.deployAgent(any(), eq(AGENT_ID), eq(2), anyBoolean(), anyBoolean())).thenReturn(deployed("READY", null));

            Map<String, Object> flat = service.cascade(AGENT_ID, 1, List.of(map(LLM_V1, LLM_V2)), true, Deployment.Environment.production, false)
                    .toMap();

            assertEquals(AGENT_ID, flat.get("agentId"));
            assertEquals(1, flat.get("previousAgentVersion"));
            assertEquals(2, flat.get("newAgentVersion"));
            assertEquals(1, flat.get("updatedWorkflows"));
            assertEquals(1, flat.get("totalWorkflows"));
            assertEquals(1, flat.get("mappingsApplied"));
            assertEquals(false, flat.get("compatibleWithPreviousVersion"));
            assertEquals(true, flat.get("redeployed"));
            assertEquals("production", flat.get("environment"));
            assertEquals("READY", flat.get("deploymentStatus"));
            assertEquals(true, flat.get("previousVersionStillDeployed"));
            assertTrue(flat.containsKey("workflows"));
        }
    }
}
