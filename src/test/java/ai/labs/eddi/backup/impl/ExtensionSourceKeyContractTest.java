/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource;
import ai.labs.eddi.backup.IResourceSource.ExtensionSourceData;
import ai.labs.eddi.backup.IResourceSource.WorkflowSourceData;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * The claim this branch is built around, tested against the two <em>real</em>
 * producers rather than a hand-built fixture.
 * <p>
 * {@link StructuralMatcherRealWorkflowTest} proves the matcher joins, but it
 * builds its {@link IResourceSource} by hand and derives the key by calling
 * {@link WorkflowExtensions#scan} itself — so it cannot catch a producer-side
 * mistake (the wrong file-extension lookup, the wrong resource id, the silent
 * {@code continue} when an archive file is missing). The only assertions on
 * {@code WorkflowSourceData.extensions()} anywhere else in the backup tree
 * assert the map is empty.
 * <p>
 * A regression in either producer reproduces the original defect exactly —
 * every extension reported CREATE, the whole configuration tree duplicated on
 * each sync — with the rest of the suite still green.
 * <p>
 * The fixture carries <em>two</em> steps of the same type on purpose: keying by
 * type alone silently kept only the last of them, and repointing then aimed
 * both steps at the same resource.
 */
@DisplayName("ZIP and remote sources produce extension keys the matcher joins on")
class ExtensionSourceKeyContractTest {

    private static final String SOURCE_AGENT_ID = "aaaa11112222333344445555";
    private static final String SOURCE_WF_ID = "bbbb11112222333344445555";
    private static final String SOURCE_LLM_ID = "cccc11112222333344445555";
    private static final String SOURCE_API_1_ID = "dddd11112222333344445555";
    private static final String SOURCE_API_2_ID = "eeee11112222333344445555";

    private static final String TARGET_AGENT_ID = "1111aaaabbbbccccdddd2222";
    private static final String TARGET_WF_ID = "2222aaaabbbbccccdddd3333";
    private static final String TARGET_LLM_ID = "3333aaaabbbbccccdddd4444";
    private static final String TARGET_API_1_ID = "4444aaaabbbbccccdddd5555";
    private static final String TARGET_API_2_ID = "5555aaaabbbbccccdddd6666";

    /** The canonical keys the fixture must produce, on both sides. */
    private static final List<String> EXPECTED_KEYS = List.of(
            "eddi://ai.labs.httpcalls#0/config",
            "eddi://ai.labs.llm#0/config",
            "eddi://ai.labs.httpcalls#1/config");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final IJsonSerialization jsonSerialization = new JacksonJsonSerialization();

    private Path zipRoot;

    private IRestAgentStore targetAgentStore;
    private IRestWorkflowStore targetWorkflowStore;
    private IDocumentDescriptorStore descriptorStore;
    private StructuralMatcher matcher;

    @BeforeEach
    void setUp() throws Exception {
        zipRoot = Files.createTempDirectory("extension-key-contract");
        writeArchive(zipRoot);

        targetAgentStore = mock(IRestAgentStore.class);
        targetWorkflowStore = mock(IRestWorkflowStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        var snippetStore = mock(IRestPromptSnippetStore.class);
        var restInterfaceFactory = mock(IRestInterfaceFactory.class);

        matcher = new StructuralMatcher(targetAgentStore, descriptorStore, snippetStore,
                targetWorkflowStore, restInterfaceFactory, jsonSerialization);

        doReturn(Collections.emptyList()).when(snippetStore).readSnippetDescriptors(anyString(), anyInt(), anyInt());

        // The target agent: one workflow, shaped exactly like the source's.
        var targetAgent = new AgentConfiguration();
        targetAgent.setWorkflows(List.of(URI.create(
                "eddi://ai.labs.workflow/workflowstore/workflows/" + TARGET_WF_ID + "?version=1")));
        doReturn(targetAgent).when(targetAgentStore).readAgent(TARGET_AGENT_ID, 1);
        stubDescriptor(TARGET_AGENT_ID, "eddi://ai.labs.agent/agentstore/agents/" + TARGET_AGENT_ID + "?version=1");
        stubDescriptor(TARGET_WF_ID, "eddi://ai.labs.workflow/workflowstore/workflows/" + TARGET_WF_ID + "?version=1");

        doReturn(MAPPER.readValue(workflowJson(TARGET_API_1_ID, TARGET_LLM_ID, TARGET_API_2_ID),
                WorkflowConfiguration.class))
                .when(targetWorkflowStore).readWorkflow(TARGET_WF_ID, 1);

        var llmStore = mock(IRestLlmStore.class);
        var apiCallsStore = mock(IRestApiCallsStore.class);
        doReturn(llmStore).when(restInterfaceFactory).get(IRestLlmStore.class);
        doReturn(apiCallsStore).when(restInterfaceFactory).get(IRestApiCallsStore.class);
        doReturn(llm("target prompt")).when(llmStore).readLlm(TARGET_LLM_ID, 1);
        doReturn(apiCalls("https://target.example.com")).when(apiCallsStore).readApiCalls(TARGET_API_1_ID, 1);
        doReturn(apiCalls("https://target.example.com")).when(apiCallsStore).readApiCalls(TARGET_API_2_ID, 1);
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteRecursively(zipRoot);
    }

    @Test
    @DisplayName("a ZIP source's extensions carry the canonical key, one entry per reference")
    void zipSourceEmitsCanonicalKeys() {
        try (var source = new ZipResourceSource(zipRoot, jsonSerialization)) {
            Map<String, ExtensionSourceData> extensions = onlyWorkflow(source).extensions();

            assertEquals(EXPECTED_KEYS, List.copyOf(extensions.keySet()),
                    "the ZIP side used to key by resource-store authority (ai.labs.apicalls), "
                            + "which the target map never contains");
            assertEquals(SOURCE_API_1_ID, extensions.get(EXPECTED_KEYS.get(0)).sourceId());
            assertEquals(SOURCE_LLM_ID, extensions.get(EXPECTED_KEYS.get(1)).sourceId());
            assertEquals(SOURCE_API_2_ID, extensions.get(EXPECTED_KEYS.get(2)).sourceId(),
                    "two steps of one type must both survive, not collapse onto one key");

            // The file-extension label decides which store the executor writes to.
            assertEquals("httpcalls", extensions.get(EXPECTED_KEYS.get(0)).type());
            assertEquals("langchain", extensions.get(EXPECTED_KEYS.get(1)).type());
            // The content is the archive's own bytes, not a placeholder.
            assertTrue(extensions.get(EXPECTED_KEYS.get(1)).contentJson().contains("source prompt"),
                    extensions.get(EXPECTED_KEYS.get(1)).contentJson());
        }
    }

    @Test
    @DisplayName("a remote source's extensions carry the same keys as the ZIP source's")
    void remoteSourceEmitsTheSameKeys() throws Exception {
        try (var remote = remoteSource();
                var zip = new ZipResourceSource(zipRoot, jsonSerialization)) {

            Map<String, ExtensionSourceData> remoteExtensions = onlyWorkflow(remote).extensions();

            assertEquals(EXPECTED_KEYS, List.copyOf(remoteExtensions.keySet()));
            assertEquals(List.copyOf(onlyWorkflow(zip).extensions().keySet()),
                    List.copyOf(remoteExtensions.keySet()),
                    "the two producers must agree, or a sync works from a ZIP and not from an instance");
            assertEquals(SOURCE_API_2_ID, remoteExtensions.get(EXPECTED_KEYS.get(2)).sourceId());
            assertTrue(remoteExtensions.get(EXPECTED_KEYS.get(1)).contentJson().contains("source prompt"));
        }
    }

    @Test
    @DisplayName("every extension read from a ZIP matches a target extension instead of being CREATEd")
    void zipSourceJoinsWithTheTarget() {
        try (var source = new ZipResourceSource(zipRoot, jsonSerialization)) {
            assertJoins(matcher.buildPreview(source, TARGET_AGENT_ID, true));
        }
    }

    @Test
    @DisplayName("every extension read from a remote instance matches a target extension too")
    void remoteSourceJoinsWithTheTarget() throws Exception {
        try (var source = remoteSource()) {
            assertJoins(matcher.buildPreview(source, TARGET_AGENT_ID, true));
        }
    }

    /**
     * Each source extension has to land on its own target extension. CREATE here
     * means {@code UpgradeExecutor} calls {@code createExtension} for every one of
     * them, duplicating the configuration tree on every sync.
     */
    private void assertJoins(ImportPreview preview) {
        assertEquals(TARGET_API_1_ID, matchedTargetId(preview, SOURCE_API_1_ID));
        assertEquals(TARGET_LLM_ID, matchedTargetId(preview, SOURCE_LLM_ID));
        assertEquals(TARGET_API_2_ID, matchedTargetId(preview, SOURCE_API_2_ID),
                "the second step of a repeated type must match its own target, not the first one's");
    }

    private String matchedTargetId(ImportPreview preview, String sourceId) {
        ResourceDiff diff = preview.resources().stream()
                .filter(d -> sourceId.equals(d.sourceId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no diff for source resource " + sourceId + " in " + preview.resources()));
        assertNotEquals(DiffAction.CREATE, diff.action(),
                "CREATE means source and target keys did not join: " + diff);
        assertEquals("type", diff.matchStrategy(), diff.toString());
        return diff.targetId();
    }

    // ==================== Fixtures ====================

    private static WorkflowSourceData onlyWorkflow(IResourceSource source) {
        List<WorkflowSourceData> workflows = source.readWorkflows();
        assertEquals(1, workflows.size(), "the fixture agent has exactly one workflow: " + workflows);
        return workflows.getFirst();
    }

    /**
     * The shape {@code AgentSetupService} writes and the shipped reference config
     * uses: an {@code eddi://} step type, and the extension reference in
     * {@code config.uri}.
     */
    private static String workflowJson(String apiCalls1Id, String llmId, String apiCalls2Id) {
        return """
                {"workflowSteps":[
                  {"type":"eddi://ai.labs.httpcalls","extensions":{},
                   "config":{"uri":"eddi://ai.labs.apicalls/apicallstore/apicalls/%s?version=1"}},
                  {"type":"eddi://ai.labs.llm","extensions":{},
                   "config":{"uri":"eddi://ai.labs.llm/llmstore/llms/%s?version=1"}},
                  {"type":"eddi://ai.labs.httpcalls","extensions":{},
                   "config":{"uri":"eddi://ai.labs.apicalls/apicallstore/apicalls/%s?version=1"}}
                ]}""".formatted(apiCalls1Id, llmId, apiCalls2Id);
    }

    /** An export archive, in the layout {@code RestExportService} produces. */
    private void writeArchive(Path root) throws IOException {
        Files.writeString(root.resolve(SOURCE_AGENT_ID + ".agent.json"),
                "{\"workflows\":[\"eddi://ai.labs.workflow/workflowstore/workflows/"
                        + SOURCE_WF_ID + "?version=1\"]}");
        Files.writeString(root.resolve(SOURCE_AGENT_ID + ".descriptor.json"), "{\"name\":\"Source Agent\"}");

        Path versionDir = Files.createDirectories(root.resolve(SOURCE_WF_ID).resolve("1"));
        Files.writeString(versionDir.resolve(SOURCE_WF_ID + ".workflow.json"),
                workflowJson(SOURCE_API_1_ID, SOURCE_LLM_ID, SOURCE_API_2_ID));
        Files.writeString(versionDir.resolve(SOURCE_API_1_ID + ".httpcalls.json"), apiCallsJson());
        Files.writeString(versionDir.resolve(SOURCE_API_2_ID + ".httpcalls.json"), apiCallsJson());
        Files.writeString(versionDir.resolve(SOURCE_LLM_ID + ".langchain.json"), llmJson());
    }

    private static String apiCallsJson() {
        return "{\"targetServerUrl\":\"https://source.example.com\",\"httpCalls\":[]}";
    }

    private static String llmJson() {
        return "{\"tasks\":[{\"id\":\"main\",\"type\":\"llm\",\"description\":\"source prompt\"}]}";
    }

    private static LlmConfiguration llm(String description) {
        var task = new LlmConfiguration.Task();
        task.setId("main");
        task.setType("llm");
        task.setDescription(description);
        return new LlmConfiguration(new ArrayList<>(List.of(task)));
    }

    private static ApiCallsConfiguration apiCalls(String targetServerUrl) {
        var config = new ApiCallsConfiguration();
        config.setTargetServerUrl(targetServerUrl);
        config.setHttpCalls(new ArrayList<>());
        return config;
    }

    /**
     * A {@link RemoteApiResourceSource} over a stubbed HTTP client that answers the
     * same documents the archive holds.
     */
    @SuppressWarnings("unchecked")
    private RemoteApiResourceSource remoteSource() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        Mockito.doAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            return response(bodyFor(request.uri().toString()));
        }).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        return new RemoteApiResourceSource("https://source.example.com", SOURCE_AGENT_ID, 1,
                "Bearer token", jsonSerialization, httpClient);
    }

    private static String bodyFor(String uri) {
        if (uri.contains("/descriptors")) {
            return "[]";
        }
        if (uri.contains("/agentstore/agents/")) {
            return "{\"workflows\":[\"eddi://ai.labs.workflow/workflowstore/workflows/"
                    + SOURCE_WF_ID + "?version=1\"]}";
        }
        if (uri.contains("/workflowstore/workflows/")) {
            return workflowJson(SOURCE_API_1_ID, SOURCE_LLM_ID, SOURCE_API_2_ID);
        }
        if (uri.contains("/apicallstore/apicalls/")) {
            return apiCallsJson();
        }
        if (uri.contains("/llmstore/llms/")) {
            return llmJson();
        }
        throw new AssertionError("the fixture does not serve " + uri);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        doReturn(200).when(response).statusCode();
        doReturn(body).when(response).body();
        return response;
    }

    private void stubDescriptor(String id, String resourceUri) throws Exception {
        var descriptor = new DocumentDescriptor();
        descriptor.setName("Target " + id);
        descriptor.setResource(URI.create(resourceUri));
        doReturn(descriptor).when(descriptorStore).readDescriptor(eq(id), isNull());
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }

    /** A real serializer — what is under test is JSON handling, not a mock. */
    private static final class JacksonJsonSerialization implements IJsonSerialization {
        @Override
        public String serialize(Object model) throws IOException {
            return MAPPER.writeValueAsString(model);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String json) throws IOException {
            return (T) MAPPER.readValue(json, Object.class);
        }

        @Override
        public <T> T deserialize(String json, Class<T> type) throws IOException {
            return MAPPER.readValue(json, type);
        }
    }
}
