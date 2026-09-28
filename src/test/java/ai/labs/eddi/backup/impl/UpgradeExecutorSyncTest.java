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
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * What a sync writes when the source moved on structurally — a step or a whole
 * workflow added or removed, an agent setting changed, a resource edited on the
 * target in the meantime. Each case once either refused, wrote something that
 * could not be deployed, or silently overwrote a local change.
 */
@DisplayName("UpgradeExecutor — structural changes and local edits")
class UpgradeExecutorSyncTest {

    private static final String AGENT = "a0a0a0a0a0a0a0a0a0a0a0a0";
    private static final String WF = "b0b0b0b0b0b0b0b0b0b0b0b0";
    private static final String WF2 = "b1b1b1b1b1b1b1b1b1b1b1b1";
    private static final String LLM = "c0c0c0c0c0c0c0c0c0c0c0c0";
    private static final String CREATED_OUT = "e0e0e0e0e0e0e0e0e0e0e0e0";
    private static final String CREATED_WF = "f0f0f0f0f0f0f0f0f0f0f0f0";
    private static final String SRC_WF = "b9b9b9b9b9b9b9b9b9b9b9b9";
    private static final String SRC_WF2 = "b8b8b8b8b8b8b8b8b8b8b8b8";
    private static final String SRC_LLM = "c9c9c9c9c9c9c9c9c9c9c9c9";
    private static final String SRC_OUT = "d9d9d9d9d9d9d9d9d9d9d9d9";
    private static final String OUTPUT_JSON = "{\"outputSet\":[]}";
    private static final String LLM_JSON = "{\"tasks\":[{\"id\":\"t\",\"type\":\"openai\",\"parameters\":{\"systemMessage\":\"new\"}}]}";

    private final ObjectMapper mapper = new ObjectMapper();
    private IRestAgentStore agentStore;
    private IRestWorkflowStore workflowStore;
    private IDocumentDescriptorStore descriptorStore;
    private StructuralMatcher matcher;
    private IOutputStore outputDocuments;
    private IWorkflowStore workflowDocuments;
    private IRestLlmStore llmStore;
    private UpgradeExecutor executor;
    private AgentConfiguration targetAgent;

