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
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.security.CallerTokenAuthorPolicy;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.utils.RestUtilities;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.ForbiddenException;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review fixes on the upgrade path: which target a shared resource is written
 * to, when a 409 may be retried, and what a direct create checks before it
 * stores anything.
 */
@DisplayName("UpgradeExecutor — review fixes")
class UpgradeExecutorReviewFixesTest {

    private static final String AGENT_ID = "aabbccddeeff112233445566";
    private static final String WF_ID = "bbccddeeff112233445566aa";
    private static final String WF2_ID = "bbccddeeff112233445566ab";
    private static final String LLM_ID = "ccddeeff112233445566aabb";
    private static final String LLM2_ID = "ccddeeff112233445566aabc";
    private static final String FOREIGN_ID = "ddeeff112233445566aabbcc";

    private IRestAgentStore agentStore;
    private IRestWorkflowStore workflowStore;
    private IDocumentDescriptorStore descriptorStore;
    private StructuralMatcher structuralMatcher;
    private IJsonSerialization jsonSerialization;
    private IRestLlmStore llmStore;
    private ResourceAccessGuard guard;
    private UpgradeExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        agentStore = mock(IRestAgentStore.class);
        workflowStore = mock(IRestWorkflowStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        structuralMatcher = mock(StructuralMatcher.class);
        jsonSerialization = mock(IJsonSerialization.class);
        llmStore = mock(IRestLlmStore.class);
        guard = mock(ResourceAccessGuard.class);

        executor = new UpgradeExecutor(agentStore, workflowStore, mock(IRestPromptSnippetStore.class),
                jsonSerialization, structuralMatcher, descriptorStore, mock(BackupMetrics.class), guard);

        lenient().when(jsonSerialization.deserialize(anyString(), eq(LlmConfiguration.class)))
                .thenReturn(new LlmConfiguration(List.of()));
        lenient().when(llmStore.updateLlm(anyString(), anyInt(), any())).thenReturn(Response.ok().build());
        lenient().when(descriptorStore.readDescriptor(anyString(), anyInt()))
                .thenAnswer(invocation -> descriptorAt(invocation.getArgument(0), invocation.getArgument(1)));
        lenient().when(descriptorStore.readCurrentDescriptor(AGENT_ID)).thenReturn(descriptorAt(AGENT_ID, 1));
    }

    // ==================== #8: a shared source resource ====================

    @Test
    @DisplayName("a resource two workflows share is written to each workflow's own copy")
    void sharedResourceIsWrittenPerWorkflow() throws Exception {
        givenAgentWith(List.of(workflowUri(WF_ID), workflowUri(WF2_ID)));
        when(workflowStore.readWorkflow(WF_ID, 1)).thenReturn(llmWorkflow(LLM_ID));
        when(workflowStore.readWorkflow(WF2_ID, 1)).thenReturn(llmWorkflow(LLM2_ID));
        when(workflowStore.updateWorkflow(anyString(), eq(1), any())).thenReturn(Response.ok().build());

        // On the source both workflows name the same LLM; the target has a copy per
        // workflow, so the matcher emits one row per workflow with its own target.
        WorkflowConfiguration sourceConfig = llmWorkflow("eeff112233445566aabbccdd");
        String key = WorkflowExtensions.scan(sourceConfig).getFirst().key();
        var llm = new ExtensionSourceData("src-llm", "LLM", "langchain", "eddi://ai.labs.llm", "{\"model\":\"gpt-4\"}");
        var source = source(List.of(
                new WorkflowSourceData("src-wf", "WF", 0, sourceConfig, Map.of(key, llm)),
                new WorkflowSourceData("src-wf2", "WF2", 1, llmWorkflow("eeff112233445566aabbccdd"), Map.of(key, llm))));
        givenPreview(List.of(
                new ResourceDiff("src-agent", "agent", "Agent", DiffAction.SKIP, AGENT_ID, 1, "targetAgent", null, null, -1),
                new ResourceDiff("src-wf", "workflow", "WF", DiffAction.SKIP, WF_ID, 1, "position", null, null, 0),
                new ResourceDiff("src-llm", "langchain", "LLM", DiffAction.UPDATE, LLM_ID, 1, "type", "{}", "{}", 0),
                new ResourceDiff("src-wf2", "workflow", "WF2", DiffAction.SKIP, WF2_ID, 1, "position", null, null, 1),
                new ResourceDiff("src-llm", "langchain", "LLM", DiffAction.UPDATE, LLM2_ID, 1, "type", "{}", "{}", 1)));

        UpgradeResult result = withStoresInCdi(Map.of(), () -> executor.executeUpgrade(source, AGENT_ID, null, null));

        assertTrue(result.failures().isEmpty(), "got: " + result.failures());
        verify(llmStore, times(1)).updateLlm(eq(LLM_ID), eq(1), any());
        verify(llmStore, times(1)).updateLlm(eq(LLM2_ID), eq(1), any());
    }

