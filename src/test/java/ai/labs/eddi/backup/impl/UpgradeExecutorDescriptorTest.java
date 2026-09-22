/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource;
import ai.labs.eddi.backup.IResourceSource.AgentSourceData;
import ai.labs.eddi.backup.IResourceSource.ExtensionSourceData;
import ai.labs.eddi.backup.IResourceSource.WorkflowSourceData;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.backup.model.UpgradeResult;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two defects that made a live sync a one-shot operation, guarded at the
 * level they actually broke: which version the executor reads and writes, and
 * whether the version it wrote can afterwards be found.
 * <p>
 * Both were invisible to the existing suite because every one of its targets
 * sat at version 1 — the one version the broken code happened to answer
 * correctly.
 */
@DisplayName("UpgradeExecutor — version resolution and descriptor bookkeeping")
class UpgradeExecutorDescriptorTest {

    private static final String AGENT_ID = "aabbccddeeff112233445566";
    private static final String WF_ID = "bbccddeeff112233445566aa";
    private static final String LLM_ID = "ccddeeff112233445566aabb";

    private IRestAgentStore agentStore;
    private IRestWorkflowStore workflowStore;
    private IDocumentDescriptorStore descriptorStore;
    private StructuralMatcher structuralMatcher;
    private IJsonSerialization jsonSerialization;
    private IRestLlmStore llmStore;
    private UpgradeExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        agentStore = mock(IRestAgentStore.class);
        workflowStore = mock(IRestWorkflowStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        structuralMatcher = mock(StructuralMatcher.class);
        jsonSerialization = mock(IJsonSerialization.class);
        llmStore = mock(IRestLlmStore.class);

        executor = new UpgradeExecutor(agentStore, workflowStore, mock(IRestPromptSnippetStore.class),
                jsonSerialization, structuralMatcher, descriptorStore, mock(BackupMetrics.class),
                mock(ResourceAccessGuard.class));

        lenient().when(jsonSerialization.deserialize(anyString(), eq(LlmConfiguration.class)))
                .thenReturn(new LlmConfiguration(List.of()));
        lenient().when(llmStore.updateLlm(anyString(), anyInt(), any())).thenReturn(Response.ok().build());
    }

    @Test
    @DisplayName("reads and writes the version the target is actually at, not version 1")
    void writesAgainstTheCurrentVersion() throws Exception {
        // The target has been synced before, so everything sits at v3. The executor
        // used to ask for the current version with readDescriptor(id, null) — a call
        // the historized store always rejects — swallow the exception and fall back
        // to 1, which the store then refused to update.
        givenTargetAt(3);

        UpgradeResult result = withLlmStoreInCdi(() -> executor.executeUpgrade(sourceWithOneLlm(), AGENT_ID, null, null));

        assertTrue(result.failures().isEmpty(), "nothing should have failed, got: " + result.failures());
        verify(agentStore).readAgent(AGENT_ID, 3);
        verify(agentStore).updateAgent(eq(AGENT_ID), eq(3), any());
        verify(agentStore, never()).updateAgent(eq(AGENT_ID), eq(1), any());
        assertEquals(URI.create("eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID + "?version=4"),
                result.agentUri());
    }

    @Test
    @DisplayName("moves each written resource's descriptor onto the version it just wrote")
    void movesDescriptorsForward() throws Exception {
        givenTargetAt(3);

        withLlmStoreInCdi(() -> executor.executeUpgrade(sourceWithOneLlm(), AGENT_ID, null, null));

        // Without this the resource is written but nothing can find it: the deployment
        // reads the descriptor for the version it was asked for, and the next sync
        // reads the descriptor to learn where the target is.
        assertDescriptorMovedTo(LLM_ID, 2, 3);
        assertDescriptorMovedTo(WF_ID, 2, 3);
        assertDescriptorMovedTo(AGENT_ID, 3, 4);
    }

    @Test
    @DisplayName("a resource whose descriptor cannot be moved is reported, not counted as written")
    void undeployableResourceIsReported() throws Exception {
        givenTargetAt(3);
        // The write lands, the bookkeeping does not — the state the whole defect
        // produced, and the one that must never be reported as success.
        when(descriptorStore.readDescriptor(eq(LLM_ID), anyInt())).thenReturn(null);

        UpgradeResult result = withLlmStoreInCdi(() -> executor.executeUpgrade(sourceWithOneLlm(), AGENT_ID, null, null));

        assertFalse(result.failures().isEmpty(), "a resource nothing can load must be reported");
        assertTrue(result.failures().stream()
                .anyMatch(f -> LLM_ID.equals(f.sourceId()) || "langchain".equals(f.resourceType())),
                "the failure should name the resource, got: " + result.failures());
        assertTrue(result.failures().getFirst().reason().contains("descriptor"),
                "the reason should say what is wrong, got: " + result.failures().getFirst().reason());
    }

    // ==================== Fixtures ====================

    /**
     * A target whose agent is at {@code agentVersion} and whose workflow and LLM
     * are at version 2 — i.e. an agent that has been synced at least once already.
     */
    private void givenTargetAt(int agentVersion) throws Exception {
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(new ArrayList<>(List.of(
                URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WF_ID + "?version=2"))));
        when(agentStore.readAgent(AGENT_ID, agentVersion)).thenReturn(agentConfig);
        when(agentStore.updateAgent(eq(AGENT_ID), eq(agentVersion), any())).thenReturn(Response.ok().build());

        var targetWorkflow = new WorkflowConfiguration();
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.llm"));
        step.setConfig(new LinkedHashMap<>(Map.of(
                "uri", "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=2")));
        targetWorkflow.setWorkflowSteps(new ArrayList<>(List.of(step)));
        when(workflowStore.readWorkflow(WF_ID, 2)).thenReturn(targetWorkflow);
        when(workflowStore.updateWorkflow(eq(WF_ID), eq(2), any())).thenReturn(Response.ok().build());

        lenient().when(descriptorStore.readCurrentDescriptor(AGENT_ID))
                .thenReturn(descriptorAt(AGENT_ID, agentVersion));
        lenient().when(descriptorStore.readDescriptor(anyString(), anyInt()))
                .thenAnswer(invocation -> descriptorAt(invocation.getArgument(0), invocation.getArgument(1)));

        var diffs = List.of(
                new ResourceDiff("src-agent", "agent", "Agent", DiffAction.UPDATE, AGENT_ID, agentVersion,
                        "targetAgent", null, null, -1),
                new ResourceDiff("src-wf", "workflow", "Workflow", DiffAction.UPDATE, WF_ID, 2, "position",
                        null, null, 0),
                new ResourceDiff("src-llm", "langchain", "LLM", DiffAction.UPDATE, LLM_ID, 2, "type",
                        "{\"model\":\"gpt-4\"}", "{\"model\":\"gpt-3\"}", 0));
        when(structuralMatcher.buildPreview(any(), eq(AGENT_ID), eq(true)))
                .thenReturn(new ImportPreview("src-agent", "Agent", AGENT_ID, "Agent", diffs));
    }