    @BeforeEach
    void setUp() throws Exception {
        agentStore = mock(IRestAgentStore.class);
        workflowStore = mock(IRestWorkflowStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        matcher = mock(StructuralMatcher.class);
        outputDocuments = mock(IOutputStore.class);
        workflowDocuments = mock(IWorkflowStore.class);
        llmStore = mock(IRestLlmStore.class);
        var accessGuard = mock(ResourceAccessGuard.class);
        lenient().when(accessGuard.stampNewDescriptor(any())).thenAnswer(inv -> inv.getArgument(0));

        executor = new UpgradeExecutor(agentStore, workflowStore, mock(IRestPromptSnippetStore.class),
                new JsonSerialization(mapper), matcher, descriptorStore, mock(BackupMetrics.class), accessGuard);

        targetAgent = new AgentConfiguration();
        targetAgent.setWorkflows(new ArrayList<>(List.of(uri("workflow", WF, 2))));
        lenient().when(agentStore.getCurrentResourceId(AGENT)).thenReturn(resourceId(AGENT, 5));
        lenient().when(agentStore.readAgent(AGENT, 5)).thenReturn(targetAgent);
        lenient().when(agentStore.updateAgent(eq(AGENT), eq(5), any())).thenReturn(Response.ok().build());
        lenient().when(descriptorStore.readDescriptor(anyString(), anyInt())).thenAnswer(inv -> new DocumentDescriptor());

        lenient().when(workflowStore.readWorkflow(WF, 2)).thenReturn(workflow(step("llm", uri("llm", LLM, 3))));
        lenient().when(workflowStore.updateWorkflow(eq(WF), eq(2), any())).thenReturn(Response.ok().build());
        lenient().when(outputDocuments.create(any())).thenReturn(resourceId(CREATED_OUT, 1));
        lenient().when(workflowDocuments.create(any())).thenReturn(resourceId(CREATED_WF, 1));
        lenient().when(llmStore.updateLlm(anyString(), anyInt(), any())).thenReturn(Response.ok().build());
    }

    @Test
    @DisplayName("a step the source added is created and placed in the adopted workflow")
    void addedStepIsCreatedAndPlaced() {
        var source = source(workflowData(SRC_WF, 0, llmAndOutput(), llmAndOutputExtensions()));
        givenPreview(agentRow(DiffAction.SKIP, null),
                row(SRC_WF, "workflow", DiffAction.UPDATE, WF, 2, 0),
                row(SRC_LLM, "langchain", DiffAction.SKIP, LLM, 3, -1),
                row(SRC_OUT, "output", DiffAction.CREATE, null, null, -1));

        UpgradeResult result = inCdi(() -> executor.executeUpgrade(source, AGENT, null, null));

        // Refusing this made "add a step on staging, promote" impossible without
        // hand-editing production.
        assertTrue(result.failures().isEmpty(), result.failures().toString());
        assertEquals(1, result.created());
        var written = ArgumentCaptor.forClass(WorkflowConfiguration.class);
        verify(workflowStore).updateWorkflow(eq(WF), eq(2), written.capture());
        List<String> uris = WorkflowExtensions.scan(written.getValue()).stream()
                .map(ref -> ref.extensionUri().toString()).toList();
        assertEquals(List.of(uri("llm", LLM, 3).toString(), uri("output", CREATED_OUT, 1).toString()), uris,
                "the adopted steps name the target's own resources — never the source's ids");
    }

    @Test
    @DisplayName("a step the source added is not created when its workflow is left out — nothing could place it")
    void addedStepIsNotCreatedWithoutItsWorkflow() throws Exception {
        var source = source(workflowData(SRC_WF, 0, llmAndOutput(), llmAndOutputExtensions()));
        givenPreview(agentRow(DiffAction.SKIP, null),
                row(SRC_WF, "workflow", DiffAction.UPDATE, WF, 2, 0),
                row(SRC_OUT, "output", DiffAction.CREATE, null, null, -1));

        UpgradeResult result = inCdi(() -> executor.executeUpgrade(source, AGENT, Set.of(SRC_OUT), null));

        verify(outputDocuments, never()).create(any());
        assertEquals(1, result.failures().size(), result.failures().toString());
        assertTrue(result.failures().getFirst().reason().contains("left out of the selection"),
                result.failures().getFirst().reason());
    }

    /**
     * The orphan this guards against: two steps added, only one selected. The
     * adoption that would place the selected one cannot happen — the other step
     * would name a resource that does not exist here — so creating the selected one
     * anyway left it in the store with nothing referencing it, and every later sync
     * made another.
     */
    @Test
    @DisplayName("an added step is not created when a sibling added step was left out — the adoption cannot happen")
    void addedStepIsNotCreatedWhenItsSiblingWasLeftOut() throws Exception {
        String secondOut = "d8d8d8d8d8d8d8d8d8d8d8d8";
        var config = workflow(step("llm", uri("llm", SRC_LLM, 1)), step("output", uri("output", SRC_OUT, 1)),
                step("output", uri("output", secondOut, 1)));
        Map<String, ExtensionSourceData> extensions = new LinkedHashMap<>(llmAndOutputExtensions());
        extensions.put("eddi://ai.labs.output#1/config",
                new ExtensionSourceData(secondOut, "Second output", "output", "eddi://ai.labs.output", OUTPUT_JSON));
        var source = source(workflowData(SRC_WF, 0, config, extensions));
        givenPreview(agentRow(DiffAction.SKIP, null),
                row(SRC_WF, "workflow", DiffAction.UPDATE, WF, 2, 0),
                row(SRC_LLM, "langchain", DiffAction.SKIP, LLM, 3, -1),
                row(SRC_OUT, "output", DiffAction.CREATE, null, null, -1),
                row(secondOut, "output", DiffAction.CREATE, null, null, -1));

        UpgradeResult result = inCdi(() -> executor.executeUpgrade(source, AGENT, Set.of(SRC_WF, SRC_LLM, SRC_OUT), null));

        verify(outputDocuments, never()).create(any());
        assertTrue(result.failures().stream().anyMatch(failure -> failure.reason().contains("Second output")),
                result.failures().toString());
    }

    @Test
    @DisplayName("a workflow the source added arrives with its own resources, naming their ids here")
    void addedWorkflowBringsItsResources() throws Exception {
        var outputOnly = workflow(step("output", uri("output", SRC_OUT, 1)));
        Map<String, ExtensionSourceData> extensions = new LinkedHashMap<>();
        extensions.put("eddi://ai.labs.output#0/config",
                new ExtensionSourceData(SRC_OUT, "FAQ output", "output", "eddi://ai.labs.output", OUTPUT_JSON));
        var source = source(workflowData(SRC_WF, 0, workflow(step("llm", uri("llm", SRC_LLM, 1))), Map.of()),
                workflowData(SRC_WF2, 1, outputOnly, extensions));
        givenPreview(agentRow(DiffAction.UPDATE, null),
                row(SRC_WF, "workflow", DiffAction.SKIP, WF, 2, 0),
                row(SRC_WF2, "workflow", DiffAction.CREATE, null, null, 1),
                row(SRC_OUT, "output", DiffAction.CREATE, null, null, -1));

        UpgradeResult result = inCdi(() -> executor.executeUpgrade(source, AGENT, null, null));

        // It used to store the source's workflow as it was: every step named an id
        // only the source has, nothing was created, and the agent version it wrote
        // could not be deployed — while the sync answered 201.
        assertTrue(result.failures().isEmpty(), result.failures().toString());
        var created = ArgumentCaptor.forClass(WorkflowConfiguration.class);
        verify(workflowDocuments).create(created.capture());
        assertEquals(List.of(uri("output", CREATED_OUT, 1).toString()),
                WorkflowExtensions.scan(created.getValue()).stream().map(ref -> ref.extensionUri().toString()).toList());
        var agent = ArgumentCaptor.forClass(AgentConfiguration.class);
        verify(agentStore).updateAgent(eq(AGENT), eq(5), agent.capture());
        assertEquals(List.of(uri("workflow", WF, 2), uri("workflow", CREATED_WF, 1)), agent.getValue().getWorkflows());
    }

    @Test
    @DisplayName("a workflow the source no longer has comes off the agent")
    void removedWorkflowComesOff() {
        targetAgent.setWorkflows(new ArrayList<>(List.of(uri("workflow", WF, 2), uri("workflow", WF2, 1))));
        var source = source(workflowData(SRC_WF, 0, workflow(step("llm", uri("llm", SRC_LLM, 1))), Map.of()));
        givenPreview(agentRow(DiffAction.UPDATE, null),
                row(SRC_WF, "workflow", DiffAction.SKIP, WF, 2, 0),
                row(WF2, "workflow", DiffAction.REMOVE, WF2, 1, 1));

        UpgradeResult result = inCdi(() -> executor.executeUpgrade(source, AGENT, null, null));

        assertTrue(result.agentUpdated());
        var agent = ArgumentCaptor.forClass(AgentConfiguration.class);
        verify(agentStore).updateAgent(eq(AGENT), eq(5), agent.capture());
        assertEquals(List.of(uri("workflow", WF, 2)), agent.getValue().getWorkflows());
    }

    @Test
    @DisplayName("agent-level settings travel; the target's identity stays")
    void agentSettingsTravelIdentityStays() throws Exception {
        var targetIdentity = new AgentConfiguration.AgentIdentity("did:target", "target-key");
        targetAgent.setIdentity(targetIdentity);
        var adopted = new AgentConfiguration();
        adopted.setWorkflows(new ArrayList<>(List.of(uri("workflow", WF, 2))));
        adopted.setDescription("promoted from staging");
        adopted.setIdentity(new AgentConfiguration.AgentIdentity("did:source", "source-key"));
        var source = source(workflowData(SRC_WF, 0, workflow(step("llm", uri("llm", SRC_LLM, 1))), Map.of()));
        givenPreview(agentRow(DiffAction.UPDATE, mapper.writeValueAsString(adopted)),
                row(SRC_WF, "workflow", DiffAction.SKIP, WF, 2, 0));

        UpgradeResult result = inCdi(() -> executor.executeUpgrade(source, AGENT, null, null));

        // Only workflows were ever written, so a changed agent setting showed as an
        // agent UPDATE in the preview and never arrived.
        assertTrue(result.agentUpdated());
        var agent = ArgumentCaptor.forClass(AgentConfiguration.class);
        verify(agentStore).updateAgent(eq(AGENT), eq(5), agent.capture());
        assertEquals("promoted from staging", agent.getValue().getDescription());
        assertEquals("did:target", agent.getValue().getIdentity().getAgentDid());
    }

    @Test
    @DisplayName("a resource edited here since the last sync is left alone unless named explicitly")
    void conflictIsLeftAloneUnlessSelected() {
        Map<String, ExtensionSourceData> extensions = Map.of("eddi://ai.labs.llm#0/config",
                new ExtensionSourceData(SRC_LLM, "LLM", "langchain", "eddi://ai.labs.llm", LLM_JSON));
        var source = source(workflowData(SRC_WF, 0, workflow(step("llm", uri("llm", SRC_LLM, 1))), extensions));
        givenPreview(agentRow(DiffAction.SKIP, null),
                row(SRC_WF, "workflow", DiffAction.SKIP, WF, 2, 0),
                row(SRC_LLM, "langchain", DiffAction.CONFLICT, LLM, 3, -1));

        UpgradeResult all = inCdi(() -> executor.executeUpgrade(source, AGENT, null, null));

        verify(llmStore, never()).updateLlm(anyString(), anyInt(), any());
        assertEquals(1, all.failures().size(), all.failures().toString());
        assertTrue(all.failures().getFirst().reason().contains("changed on this instance"),
                all.failures().getFirst().reason());

        UpgradeResult chosen = inCdi(() -> executor.executeUpgrade(source, AGENT, Set.of(SRC_WF, SRC_LLM), null));

        verify(llmStore).updateLlm(eq(LLM), eq(3), any());
        assertTrue(chosen.failures().isEmpty(), chosen.failures().toString());
    }

    @Test
    @DisplayName("agent settings changed here are left alone, while the workflow changes still land")
    void agentSettingsConflictLeavesSettingsButWritesWorkflows() throws Exception {
        targetAgent.setDescription("production hotfix");
        var fromSource = new AgentConfiguration();
        fromSource.setWorkflows(new ArrayList<>(List.of(uri("workflow", WF, 2))));
        fromSource.setDescription("from staging");
        Map<String, ExtensionSourceData> extensions = Map.of("eddi://ai.labs.llm#0/config",
                new ExtensionSourceData(SRC_LLM, "LLM", "langchain", "eddi://ai.labs.llm", LLM_JSON));
        var source = source(workflowData(SRC_WF, 0, workflow(step("llm", uri("llm", SRC_LLM, 1))), extensions));
        givenPreview(new ResourceDiff("src-agent", "agent", "Agent", DiffAction.CONFLICT, AGENT, 5, "targetAgent",
                mapper.writeValueAsString(fromSource), null, -1),
                row(SRC_WF, "workflow", DiffAction.SKIP, WF, 2, 0),
                row(SRC_LLM, "langchain", DiffAction.UPDATE, LLM, 3, -1));

        UpgradeResult result = inCdi(() -> executor.executeUpgrade(source, AGENT, null, null));

        var agent = ArgumentCaptor.forClass(AgentConfiguration.class);
        verify(agentStore).updateAgent(eq(AGENT), eq(5), agent.capture());
        assertEquals("production hotfix", agent.getValue().getDescription(), "the local settings stay");
        assertEquals(List.of(uri("workflow", WF, 3)), agent.getValue().getWorkflows(), "the workflow update lands");
        assertTrue(result.failures().stream().anyMatch(f -> "agent".equals(f.resourceType())), result.failures().toString());
    }

    @Test
    @DisplayName("a workflow changed here keeps its steps, while its extensions are still updated")
    void workflowConflictKeepsStepsButWritesExtensions() throws Exception {
        var local = workflow(step("llm", uri("llm", LLM, 3)), step("output", uri("output", "d1d1d1d1d1d1d1d1d1d1d1d1", 1)));
        when(workflowStore.readWorkflow(WF, 2)).thenReturn(local);
        Map<String, ExtensionSourceData> extensions = Map.of("eddi://ai.labs.llm#0/config",
                new ExtensionSourceData(SRC_LLM, "LLM", "langchain", "eddi://ai.labs.llm", LLM_JSON));
        var source = source(workflowData(SRC_WF, 0, workflow(step("llm", uri("llm", SRC_LLM, 1))), extensions));
        givenPreview(agentRow(DiffAction.SKIP, null),
                row(SRC_WF, "workflow", DiffAction.CONFLICT, WF, 2, 0),
                row(SRC_LLM, "langchain", DiffAction.UPDATE, LLM, 3, -1));

        UpgradeResult result = inCdi(() -> executor.executeUpgrade(source, AGENT, null, null));

        var written = ArgumentCaptor.forClass(WorkflowConfiguration.class);
        verify(workflowStore).updateWorkflow(eq(WF), eq(2), written.capture());
        assertEquals(List.of(uri("llm", LLM, 4).toString(), uri("output", "d1d1d1d1d1d1d1d1d1d1d1d1", 1).toString()),
                WorkflowExtensions.scan(written.getValue()).stream().map(ref -> ref.extensionUri().toString()).toList(),
                "the step added here stays, and the LLM step moves to the version this sync wrote");
        assertTrue(result.failures().stream().anyMatch(f -> "workflow".equals(f.resourceType())), result.failures().toString());
    }

    @Test
    @DisplayName("every version a sync writes is recorded as the baseline for the next")
    void writtenVersionBecomesTheBaseline() throws Exception {
        Map<String, ExtensionSourceData> extensions = Map.of("eddi://ai.labs.llm#0/config",
                new ExtensionSourceData(SRC_LLM, "LLM", "langchain", "eddi://ai.labs.llm", LLM_JSON));
        var source = source(workflowData(SRC_WF, 0, workflow(step("llm", uri("llm", SRC_LLM, 1))), extensions));
        givenPreview(agentRow(DiffAction.SKIP, null),
                row(SRC_WF, "workflow", DiffAction.SKIP, WF, 2, 0),
                row(SRC_LLM, "langchain", DiffAction.UPDATE, LLM, 3, -1));

        inCdi(() -> executor.executeUpgrade(source, AGENT, null, null));

        var descriptor = ArgumentCaptor.forClass(DocumentDescriptor.class);
        verify(descriptorStore).updateDescriptor(eq(LLM), eq(3), descriptor.capture());
        assertEquals(4, descriptor.getValue().getSyncedVersion());
    }

    // ==================== helpers ====================

    private WorkflowConfiguration llmAndOutput() {
        return workflow(step("llm", uri("llm", SRC_LLM, 1)), step("output", uri("output", SRC_OUT, 1)));
    }

    private Map<String, ExtensionSourceData> llmAndOutputExtensions() {
        Map<String, ExtensionSourceData> extensions = new LinkedHashMap<>();
        extensions.put("eddi://ai.labs.llm#0/config",
                new ExtensionSourceData(SRC_LLM, "LLM", "langchain", "eddi://ai.labs.llm", LLM_JSON));
        extensions.put("eddi://ai.labs.output#0/config",
                new ExtensionSourceData(SRC_OUT, "Output", "output", "eddi://ai.labs.output", OUTPUT_JSON));
        return extensions;
    }

    private void givenPreview(ResourceDiff... rows) {
        when(matcher.buildPreview(any(), eq(AGENT), eq(true)))
                .thenReturn(new ImportPreview("src-agent", "Agent", AGENT, "Agent", List.of(rows)));
    }

    private static ResourceDiff agentRow(DiffAction action, String sourceContent) {
        return new ResourceDiff("src-agent", "agent", "Agent", action, AGENT, 5, "targetAgent", sourceContent, null, -1);
    }

    private static ResourceDiff row(String sourceId, String type, DiffAction action, String targetId, Integer targetVersion,
                                    int workflowIndex) {
        return new ResourceDiff(sourceId, type, type, action, targetId, targetVersion, "type", null, null, workflowIndex);
    }

    private static WorkflowSourceData workflowData(String id, int position, WorkflowConfiguration config,
                                                   Map<String, ExtensionSourceData> extensions) {
        return new WorkflowSourceData(id, "Workflow " + position, position, config, extensions);
    }

    private static IResourceSource source(WorkflowSourceData... workflows) {
        var agent = new AgentConfiguration();
        List<URI> uris = new ArrayList<>();
        for (WorkflowSourceData workflow : workflows) {
            uris.add(uri("workflow", workflow.sourceId(), 1));
        }
        agent.setWorkflows(uris);
        var source = mock(IResourceSource.class);
        when(source.readAgent()).thenReturn(new AgentSourceData("src-agent", "Agent", agent));
        when(source.readWorkflows()).thenReturn(List.of(workflows));
        when(source.readSnippets()).thenReturn(List.of());
        return source;
    }

    private static WorkflowConfiguration.WorkflowStep step(String type, URI uri) {
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs." + type));
        step.setConfig(new LinkedHashMap<>(Map.of("uri", uri.toString())));
        return step;
    }

    private static WorkflowConfiguration workflow(WorkflowConfiguration.WorkflowStep... steps) {
        var workflow = new WorkflowConfiguration();
        workflow.setWorkflowSteps(new ArrayList<>(List.of(steps)));
        return workflow;
    }

    private static URI uri(String kind, String id, int version) {
        String path = switch (kind) {
            case "workflow" -> "eddi://ai.labs.workflow/workflowstore/workflows/";
            case "llm" -> "eddi://ai.labs.llm/llmstore/llms/";
            case "output" -> "eddi://ai.labs.output/outputstore/outputsets/";
            default -> throw new IllegalArgumentException(kind);
        };
        return URI.create(path + id + "?version=" + version);
    }

    private static IResourceId resourceId(String id, int version) {
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> T inCdi(Supplier<T> action) {
        MockedStatic<CDI> cdiMock = Mockito.mockStatic(CDI.class);
        try (cdiMock) {
            var cdi = Mockito.mock(CDI.class);
            cdiMock.when(CDI::current).thenReturn(cdi);
            Map<Class<?>, Object> beans = Map.of(
                    IOutputStore.class, outputDocuments,
                    IWorkflowStore.class, workflowDocuments,
                    IRestLlmStore.class, llmStore);
            beans.forEach((type, bean) -> {
                var instance = (Instance) Mockito.mock(Instance.class);
                when(cdi.select((Class) type)).thenReturn(instance);
                when(instance.get()).thenReturn(bean);
            });
            return action.get();
        }
    }
}