    // ==================== 409 retry ====================

    @Test
    @DisplayName("a 409 for a version whose descriptor moved too is an edit, not retried over")
    void conflictWithMovedDescriptorIsNotOverwritten() throws Exception {
        givenAgentWith(List.of(workflowUri(WF_ID)));
        when(workflowStore.readWorkflow(WF_ID, 1)).thenReturn(llmWorkflow(LLM_ID));
        when(workflowStore.updateWorkflow(anyString(), eq(1), any())).thenReturn(Response.ok().build());
        when(llmStore.updateLlm(eq(LLM_ID), eq(1), any()))
                .thenThrow(RestUtilities.createConflictException("eddi://ai.labs.llm/llmstore/llms/", resourceIdAt(LLM_ID, 2)));
        // Somebody saved v2 through the Manager after the preview: its descriptor
        // names v2, exactly as a normal save leaves it.
        when(descriptorStore.getCurrentResourceId(LLM_ID)).thenReturn(resourceIdAt(LLM_ID, 2));

        WorkflowConfiguration sourceConfig = llmWorkflow("eeff112233445566aabbccdd");
        String key = WorkflowExtensions.scan(sourceConfig).getFirst().key();
        var source = source(List.of(new WorkflowSourceData("src-wf", "WF", 0, sourceConfig,
                Map.of(key, new ExtensionSourceData("src-llm", "LLM", "langchain", "eddi://ai.labs.llm", "{\"model\":\"gpt-4\"}")))));
        givenPreview(List.of(
                new ResourceDiff("src-agent", "agent", "Agent", DiffAction.SKIP, AGENT_ID, 1, "targetAgent", null, null, -1),
                new ResourceDiff("src-wf", "workflow", "WF", DiffAction.SKIP, WF_ID, 1, "position", null, null, 0),
                new ResourceDiff("src-llm", "langchain", "LLM", DiffAction.UPDATE, LLM_ID, 1, "type", "{}", "{}", 0)));

        UpgradeResult result = withStoresInCdi(Map.of(), () -> executor.executeUpgrade(source, AGENT_ID, null, null));

        verify(llmStore, never()).updateLlm(eq(LLM_ID), eq(2), any());
        assertTrue(result.failures().stream().anyMatch(f -> f.reason().contains("changed on this instance")),
                "got: " + result.failures());
    }

