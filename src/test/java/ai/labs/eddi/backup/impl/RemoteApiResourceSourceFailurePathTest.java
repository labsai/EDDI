/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource.ExtensionSourceData;
import ai.labs.eddi.backup.IResourceSource.WorkflowSourceData;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a live sync reads from a remote instance when part of that instance does
 * not answer.
 * <p>
 * The split matters: a missing <em>version</em> is fatal, because guessing one
 * would sync an arbitrarily old configuration into the target, while a missing
 * <em>name</em> or a single unreadable extension is not — the preview is still
 * worth showing, and the sync's own diff decides what to write.
 */
@DisplayName("RemoteApiResourceSource — partial remote failures")
@SuppressWarnings("unchecked")
class RemoteApiResourceSourceFailurePathTest {

    private static final String BASE_URL = "http://remote.invalid:7070";
    private static final String AGENT_ID = "aabbccddeeff112233445566";
    private static final String WORKFLOW_ID = "bbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String LLM_ID = "cccccccccccccccccccccccc";
    private static final String READABLE_LLM_ID = "dddddddddddddddddddddddd";

    private IJsonSerialization jsonSerialization;
    private HttpClient httpClient;
    /**
     * path suffix → status; anything not listed answers 200 with
     * {@link #bodyByPath}.
     */
    private final Map<String, Integer> statusByPath = new HashMap<>();
    private final Map<String, String> bodyByPath = new HashMap<>();
    /** Every path this source asked the remote instance for, in order. */
    private final List<String> requestedPaths = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        jsonSerialization = mock(IJsonSerialization.class);
        httpClient = mock(HttpClient.class);

        doAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            String path = request.uri().getPath() + (request.uri().getQuery() == null
                    ? ""
                    : "?" + request.uri().getQuery());
            requestedPaths.add(path);
            HttpResponse<String> response = mock(HttpResponse.class);
            int status = statusByPath.entrySet().stream()
                    .filter(entry -> path.contains(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(200);
            when(response.statusCode()).thenReturn(status);
            when(response.body()).thenReturn(bodyByPath.entrySet().stream()
                    .filter(entry -> path.contains(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse("{}"));
            return response;
        }).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    /**
     * Falling back to version 1 silently synced an arbitrarily old configuration
     * into the target whenever the descriptor listing was paginated, access-scoped,
     * or briefly unavailable. Failing loudly is the point.
     */
    @Test
    @DisplayName("an unresolvable agent version fails the read instead of guessing one")
    void unresolvableAgentVersionFails() {
        statusByPath.put("/agentstore/agents/descriptors", 503);

        var source = new RemoteApiResourceSource(BASE_URL, AGENT_ID, null, null, jsonSerialization, httpClient);

        var thrown = assertThrows(RuntimeException.class, source::readAgent);

        assertTrue(thrown.getMessage().contains(AGENT_ID), thrown.getMessage());
        Throwable cause = thrown.getCause();
        assertTrue(cause != null && cause.getMessage().contains("latest version"),
                "the reason must name the version lookup, was: " + cause);
    }

    /**
     * A descriptor listing that does not answer costs the human-readable name and
     * nothing else. The sync joins on ids, so an unnamed row is still a usable row.
     */
    @Test
    @DisplayName("an unreadable descriptor listing costs the name, not the agent")
    void unreadableDescriptorListingCostsOnlyTheName() throws Exception {
        statusByPath.put("/agentstore/agents/descriptors", 500);
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of());
        when(jsonSerialization.deserialize(anyString(), eq(AgentConfiguration.class))).thenReturn(agentConfig);

        var source = new RemoteApiResourceSource(BASE_URL, AGENT_ID, 3, null, jsonSerialization, httpClient);
        var agent = source.readAgent();

        assertEquals(AGENT_ID, agent.sourceId());
        assertNull(agent.name(), "an unnamed row is still a row the sync can join on");
    }

    /**
     * Snippets are a bonus, not a precondition: a remote instance that will not
     * list them must not stop the agent and its workflows from being previewed.
     */
    @Test
    @DisplayName("a snippet listing that fails yields no snippets rather than failing the read")
    void unreadableSnippetListingYieldsNoSnippets() {
        statusByPath.put("/snippetstore/snippets/descriptors", 502);

        var source = new RemoteApiResourceSource(BASE_URL, AGENT_ID, 1, null, jsonSerialization, httpClient);

        assertTrue(source.readSnippets().isEmpty());
    }

    /**
     * One extension the remote will not hand over must not cost the workflow it
     * belongs to — nor the other extensions of that same workflow.
     * <p>
     * The surviving one is asserted by its <em>key</em>, not merely by its
     * presence, because the key is the join: {@code StructuralMatcher} builds the
     * same key from the local target workflow, and when the two sides disagreed
     * every extension read as CREATE and every sync duplicated the lot. The
     * occurrence ordinal is part of it, so two steps of the same type stay two
     * entries instead of collapsing onto one.
     */
    @Test
    @DisplayName("an extension the remote refuses is left out; the workflow and its other extensions are still read")
    void unreadableExtensionLeavesTheWorkflowIntact() throws Exception {
        statusByPath.put("/llmstore/llms/" + LLM_ID, 500);
        bodyByPath.put("/llmstore/llms/" + READABLE_LLM_ID, "{\"model\":\"gpt\"}");
        stubAgentWithOneWorkflow();
        stubWorkflowWithTwoLlmSteps();
        when(jsonSerialization.deserialize(anyString(), eq(DocumentDescriptor[].class)))
                .thenReturn(new DocumentDescriptor[0]);

        var source = new RemoteApiResourceSource(BASE_URL, AGENT_ID, 1, null, jsonSerialization, httpClient);
        List<WorkflowSourceData> workflows = source.readWorkflows();

        assertEquals(1, workflows.size(), "the workflow itself must survive an unreadable extension");
        Map<String, ExtensionSourceData> extensions = workflows.getFirst().extensions();
        assertEquals(Set.of("eddi://ai.labs.llm#1/config"), extensions.keySet(),
                "the readable extension must be keyed the way the matcher keys the target's own copy");

        ExtensionSourceData readable = extensions.get("eddi://ai.labs.llm#1/config");
        assertEquals(READABLE_LLM_ID, readable.sourceId());
        assertEquals("langchain", readable.type());
        assertEquals("{\"model\":\"gpt\"}", readable.contentJson(),
                "the config the remote did hand over has to arrive intact");
    }

    /**
     * A descriptor listing is unpaged: it returns every descriptor of that type in
     * the whole remote deployment. Fetching it once per workflow and once per
     * extension — purely to fill in a display name — turned a single preview of a
     * modest agent into dozens of full downloads from the remote instance, which is
     * what made a sync preview time out against a real deployment.
     */
    @Test
    @DisplayName("a store's descriptor listing is downloaded once per sync, not once per extension")
    void descriptorListingsAreFetchedOncePerStore() throws Exception {
        bodyByPath.put("/llmstore/llms/" + LLM_ID, "{\"model\":\"a\"}");
        bodyByPath.put("/llmstore/llms/" + READABLE_LLM_ID, "{\"model\":\"b\"}");
        stubAgentWithOneWorkflow();
        stubWorkflowWithTwoLlmSteps();
        when(jsonSerialization.deserialize(anyString(), eq(DocumentDescriptor[].class)))
                .thenReturn(new DocumentDescriptor[0]);

        var source = new RemoteApiResourceSource(BASE_URL, AGENT_ID, 1, null, jsonSerialization, httpClient);
        List<WorkflowSourceData> workflows = source.readWorkflows();

        assertEquals(2, workflows.getFirst().extensions().size(), "both extensions were read");
        assertEquals(1, requestCount("/llmstore/llms/descriptors"),
                "the LLM descriptor listing must be downloaded once, was: " + requestedPaths);
        assertEquals(1, requestCount("/workflowstore/workflows/descriptors"),
                "and the workflow listing likewise, was: " + requestedPaths);
    }

    // ==================== Helpers ====================

    private void stubAgentWithOneWorkflow() throws Exception {
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create(
                "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1")));
        when(jsonSerialization.deserialize(anyString(), eq(AgentConfiguration.class))).thenReturn(agentConfig);
    }

    /**
     * Two steps of the same type, so the occurrence ordinal in the extension key is
     * load-bearing: {@code #0} is the one the remote refuses, {@code #1} the one it
     * hands over.
     */
    private void stubWorkflowWithTwoLlmSteps() throws Exception {
        var workflowConfig = new WorkflowConfiguration();
        workflowConfig.setWorkflowSteps(new ArrayList<>(List.of(
                llmStep(LLM_ID), llmStep(READABLE_LLM_ID))));
        when(jsonSerialization.deserialize(anyString(), eq(WorkflowConfiguration.class))).thenReturn(workflowConfig);
    }

    private static WorkflowConfiguration.WorkflowStep llmStep(String llmId) {
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.llm"));
        step.setConfig(new HashMap<>(Map.of("uri",
                "eddi://ai.labs.llm/llmstore/llms/" + llmId + "?version=1")));
        step.setExtensions(new HashMap<>());
        return step;
    }

    private long requestCount(String pathFragment) {
        return requestedPaths.stream().filter(path -> path.contains(pathFragment)).count();
    }
}
