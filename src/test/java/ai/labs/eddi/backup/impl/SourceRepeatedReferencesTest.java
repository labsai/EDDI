/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource.WorkflowSourceData;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.httpclient.outbound.JdkClientEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A source agent that names one workflow, extension or failing document over
 * and over must not multiply that one document into as many parses, copies or
 * requests (agent-findings review, backup batch).
 */
@DisplayName("Source readers — repeated references")
@SuppressWarnings("unchecked")
class SourceRepeatedReferencesTest {

    private static final String AGENT_ID = "aabbccddeeff112233445566";
    private static final String WORKFLOW_ID = "bbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String LLM_ID = "cccccccccccccccccccccccc";
    private static final String MISSING_ID = "dddddddddddddddddddddddd";

    private final JsonSerialization jsonSerialization = new JsonSerialization(new ObjectMapper());

    private static String workflowRefs(int times) {
        return IntStream.range(0, times)
                .mapToObj(i -> "\"eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1\"")
                .collect(Collectors.joining(",", "{\"workflows\":[", "]}"));
    }

    private static String llmSteps(String llmId, int times) {
        return IntStream.range(0, times)
                .mapToObj(i -> "{\"type\":\"eddi://ai.labs.llm\",\"config\":{\"uri\":\"eddi://ai.labs.llm/llmstore/llms/" + llmId
                        + "?version=1\"}}")
                .collect(Collectors.joining(",", "{\"workflowSteps\":[", "]}"));
    }

    @Nested
    @DisplayName("remote instance")
    class Remote {

        private final Map<String, String> bodyByPath = new LinkedHashMap<>();
        private final Map<String, Integer> requestsByPath = new ConcurrentHashMap<>();
        private HttpClient httpClient;

        @BeforeEach
        void setUp() throws Exception {
            httpClient = mock(HttpClient.class);
            doAnswer(invocation -> {
                HttpRequest request = invocation.getArgument(0);
                String path = request.uri().getPath() + (request.uri().getQuery() == null ? "" : "?" + request.uri().getQuery());
                requestsByPath.merge(request.uri().getPath(), 1, Integer::sum);
                String body = bodyByPath.entrySet().stream()
                        .filter(entry -> path.startsWith(entry.getKey()))
                        .max(Comparator.comparingInt(entry -> entry.getKey().length()))
                        .map(Map.Entry::getValue)
                        .orElse(null);
                HttpResponse<String> response = mock(HttpResponse.class);
                when(response.statusCode()).thenReturn(body == null ? 404 : 200);
                when(response.body()).thenReturn(body == null ? "" : body);
                return response;
            }).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }

        private RemoteApiResourceSource source() {
            return new RemoteApiResourceSource("http://remote.invalid:7070", AGENT_ID, 1, null, jsonSerialization,
                    UnaryOperator.identity(), JdkClientEngine.facade(httpClient));
        }

        @Test
        @DisplayName("a workflow named at every position is parsed once and shared, each copy at its own position")
        void repeatedWorkflowIsParsedOnce() {
            bodyByPath.put("/agentstore/agents/" + AGENT_ID, workflowRefs(5));
            bodyByPath.put("/workflowstore/workflows/" + WORKFLOW_ID, llmSteps(LLM_ID, 1));
            bodyByPath.put("/llmstore/llms/" + LLM_ID, "{\"tasks\":[]}");

            List<WorkflowSourceData> workflows = source().readWorkflows();

            assertEquals(5, workflows.size());
            for (int i = 0; i < workflows.size(); i++) {
                assertEquals(i, workflows.get(i).positionIndex());
                assertSame(workflows.getFirst().config(), workflows.get(i).config(), "one parsed config for every position");
                assertSame(workflows.getFirst().extensions(), workflows.get(i).extensions());
            }
        }

        @Test
        @DisplayName("a document that failed is not requested again for the next reference to it")
        void failedDocumentIsRequestedOnce() {
            bodyByPath.put("/agentstore/agents/" + AGENT_ID, "{\"workflows\":["
                    + "\"eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1\","
                    + "\"eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=2\"]}");
            // Two versions of one workflow (so two documents), each naming the same
            // missing LLM in many steps.
            bodyByPath.put("/workflowstore/workflows/" + WORKFLOW_ID, llmSteps(MISSING_ID, 20));

            source().readWorkflows();

            assertEquals(1, requestsByPath.get("/llmstore/llms/" + MISSING_ID), "requests: " + requestsByPath);
        }