    @Test
    @DisplayName("a 409 caused by this run's own earlier write of the same target is retried, not reported as a local edit")
    void conflictFromThisRunsOwnWriteIsRetried() throws Exception {
        givenAgentWith(List.of(workflowUri(WF_ID), workflowUri(WF2_ID)));
        when(workflowStore.readWorkflow(WF_ID, 1)).thenReturn(llmWorkflow(LLM_ID));
        when(workflowStore.readWorkflow(WF2_ID, 1)).thenReturn(llmWorkflow(LLM_ID));
        when(workflowStore.updateWorkflow(anyString(), eq(1), any())).thenReturn(Response.ok().build());
        // Both target workflows name the same LLM. The first write moves it to v2 and
        // this run bumps its descriptor to v2; the second workflow's row was previewed
        // at v1, so its write meets a 409 whose newer version is the run's own.
        when(llmStore.updateLlm(eq(LLM_ID), eq(1), any()))
                .thenReturn(Response.ok().build())
                .thenThrow(RestUtilities.createConflictException("eddi://ai.labs.llm/llmstore/llms/", resourceIdAt(LLM_ID, 2)));
        when(llmStore.updateLlm(eq(LLM_ID), eq(2), any())).thenReturn(Response.ok().build());
        when(descriptorStore.getCurrentResourceId(LLM_ID)).thenReturn(resourceIdAt(LLM_ID, 2));

        WorkflowConfiguration sourceConfig = llmWorkflow("eeff112233445566aabbccdd");
        String key = WorkflowExtensions.scan(sourceConfig).getFirst().key();
        var llm = new ExtensionSourceData("src-llm", "LLM", "langchain", "eddi://ai.labs.llm", "{\"model\":\"gpt-4\"}");
        var source = source(List.of(
                new WorkflowSourceData("src-wf", "WF", 0, sourceConfig, Map.of(key, llm)),
                new WorkflowSourceData("src-wf2", "WF2", 1, llmWorkflow("eeff112233445566aabbccdd"), Map.of(key, llm))));
        givenPreview(List.of(
                new ResourceDiff("src-agent", "agent", "Agent", DiffAction.SKIP, AGENT_ID, 1, "targetAgent", null, null, -1),
                new ResourceDiff("src-wf", "workflow", "WF", DiffAction.SKIP, WF_ID, 1, "position", null, null, 0),
                new ResourceDiff("src-llm", "langchain", "LLM", DiffAction.UPDATE, LLM_ID, 1, "type", "{}", "{}", 0),
                new ResourceDiff("src-wf2", "workflow", "WF2", DiffAction.SKIP, WF2_ID, 1, "position", null, null, 1),
                new ResourceDiff("src-llm", "langchain", "LLM", DiffAction.UPDATE, LLM_ID, 1, "type", "{}", "{}", 1)));

        UpgradeResult result = withStoresInCdi(Map.of(), () -> executor.executeUpgrade(source, AGENT_ID, null, null));

        assertTrue(result.failures().stream().noneMatch(f -> f.reason().contains("changed on this instance")),
                "got: " + result.failures());
        verify(llmStore, times(1)).updateLlm(eq(LLM_ID), eq(2), any());
    }

    // ==================== direct creates ====================

    @Test
    @DisplayName("a created extension is checked for ${caller:token} after decoding, and a RAG gets per-id storage")
    void createdExtensionIsCheckedDecodedAndRagIsolated() throws Exception {
        var policy = mock(CallerTokenAuthorPolicy.class);
        executor.useCallerTokenAuthorPolicy(policy);
        var rag = new RagConfiguration();
        rag.setStoreNamespace(KnowledgeBaseStorage.NAMESPACE_NAME);
        when(jsonSerialization.deserialize(eq("{\"rag\":1}"), eq(RagConfiguration.class))).thenReturn(rag);

        var ragStore = mock(IRagStore.class);
        when(ragStore.create(any())).thenReturn(resourceIdAt("ff112233445566aabbccddee", 1));
        var wfStore = mock(IWorkflowStore.class);
        when(wfStore.create(any())).thenReturn(resourceIdAt(WF2_ID, 1));

        var source = newWorkflowWithRag(ragWorkflow());
        UpgradeResult result = withStoresInCdi(Map.of(IRagStore.class, ragStore, IWorkflowStore.class, wfStore),
                () -> executor.executeUpgrade(source, AGENT_ID, null, null));

        assertTrue(result.failures().isEmpty(), "got: " + result.failures());
        // The decoded object, not the raw text a JSON escape could hide a reference in.
        verify(policy).requireMayAuthor(isA(RagConfiguration.class), eq("rag"));
        verify(policy, never()).requireMayAuthor(isA(String.class), anyString());
        var stored = ArgumentCaptor.forClass(RagConfiguration.class);
        verify(ragStore).create(stored.capture());
        assertEquals(KnowledgeBaseStorage.NAMESPACE_ID, stored.getValue().getStoreNamespace());
    }