    private IResourceSource sourceWithOneLlm() {
        var source = mock(IResourceSource.class);
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(new ArrayList<>());
        when(source.readAgent()).thenReturn(new AgentSourceData("src-agent", "Agent", agentConfig));
        when(source.readSnippets()).thenReturn(List.of());

        var sourceStep = new WorkflowConfiguration.WorkflowStep();
        sourceStep.setType(URI.create("eddi://ai.labs.llm"));
        sourceStep.setConfig(new LinkedHashMap<>(Map.of(
                "uri", "eddi://ai.labs.llm/llmstore/llms/ddeeff112233445566aabbcc?version=1")));
        var sourceWorkflow = new WorkflowConfiguration();
        sourceWorkflow.setWorkflowSteps(new ArrayList<>(List.of(sourceStep)));

        when(source.readWorkflows()).thenReturn(List.of(new WorkflowSourceData(
                "src-wf", "Workflow", 0, sourceWorkflow,
                Map.of("eddi://ai.labs.llm#0/config",
                        new ExtensionSourceData("src-llm", "LLM", "langchain", "eddi://ai.labs.llm",
                                "{\"model\":\"gpt-4\"}")))));
        return source;
    }

    private static DocumentDescriptor descriptorAt(String resourceId, Integer version) {
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + resourceId + "?version=" + version));
        return descriptor;
    }

    /**
     * Asserts the descriptor of {@code resourceId} was rewritten from {@code from}
     * to {@code to}.
     */
    private void assertDescriptorMovedTo(String resourceId, int from, int to) throws Exception {
        var captor = ArgumentCaptor.forClass(DocumentDescriptor.class);
        verify(descriptorStore).updateDescriptor(eq(resourceId), eq(from), captor.capture());
        assertTrue(captor.getValue().getResource().toString().endsWith("?version=" + to),
                "descriptor of " + resourceId + " should name v" + to + ", got "
                        + captor.getValue().getResource());
    }

    /**
     * Runs {@code action} with {@code CDI.current().select(IRestLlmStore.class)}
     * answering the mock — the executor resolves extension stores that way, and a
     * unit test has no container.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> T withLlmStoreInCdi(Supplier<T> action) {
        MockedStatic<CDI> cdiMock = Mockito.mockStatic(CDI.class);
        try (cdiMock) {
            var cdiInstance = Mockito.mock(CDI.class);
            cdiMock.when(CDI::current).thenReturn(cdiInstance);
            var instance = (Instance<IRestLlmStore>) Mockito.mock(Instance.class);
            when(cdiInstance.select(IRestLlmStore.class)).thenReturn(instance);
            when(instance.get()).thenReturn(llmStore);
            return action.get();
        }
    }
}
