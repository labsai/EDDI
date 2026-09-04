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
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Runs the real {@link StructuralMatcher} against a workflow shaped the way
 * {@code AgentSetupService} writes one and the way the shipped reference config
 * looks — step {@code type} is the {@code eddi://} step-type URI and the
 * extension reference lives in {@code config.uri}.
 * <p>
 * Three independent breaks in the flagship sync capability met here:
 * <ul>
 * <li>the URI was read from {@code step.getExtensions()}, which no real
 * workflow populates, so the matcher saw zero target extensions;</li>
 * <li>source and target keyed their extension maps in two different namespaces,
 * so nothing could join even once the URI was found;</li>
 * <li>the {@link DiffAction} was decided from content the matcher had been told
 * not to load, so every matched resource compared equal and every sync silently
 * updated nothing.</li>
 * </ul>
 */
@DisplayName("StructuralMatcher — against a real-shaped workflow")
class StructuralMatcherRealWorkflowTest {

    private static final String TARGET_AGENT_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TARGET_WF_ID = "bbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String TARGET_LLM_ID = "cccccccccccccccccccccccc";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IRestAgentStore agentStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IRestWorkflowStore workflowStore;
    private IRestInterfaceFactory restInterfaceFactory;
    private IRestLlmStore llmStore;
    private StructuralMatcher matcher;

    @BeforeEach
    void setUp() throws Exception {
        agentStore = mock(IRestAgentStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        IRestPromptSnippetStore snippetStore = mock(IRestPromptSnippetStore.class);
        workflowStore = mock(IRestWorkflowStore.class);
        restInterfaceFactory = mock(IRestInterfaceFactory.class);
        llmStore = mock(IRestLlmStore.class);

        matcher = new StructuralMatcher(agentStore, documentDescriptorStore, snippetStore,
                workflowStore, restInterfaceFactory, new JacksonJsonSerialization());

        doReturn(Collections.emptyList()).when(snippetStore).readSnippetDescriptors(anyString(), anyInt(), anyInt());
        doReturn(llmStore).when(restInterfaceFactory).get(IRestLlmStore.class);

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(
                URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + TARGET_WF_ID + "?version=1")));
        doReturn(agentConfig).when(agentStore).readAgent(TARGET_AGENT_ID, 1);

        stubDescriptor(TARGET_AGENT_ID, "eddi://ai.labs.agent/agentstore/agents/" + TARGET_AGENT_ID + "?version=1");
        stubDescriptor(TARGET_WF_ID, "eddi://ai.labs.workflow/workflowstore/workflows/" + TARGET_WF_ID + "?version=1");

        doReturn(realShapedWorkflow()).when(workflowStore).readWorkflow(TARGET_WF_ID, 1);
    }

    @Test
    @DisplayName("target extensions are found and matched — not reported as CREATE")
    void matchesExtensionAgainstRealWorkflow() throws Exception {
        doReturn(llm("answer questions")).when(llmStore).readLlm(TARGET_LLM_ID, 1);

        ImportPreview preview = matcher.buildPreview(sourceWith(llmJson("answer questions")), TARGET_AGENT_ID, true);

        ResourceDiff llmDiff = diffOfType(preview, "langchain");
        // CREATE here meant executeUpgrade called createExtension for every
        // extension, duplicating the whole configuration tree on every sync.
        assertEquals(DiffAction.SKIP, llmDiff.action());
        assertEquals(TARGET_LLM_ID, llmDiff.targetId());
        assertEquals("type", llmDiff.matchStrategy());
    }

    @Test
    @DisplayName("differing content is UPDATE even when the diff view is not requested")
    void decidesActionWithoutIncludeContent() throws Exception {
        doReturn(llm("answer questions")).when(llmStore).readLlm(TARGET_LLM_ID, 1);

        // includeContent=false is what UpgradeExecutor passed. Deciding the action
        // from content that was not loaded compared null against null, so every
        // matched resource was SKIP and a sync updated nothing while still bumping
        // the agent version and answering 201 CREATED.
        ImportPreview preview = matcher.buildPreview(sourceWith(llmJson("summarise tickets")), TARGET_AGENT_ID, false);

        ResourceDiff llmDiff = diffOfType(preview, "langchain");
        assertEquals(DiffAction.UPDATE, llmDiff.action());
        assertEquals(TARGET_LLM_ID, llmDiff.targetId());
        // includeContent still governs whether the JSON is returned for the UI.
        assertNull(llmDiff.sourceContent());
        assertNull(llmDiff.targetContent());
    }

    @Test
    @DisplayName("identical content differing only in whitespace and key order still SKIPs")
    void normalizesBeforeComparing() throws Exception {
        doReturn(llm("answer questions")).when(llmStore).readLlm(TARGET_LLM_ID, 1);

        // Source content is verbatim ZIP text or a verbatim HTTP body; target
        // content is a fresh serialization. Comparing them as strings made every
        // resource look modified, so SKIP effectively never fired.
        String reordered = prettyWithReversedKeys(llmJson("answer questions"));
        assertNotEquals(reordered, llmJson("answer questions"), "the fixture must actually differ as text");

        ImportPreview preview = matcher.buildPreview(sourceWith(reordered), TARGET_AGENT_ID, true);

        assertEquals(DiffAction.SKIP, diffOfType(preview, "langchain").action());
    }

    @Test
    @DisplayName("an unreadable target agent is a 404, not a silent switch to create-everything")
    void unreadableTargetIsNotFound() throws Exception {
        doThrow(new RuntimeException("datastore down")).when(agentStore).readAgent(eq(TARGET_AGENT_ID), anyInt());

        var ex = assertThrows(NotFoundException.class,
                () -> matcher.buildPreview(sourceWith(llmJson("answer questions")), TARGET_AGENT_ID, true));
        assertTrue(ex.getMessage().contains(TARGET_AGENT_ID), ex.getMessage());
    }

    // ==================== Fixtures ====================

    /**
     * A workflow step in the shape AgentSetupService writes and the shipped
     * reference config uses: an {@code eddi://} step type and {@code config.uri}.
     */
    private static WorkflowConfiguration realShapedWorkflow() {
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.llm"));
        step.setConfig(new HashMap<>(Map.of("uri",
                "eddi://ai.labs.llm/llmstore/llms/" + TARGET_LLM_ID + "?version=1")));
        step.setExtensions(new HashMap<>());

        var config = new WorkflowConfiguration();
        config.setWorkflowSteps(new ArrayList<>(List.of(step)));
        return config;
    }

    /**
     * The source content an export or a remote read would carry: the same config
     * class serialized, not a hand-written subset.
     */
    private static String llmJson(String description) throws Exception {
        return MAPPER.writeValueAsString(llm(description));
    }

    /**
     * The same document rendered by a different producer — indented, and with every
     * object's keys in the opposite order.
     */
    private static String prettyWithReversedKeys(String json) throws Exception {
        Object tree = MAPPER.readValue(json, Object.class);
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(reverseKeys(tree));
    }

    private static Object reverseKeys(Object node) {
        if (node instanceof Map<?, ?> map) {
            List<?> keys = new ArrayList<>(map.keySet());
            Collections.reverse(keys);
            Map<String, Object> reversed = new LinkedHashMap<>();
            for (Object key : keys) {
                reversed.put(String.valueOf(key), reverseKeys(map.get(key)));
            }
            return reversed;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(StructuralMatcherRealWorkflowTest::reverseKeys).toList();
        }
        return node;
    }

    private static LlmConfiguration llm(String description) {
        var task = new LlmConfiguration.Task();
        task.setId("main");
        task.setType("llm");
        task.setDescription(description);
        return new LlmConfiguration(new ArrayList<>(List.of(task)));
    }

    private IResourceSource sourceWith(String llmContentJson) {
        var sourceWfConfig = realShapedWorkflow();
        String extensionKey = WorkflowExtensions.scan(sourceWfConfig).getFirst().key();

        var extension = new ExtensionSourceData("src-llm", "GPT Config", "langchain",
                "eddi://ai.labs.llm", llmContentJson);
        var workflow = new WorkflowSourceData("src-wf", "Workflow", 0, sourceWfConfig,
                Map.of(extensionKey, extension));

        return new IResourceSource() {
            @Override
            public AgentSourceData readAgent() {
                return new AgentSourceData("src-agent", "Source Agent", new AgentConfiguration());
            }

            @Override
            public List<WorkflowSourceData> readWorkflows() {
                return List.of(workflow);
            }

            @Override
            public List<SnippetSourceData> readSnippets() {
                return List.of();
            }
        };
    }

    private void stubDescriptor(String id, String resourceUri) throws Exception {
        var descriptor = new DocumentDescriptor();
        descriptor.setName("Target " + id);
        descriptor.setResource(URI.create(resourceUri));
        doReturn(descriptor).when(documentDescriptorStore).readDescriptor(eq(id), isNull());
    }

    private static ResourceDiff diffOfType(ImportPreview preview, String resourceType) {
        return preview.resources().stream()
                .filter(d -> resourceType.equals(d.resourceType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no '" + resourceType + "' diff in preview: " + preview.resources()));
    }

    /** A real serializer — the comparison under test is about JSON, not mocks. */
    private static final class JacksonJsonSerialization implements IJsonSerialization {
        private final ObjectMapper mapper = MAPPER;

        @Override
        public String serialize(Object model) throws IOException {
            return mapper.writeValueAsString(model);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String json) throws IOException {
            return (T) mapper.readValue(json, Object.class);
        }

        @Override
        public <T> T deserialize(String json, Class<T> type) throws IOException {
            return mapper.readValue(json, type);
        }
    }
}