    @Test
    @DisplayName("a new workflow naming a configuration the caller cannot read is refused before anything is written")
    void newWorkflowReferenceIsChecked() throws Exception {
        when(jsonSerialization.deserialize(eq("{\"rag\":1}"), eq(RagConfiguration.class))).thenReturn(new RagConfiguration());
        var ragStore = mock(IRagStore.class);
        var wfStore = mock(IWorkflowStore.class);
        doThrow(new ForbiddenException("no")).when(guard).requireAccess(eq(FOREIGN_ID), eq(AccessLevel.VIEW), anyString());

        // A legacy authority the engine still resolves and the backup scanner does not
        // recognise, so nothing repoints it.
        WorkflowConfiguration config = ragWorkflow();
        var legacy = new WorkflowConfiguration.WorkflowStep();
        legacy.setType(URI.create("eddi://ai.labs.httpcalls"));
        legacy.setConfig(new LinkedHashMap<>(Map.of("uri", "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/" + FOREIGN_ID + "?version=1")));
        config.getWorkflowSteps().add(legacy);

        var source = newWorkflowWithRag(config);
        UpgradeResult result = withStoresInCdi(Map.of(IRagStore.class, ragStore, IWorkflowStore.class, wfStore),
                () -> executor.executeUpgrade(source, AGENT_ID, null, null));

        verify(ragStore, never()).create(any());
        verify(wfStore, never()).create(any());
        assertTrue(result.failures().stream().anyMatch(f -> "workflow".equals(f.resourceType())), "got: " + result.failures());
    }

    @Test
    @DisplayName("an unrecognised reference sharing host and id with a recognised one is still access-checked")
    void paddedReferenceSharingAKeyWithARecognisedOneIsStillChecked() throws Exception {
        when(jsonSerialization.deserialize(eq("{\"rag\":1}"), eq(RagConfiguration.class))).thenReturn(new RagConfiguration());
        var ragStore = mock(IRagStore.class);
        var wfStore = mock(IWorkflowStore.class);
        doThrow(new ForbiddenException("no")).when(guard).requireAccess(eq("112233445566aabbccddeeff"), eq(AccessLevel.VIEW), anyString());

        // The same resource the recognised step names, written with leading whitespace:
        // the REST check trims it and counts it, the backup scanner does not recognise
        // it, so nothing repoints it and it would be stored as written.
        WorkflowConfiguration config = ragWorkflow();
        var padded = new WorkflowConfiguration.WorkflowStep();
        padded.setType(URI.create("eddi://ai.labs.rag"));
        padded.setConfig(new LinkedHashMap<>(Map.of("uri", " eddi://ai.labs.rag/ragstore/rags/112233445566aabbccddeeff?version=1")));
        config.getWorkflowSteps().add(padded);

        var source = newWorkflowWithRag(config);
        UpgradeResult result = withStoresInCdi(Map.of(IRagStore.class, ragStore, IWorkflowStore.class, wfStore),
                () -> executor.executeUpgrade(source, AGENT_ID, null, null));

        verify(ragStore, never()).create(any()); // refused before anything is written
        verify(wfStore, never()).create(any());
        assertTrue(result.failures().stream().anyMatch(f -> "workflow".equals(f.resourceType())), "got: " + result.failures());
    }

    // ==================== fixtures ====================