        @Test
        @DisplayName("a source naming more documents than the request budget fails the sync instead of being read to the end")
        void requestBudgetEndsTheSync() {
            int distinct = RemoteApiResourceSource.DEFAULT_MAX_REQUESTS + 10;
            bodyByPath.put("/agentstore/agents/" + AGENT_ID, workflowRefs(1));
            // Distinct ids, every one missing: each costs a request, none is cached.
            bodyByPath.put("/workflowstore/workflows/" + WORKFLOW_ID, IntStream.range(0, distinct)
                    .mapToObj(i -> "{\"type\":\"eddi://ai.labs.llm\",\"config\":{\"uri\":\"eddi://ai.labs.llm/llmstore/llms/"
                            + String.format("%024x", i) + "?version=1\"}}")
                    .collect(Collectors.joining(",", "{\"workflowSteps\":[", "]}")));

            var source = source();
            var failure = assertThrows(RemoteApiResourceSource.RemoteReadException.class, source::readWorkflows);

            assertTrue(failure.getMessage().contains(RemoteApiResourceSource.MAX_REQUESTS_PROPERTY), failure.getMessage());
            int sent = requestsByPath.values().stream().mapToInt(Integer::intValue).sum();
            assertEquals(RemoteApiResourceSource.DEFAULT_MAX_REQUESTS, sent, "no request past the budget");
        }
    }

    @Test
    @DisplayName("the executor repoints a copy of a shared source config, never the config itself")
    void executorCopyIsIndependent() throws Exception {
        WorkflowConfiguration shared = jsonSerialization.deserialize(llmSteps(LLM_ID, 1), WorkflowConfiguration.class);
        WorkflowConfiguration copy = UpgradeExecutor.copyOf(shared);

        WorkflowExtensions.scan(copy).getFirst().repointTo(URI.create("eddi://ai.labs.llm/llmstore/llms/" + MISSING_ID + "?version=9"));

        assertTrue(jsonSerialization.serialize(shared).contains(LLM_ID), "the shared config is untouched");
        assertTrue(jsonSerialization.serialize(copy).contains(MISSING_ID));
    }

    @Nested
    @DisplayName("ZIP archive")
    class Zip {

        private Path root;

        @BeforeEach
        void setUp() throws Exception {
            root = Files.createTempDirectory("eddi-zip-repeat");
        }

        @AfterEach
        void tearDown() throws Exception {
            // ZipResourceSource.close() removes the tree itself; this is for a failure.
            if (!Files.exists(root)) {
                return;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }

        @Test
        @DisplayName("a workflow named at every position is parsed once; an extension every step names is read into one string")
        void repeatedReferencesShareOneCopy() throws Exception {
            Files.writeString(root.resolve(AGENT_ID + ".agent.json"), workflowRefs(4), StandardCharsets.UTF_8);
            Path versionDir = Files.createDirectories(root.resolve(WORKFLOW_ID).resolve("1"));
            Files.writeString(versionDir.resolve(WORKFLOW_ID + ".workflow.json"), llmSteps(LLM_ID, 3), StandardCharsets.UTF_8);
            Files.writeString(versionDir.resolve(LLM_ID + ".langchain.json"), "{\"tasks\":[]}", StandardCharsets.UTF_8);

            try (var source = new ZipResourceSource(root, jsonSerialization)) {
                List<WorkflowSourceData> workflows = source.readWorkflows();

                assertEquals(4, workflows.size());
                for (int i = 0; i < workflows.size(); i++) {
                    assertEquals(i, workflows.get(i).positionIndex());
                    assertSame(workflows.getFirst().config(), workflows.get(i).config());
                }
                var extensions = List.copyOf(workflows.getFirst().extensions().values());
                assertEquals(3, extensions.size(), "each step keeps its own entry");
                assertSame(extensions.get(0).contentJson(), extensions.get(1).contentJson(), "one string for one file");
                assertSame(extensions.get(0).contentJson(), extensions.get(2).contentJson());
            }
        }
    }
}
