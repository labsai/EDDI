/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource.ExtensionSourceData;
import ai.labs.eddi.backup.IResourceSource.SnippetSourceData;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a live sync reads from the source instance. The store endpoints answer
 * with raw configuration, credentials included; before this was scrubbed, the
 * source's plaintext secrets were shown in the operator's preview and written
 * into the target. A real {@link SecretScrubber} is used on purpose — a mocked
 * one would prove only that a method was called.
 */
@DisplayName("RemoteApiResourceSource — what a live sync reads")
@SuppressWarnings("unchecked")
class RemoteApiResourceSourceScrubbingTest {

    private static final String BASE_URL = "http://remote.invalid:7070";
    private static final String AGENT_ID = "aabbccddeeff112233445566";
    private static final String WORKFLOW_ID = "bbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String LLM_ID = "cccccccccccccccccccccccc";
    private static final String API_ID = "dddddddddddddddddddddddd";
    private static final String SNIPPET_OLD = "eeeeeeeeeeeeeeeeeeeeeee1";
    private static final String SNIPPET_NEW = "eeeeeeeeeeeeeeeeeeeeeee2";

    private static final String LIVE_KEY = "not-a-real-key-aaaaaaaa";
    private static final String LIVE_TOKEN = "not-a-real-token-bbbbbbbb";

    private final Map<String, String> bodyByPath = new LinkedHashMap<>();
    private HttpClient httpClient;
    private JsonSerialization jsonSerialization;
    private SecretScrubber secretScrubber;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        jsonSerialization = new JsonSerialization(mapper);
        secretScrubber = new SecretScrubber(mapper);
        httpClient = mock(HttpClient.class);
        doAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            String path = request.uri().getPath() + (request.uri().getQuery() == null ? "" : "?" + request.uri().getQuery());
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            // The most specific registered path wins, so a descriptor listing and a
            // document under the same store do not answer for each other.
            when(response.body()).thenReturn(bodyByPath.entrySet().stream()
                    .filter(entry -> path.startsWith(entry.getKey()))
                    .max(Comparator.comparingInt(entry -> entry.getKey().length()))
                    .map(Map.Entry::getValue)
                    .orElse("[]"));
            return response;
        }).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        bodyByPath.put("/agentstore/agents/" + AGENT_ID, """
                {"workflows":["eddi://ai.labs.workflow/workflowstore/workflows/%s?version=1"],
                 "channels":[{"type":"eddi://ai.labs.channel.slack","config":{"botToken":"%s"}}]}"""
                .formatted(WORKFLOW_ID, LIVE_TOKEN));
        bodyByPath.put("/workflowstore/workflows/" + WORKFLOW_ID, """
                {"workflowSteps":[
                  {"type":"eddi://ai.labs.llm","config":{"uri":"eddi://ai.labs.llm/llmstore/llms/%s?version=1"}},
                  {"type":"eddi://ai.labs.httpcalls","config":{"uri":"eddi://ai.labs.apicalls/apicallstore/apicalls/%s?version=1"}}
                ]}""".formatted(LLM_ID, API_ID));
        bodyByPath.put("/llmstore/llms/" + LLM_ID, """
                {"tasks":[{"id":"support","type":"openai","actions":["ask"],
                  "parameters":{"apiKey":"%s","systemMessage":"Help. {snippets.tone}"}}]}""".formatted(LIVE_KEY));
        bodyByPath.put("/apicallstore/apicalls/" + API_ID, """
                {"targetServerUrl":"https://api.example.com","httpCalls":[{"name":"lookup","actions":["x"],
                  "request":{"path":"/l","method":"get","headers":{"Authorization":"Bearer %s"}}}]}"""
                .formatted(LIVE_TOKEN));
    }

    private RemoteApiResourceSource source() {
        return new RemoteApiResourceSource(BASE_URL, AGENT_ID, 1, null, jsonSerialization, httpClient, false,
                secretScrubber::scrubJson);
    }

    @Test
    @DisplayName("no credential the source holds reaches the preview or the target")
    void everyDocumentIsScrubbed() throws Exception {
        var source = source();

        String agent = jsonSerialization.serialize(source.readAgent().config());
        assertFalse(agent.contains(LIVE_TOKEN), "agent channel token leaked: " + agent);

        Map<String, ExtensionSourceData> extensions = source.readWorkflows().getFirst().extensions();
        String all = String.join("\n", extensions.values().stream().map(ExtensionSourceData::contentJson).toList());
        assertFalse(all.contains(LIVE_KEY), "LLM apiKey leaked: " + all);
        assertFalse(all.contains(LIVE_TOKEN), "Authorization header leaked: " + all);
        assertTrue(all.contains(ScrubbedSecrets.PLACEHOLDER),
                "each secret is replaced by the placeholder the target's own value is put back into: " + all);
        assertTrue(all.contains("Help. {snippets.tone}"), "everything else is left as it was: " + all);
    }

    /**
     * Several snippets named alike on the source used to be offered — and written
     * over the target's one copy — in turn. Only the one the source renders
     * travels: {@code PromptSnippetService} lets a later listing entry replace an
     * earlier one, so the same rule applies here.
     */
    @Test
    @DisplayName("of two snippets sharing a name, only the rendered one travels, with a warning")
    void duplicateSnippetNamesCollapseToTheRenderedOne() {
        bodyByPath.put("/snippetstore/snippets/descriptors", """
                [{"resource":"eddi://ai.labs.snippet/snippetstore/snippets/%s?version=1","name":"tone"},
                 {"resource":"eddi://ai.labs.snippet/snippetstore/snippets/%s?version=1","name":"tone"}]"""
                .formatted(SNIPPET_NEW, SNIPPET_OLD));
        bodyByPath.put("/snippetstore/snippets/" + SNIPPET_NEW,
                "{\"name\":\"tone\",\"content\":\"Be concise.\",\"templateEnabled\":false}");
        bodyByPath.put("/snippetstore/snippets/" + SNIPPET_OLD,
                "{\"name\":\"tone\",\"content\":\"Be warm.\",\"templateEnabled\":false}");

        var source = source();
        List<SnippetSourceData> snippets = source.readSnippets();

        assertEquals(1, snippets.size(), snippets.toString());
        assertEquals(SNIPPET_OLD, snippets.getFirst().sourceId(), "the later listing entry is the one rendered");
        assertEquals("Be warm.", snippets.getFirst().snippet().getContent());
        assertEquals(1, source.warnings().size(), source.warnings().toString());
        assertTrue(source.warnings().getFirst().contains("'tone'"), source.warnings().getFirst());
    }

    @Test
    @DisplayName("a snippet name used once raises no warning")
    void uniqueSnippetNameHasNoWarning() {
        bodyByPath.put("/snippetstore/snippets/descriptors", """
                [{"resource":"eddi://ai.labs.snippet/snippetstore/snippets/%s?version=1","name":"tone"}]"""
                .formatted(SNIPPET_NEW));
        bodyByPath.put("/snippetstore/snippets/" + SNIPPET_NEW,
                "{\"name\":\"tone\",\"content\":\"Be concise.\",\"templateEnabled\":false}");

        var source = source();

        assertEquals(1, source.readSnippets().size());
        assertTrue(source.warnings().isEmpty(), source.warnings().toString());
    }
}