    private IResourceSource newWorkflowWithRag(WorkflowConfiguration config) throws Exception {
        givenAgentWith(new ArrayList<>());
        String key = WorkflowExtensions.scan(config).getFirst().key();
        when(jsonSerialization.serialize(config)).thenReturn("wf-json");
        // A fresh copy per call, like the real deserializer: the executor edits the
        // copy.
        when(jsonSerialization.deserialize("wf-json", WorkflowConfiguration.class))
                .thenAnswer(inv -> new ObjectMapper().convertValue(config, WorkflowConfiguration.class));
        var source = source(List.of(new WorkflowSourceData("src-wf", "WF", 0, config,
                Map.of(key, new ExtensionSourceData("src-rag", "KB", "rag", "eddi://ai.labs.rag", "{\"rag\":1}")))));
        givenPreview(List.of(
                new ResourceDiff("src-agent", "agent", "Agent", DiffAction.SKIP, AGENT_ID, 1, "targetAgent", null, null, -1),
                new ResourceDiff("src-wf", "workflow", "WF", DiffAction.CREATE, null, null, null, null, null, 0),
                new ResourceDiff("src-rag", "rag", "KB", DiffAction.CREATE, null, null, null, null, null, 0)));
        return source;
    }

    private static WorkflowConfiguration ragWorkflow() {
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.rag"));
        step.setConfig(new LinkedHashMap<>(Map.of("uri", "eddi://ai.labs.rag/ragstore/rags/112233445566aabbccddeeff?version=1")));
        var config = new WorkflowConfiguration();
        config.setWorkflowSteps(new ArrayList<>(List.of(step)));
        return config;
    }

    private static WorkflowConfiguration llmWorkflow(String llmId) {
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.llm"));
        step.setConfig(new LinkedHashMap<>(Map.of("uri", "eddi://ai.labs.llm/llmstore/llms/" + llmId + "?version=1")));
        var config = new WorkflowConfiguration();
        config.setWorkflowSteps(new ArrayList<>(List.of(step)));
        return config;
    }

    private static URI workflowUri(String id) {
        return URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + id + "?version=1");
    }

    private void givenAgentWith(List<URI> workflows) {
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(new ArrayList<>(workflows));
        when(agentStore.readAgent(AGENT_ID, 1)).thenReturn(agentConfig);
        lenient().when(agentStore.updateAgent(eq(AGENT_ID), eq(1), any(), any())).thenReturn(Response.ok().build());
    }

    private void givenPreview(List<ResourceDiff> diffs) {
        when(structuralMatcher.buildPreview(any(), eq(AGENT_ID), eq(true)))
                .thenReturn(new ImportPreview("src-agent", "Agent", AGENT_ID, "Agent", diffs));
    }

    private static IResourceSource source(List<WorkflowSourceData> workflows) {
        var source = mock(IResourceSource.class);
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(new ArrayList<>());
        when(source.readAgent()).thenReturn(new AgentSourceData("src-agent", "Agent", agentConfig));
        when(source.readSnippets()).thenReturn(List.of());
        when(source.readWorkflows()).thenReturn(workflows);
        return source;
    }

    private static IResourceId resourceIdAt(String id, int version) {
        return new IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    private static DocumentDescriptor descriptorAt(String resourceId, Integer version) {
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + resourceId + "?version=" + version));
        return descriptor;
    }

    /**
     * Runs {@code action} with CDI answering the LLM REST store and the given
     * in-process stores.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> T withStoresInCdi(Map<Class<?>, Object> stores, Supplier<T> action) {
        MockedStatic<CDI> cdiMock = Mockito.mockStatic(CDI.class);
        try (cdiMock) {
            var cdiInstance = mock(CDI.class);
            cdiMock.when(CDI::current).thenReturn(cdiInstance);
            var llmInstance = (Instance) mock(Instance.class);
            when(cdiInstance.select(IRestLlmStore.class)).thenReturn(llmInstance);
            when(llmInstance.get()).thenReturn(llmStore);
            for (Map.Entry<Class<?>, Object> store : stores.entrySet()) {
                var instance = (Instance) mock(Instance.class);
                when(cdiInstance.select(store.getKey())).thenReturn(instance);
                when(instance.get()).thenReturn(store.getValue());
            }
            return action.get();
        }
    }
}
