/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.ResolvedRequest;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import ai.labs.eddi.modules.llm.tools.impl.CalculatorTool;
import ai.labs.eddi.modules.llm.tools.impl.DataFormatterTool;
import ai.labs.eddi.modules.llm.tools.impl.DateTimeTool;
import ai.labs.eddi.modules.llm.tools.impl.FetchToolResponsePageTool;
import ai.labs.eddi.modules.llm.tools.impl.PdfReaderTool;
import ai.labs.eddi.modules.llm.tools.impl.TextSummarizerTool;
import ai.labs.eddi.modules.llm.tools.impl.WeatherTool;
import ai.labs.eddi.modules.llm.tools.impl.WebScraperTool;
import ai.labs.eddi.modules.llm.tools.impl.WebSearchTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code lazySources: ["http"]} end to end, through the real tool assembly
 * (workflow → apicalls configurations → {@link HttpCallToolsProvider}) and the
 * real tool loop and resume path, against a 66-tool API split into six groups:
 * what each model request carries, the refusal of a tool the model did not
 * load, the approval gate on a loaded one, and a resume that runs the approved
 * call without discovering it again.
 */
@DisplayName("LAZY http tools (lazySources)")
class LazyExternalToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int GROUPS = 6;
    private static final int TOOLS_PER_GROUP = 11;

    private final List<String> executed = new ArrayList<>();
    private AgentOrchestrator orchestrator;
    private ConversationMemory memory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        WorkflowTraversal.clearCache();

        var agentStore = mock(IAgentStore.class);
        var workflowStore = mock(IWorkflowStore.class);
        var resourceClientLibrary = mock(IResourceClientLibrary.class);
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1")));
        when(agentStore.read("agent-1", 1)).thenReturn(agentConfig);
        var workflow = new WorkflowConfiguration();
        for (int g = 0; g < GROUPS; g++) {
            var step = new WorkflowConfiguration.WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.httpcalls"));
            step.getConfig().put("uri", "eddi://ai.labs.apicalls/apicallstore/apicalls/group" + g + "?version=1");
            workflow.getWorkflowSteps().add(step);
            when(resourceClientLibrary.getResource(URI.create("eddi://ai.labs.apicalls/apicallstore/apicalls/group" + g + "?version=1"),
                    ApiCallsConfiguration.class)).thenReturn(group(g));
        }
        when(workflowStore.read("wf1", 1)).thenReturn(workflow);

        IJsonSerialization json = mock(IJsonSerialization.class);
        when(json.deserialize(anyString(), any(Class.class))).thenAnswer(inv -> MAPPER.readValue((String) inv.getArgument(0), Map.class));
        when(json.serialize(any())).thenAnswer(inv -> MAPPER.writeValueAsString(inv.getArgument(0)));
        IMemoryItemConverter converter = mock(IMemoryItemConverter.class);
        when(converter.convert(any())).thenAnswer(inv -> new HashMap<String, Object>());

        IApiCallExecutor apiCallExecutor = mock(IApiCallExecutor.class);
        when(apiCallExecutor.execute(any(ApiCall.class), any(), any(), any())).thenAnswer(inv -> {
            executed.add(((ApiCall) inv.getArgument(0)).getName());
            return Map.of("status", 200);
        });
        when(apiCallExecutor.resolve(any(ApiCall.class), any(), any(), any())).thenAnswer(inv -> ResolvedRequest.of(
                ((ApiCall) inv.getArgument(0)).getRequest().getMethod(), "http://api.example.com/x", Map.of(), Map.of(), "{}", true));

        var toolExecutionService = mock(ToolExecutionService.class);
        when(toolExecutionService.executeToolWrapped(any(ToolInvocation.class), anyString(), nullable(String.class), any(), any(Supplier.class),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt(), anyInt())).thenAnswer(inv -> ((Supplier<String>) inv.getArgument(4)).get());
        var tenantQuotaService = mock(TenantQuotaService.class);
        when(tenantQuotaService.getDefaultTenantId()).thenReturn("t");
        when(tenantQuotaService.checkCostBudget(any(), anyDouble())).thenReturn(QuotaCheckResult.OK);
        var journalStore = mock(IHitlToolJournalStore.class);
        when(journalStore.tryClaim(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        var truncator = mock(ToolResponseTruncator.class);
        when(truncator.truncateIfNeeded(any(), anyString(), anyString(), any(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(2));

        orchestrator = new AgentOrchestrator(mock(CalculatorTool.class), mock(DateTimeTool.class), mock(WebSearchTool.class),
                mock(DataFormatterTool.class), mock(WebScraperTool.class), mock(TextSummarizerTool.class), mock(PdfReaderTool.class),
                mock(WeatherTool.class), mock(FetchToolResponsePageTool.class), toolExecutionService, mock(McpToolProviderManager.class),
                mock(A2AToolProviderManager.class), workflowStore, resourceClientLibrary, apiCallExecutor, json, converter,
                mock(IUserMemoryStore.class), truncator, tenantQuotaService, mock(MemorySnapshotService.class), null, null, null, null, agentStore,
                journalStore, new ConversationHistoryBuilder(), new TokenCounterFactory());

        memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
    }

    /**
     * One apicalls configuration: a read and ten writes-or-reads with a realistic
     * shape.
     */
    private static ApiCallsConfiguration group(int g) {
        var config = new ApiCallsConfiguration();
        config.setTargetServerUrl("http://api.example.com");
        config.setDescription("Area" + g + ": find, read, create, change and delete area-" + g + " resources");
        List<ApiCall> calls = new ArrayList<>();
        for (int t = 0; t < TOOLS_PER_GROUP; t++) {
            String method = t == 0 ? "GET" : t % 2 == 0 ? "POST" : "GET";
            var request = new Request();
            request.setMethod(method);
            request.setPath("/area" + g + "/resources/{resourceId}/op" + t);
            var call = new ApiCall();
            call.setName((method.equals("POST") ? "change" : "read") + "Area" + g + "Op" + t);
            call.setDescription(method + " /area" + g + "/resources/{resourceId}/op" + t + ": "
                    + (method.equals("POST") ? "Changes" : "Reads") + " the op" + t + " settings of one area-" + g
                    + " resource, identified by its id, and returns the resource as it is afterwards.");
            Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put("resourceId", "The id of the resource, as returned by the find operation of this area.");
            parameters.put("body", "The complete JSON request body. Properties — name (string): display name; enabled (boolean): "
                    + "whether it is active; tags (array of string): free labels.");
            call.setParameters(parameters);
            call.setRequest(request);
            calls.add(call);
        }
        config.setHttpCalls(calls);
        return config;
    }

    private LlmConfiguration.Task task(boolean lazyHttp) {
        var task = new LlmConfiguration.Task();
        task.setId("llmTask-A");
        task.setEnableBuiltInTools(false);
        task.setEnableHttpCallTools(true);
        task.setEnableMcpCallTools(false);
        task.setMaxToolContextTokens(-1);
        if (lazyHttp) {
            task.setToolLoadingStrategy(LlmConfiguration.ToolLoadingStrategy.LAZY);
            task.setLazySources(List.of("http"));
        }
        return task;
    }

    private static ToolApprovalsConfig gatePosts() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("http.post:*"));
        return cfg;
    }

    private static ToolExecutionRequest call(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }

    /** Replays a script of responses and records every request it is sent. */
    private static final class ScriptedModel implements ChatModel {
        final List<ChatRequest> requests = new ArrayList<>();
        private final Deque<ChatResponse> script = new ArrayDeque<>();

        ScriptedModel then(ToolExecutionRequest... calls) {
            script.add(ChatResponse.builder().aiMessage(AiMessage.from(List.of(calls))).build());
            return this;
        }

        ScriptedModel thenAnswer(String text) {
            script.add(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
            return this;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            return script.isEmpty() ? ChatResponse.builder().aiMessage(AiMessage.from("done")).build() : script.poll();
        }

        List<Integer> specCounts() {
            return requests.stream().map(r -> r.toolSpecifications() == null ? 0 : r.toolSpecifications().size()).toList();
        }

        List<String> toolResults() {
            List<String> results = new ArrayList<>();
            for (ChatMessage message : requests.get(requests.size() - 1).messages()) {
                if (message instanceof ToolExecutionResultMessage result) {
                    results.add(result.text());
                }
            }
            return results;
        }
    }

    private AgentOrchestrator.ExecutionResult run(ScriptedModel model, LlmConfiguration.Task task, ToolApprovalsConfig approvals)
            throws Exception {
        return orchestrator.executeIfToolsEnabled(model, "You operate the API.", List.of(UserMessage.from("rename resource 7 in area 2")), task,
                memory, approvals, 0);
    }

    // ─── what each request carries ───

    @Test
    @DisplayName("eager: all 66 specs on every request; lazy: discover_tools, then only the group it loaded")
    void specsPerModelCall() throws Exception {
        var eager = new ScriptedModel().then(call("c1", "readArea2Op1", "{\"resourceId\":\"7\"}")).thenAnswer("ok");
        run(eager, task(false), null);
        assertEquals(List.of(66, 66), eager.specCounts());

        var lazy = new ScriptedModel().then(call("c1", "discover_tools", "{\"group\":\"group2\"}"))
                .then(call("c2", "readArea2Op1", "{\"resourceId\":\"7\"}")).thenAnswer("ok");
        run(lazy, task(true), null);
        assertEquals(List.of(1, 1 + TOOLS_PER_GROUP, 1 + TOOLS_PER_GROUP), lazy.specCounts());
        assertEquals(List.of("readArea2Op1", "readArea2Op1"), executed, "the loaded tool runs exactly like the eager one");

        ToolSpecification discover = lazy.requests.get(0).toolSpecifications().get(0);
        assertEquals("discover_tools", discover.name());
        for (int g = 0; g < GROUPS; g++) {
            assertTrue(discover.description().contains("group" + g + " (11 tools): Area" + g + ": find, read"), discover.description());
        }

        // Measured for the PR description: the tool part of the first request.
        var estimator = new OpenAiTokenCountEstimator("gpt-4o");
        int eagerChars = specChars(eager.requests.get(0).toolSpecifications());
        int lazyChars = specChars(lazy.requests.get(0).toolSpecifications());
        System.out.printf("LAZY-MEASURE eager: %d specs, %d chars, ~%d tokens | lazy first request: %d specs, %d chars, ~%d tokens | "
                + "after loading one group: %d specs, %d chars, ~%d tokens%n",
                eager.requests.get(0).toolSpecifications().size(), eagerChars, estimator.estimateTokenCountInText(specText(eager.requests.get(0)
                        .toolSpecifications())),
                1, lazyChars, estimator.estimateTokenCountInText(specText(lazy.requests.get(0).toolSpecifications())),
                lazy.requests.get(1).toolSpecifications().size(), specChars(lazy.requests.get(1).toolSpecifications()),
                estimator.estimateTokenCountInText(specText(lazy.requests.get(1).toolSpecifications())));
        assertTrue(lazyChars * 5 < eagerChars, "lazy first request " + lazyChars + " chars vs eager " + eagerChars);
    }

    private static String specText(List<ToolSpecification> specs) {
        var text = new StringBuilder();
        for (ToolSpecification spec : specs) {
            text.append(spec.name()).append('\n').append(spec.description()).append('\n')
                    .append(spec.parameters() != null ? spec.parameters().toString() : "").append('\n');
        }
        return text.toString();
    }

    private static int specChars(List<ToolSpecification> specs) {
        return specText(specs).length();
    }

    // ─── safety parity ───

    @Test
    @DisplayName("a call to a tool discover_tools did not load is refused and never executed")
    void unactivatedCallIsRefused() throws Exception {
        var model = new ScriptedModel().then(call("c1", "readArea2Op1", "{\"resourceId\":\"7\"}")).thenAnswer("I need to look first.");

        var result = run(model, task(true), null);

        assertTrue(executed.isEmpty(), "never executed: " + executed);
        String refusal = model.toolResults().get(0);
        assertTrue(refusal.contains("NOT_EXECUTED") && refusal.contains("call discover_tools first"), refusal);
        assertTrue(result.trace().stream().anyMatch(s -> "tool_not_activated".equals(s.get("type"))), result.trace().toString());
    }

    @Test
    @DisplayName("a refused un-activated write never reaches the approval gate — no pause for a call that cannot run")
    void unactivatedGatedCallDoesNotPause() throws Exception {
        var model = new ScriptedModel().then(call("c1", "changeArea2Op2", "{\"resourceId\":\"7\",\"body\":\"{}\"}")).thenAnswer("ok");

        run(model, task(true), gatePosts());

        assertTrue(executed.isEmpty());
        assertTrue(memory.getHitlPendingToolCalls() == null, "no pause was recorded");
    }

    @Test
    @DisplayName("a loaded write still pauses for approval, exactly as the eager tool would")
    void activatedGatedCallPauses() {
        var model = new ScriptedModel().then(call("c1", "discover_tools", "{\"group\":\"group2\"}"))
                .then(call("c2", "changeArea2Op2", "{\"resourceId\":\"7\",\"body\":\"{}\"}"));

        var pause = assertThrows(ToolApprovalRequiredException.class, () -> run(model, task(true), gatePosts()));

        assertTrue(executed.isEmpty(), "a gated call does not run before approval");
        PendingToolCallBatch batch = pause.getBatch();
        assertEquals("changeArea2Op2", batch.getCalls().get(0).getToolName());
        assertTrue(batch.getActivatedToolNames().contains("changeArea2Op2"), batch.getActivatedToolNames().toString());
    }

    @Test
    @DisplayName("resume runs the approved call by name although the resumed turn has discovered nothing")
    void resumeExecutesWithoutRediscovery() throws Exception {
        var approved = call("c2", "changeArea2Op2", "{\"resourceId\":\"7\",\"body\":\"{}\"}");
        var model = new ScriptedModel().thenAnswer("renamed");

        var decision = new HitlDecision();
        decision.setVerdict(HitlDecision.HitlVerdict.APPROVED);
        decision.setDecidedBy("reviewer-1");
        var result = orchestrator.resumeToolLoop(model, task(true), memory, batchFor(approved), decision, true);

        assertEquals(List.of("changeArea2Op2"), executed);
        assertEquals("renamed", result.response());
        assertFalse(model.toolResults().get(0).contains("NOT_EXECUTED"), model.toolResults().get(0));
    }

    private static PendingToolCallBatch batchFor(ToolExecutionRequest request) {
        var gated = new PendingToolCallBatch.PendingToolCall();
        gated.setCallId(request.id());
        gated.setToolName(request.name());
        gated.setSource("http");
        gated.setArgumentsRaw(request.arguments());
        gated.setArgsTruncated(false);
        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        batch.setLlmTaskId("llmTask-A");
        batch.setLlmTaskIndex(0);
        batch.setIterationIndex(0);
        // Nothing recorded as active: the resumed turn has to run the call by name.
        batch.setActivatedToolNames(List.of());
        batch.setPauseCountThisTurn(1);
        batch.setAutoApproveCount(0);
        batch.setCalls(List.of(gated));
        batch.setTraceSoFar(new ArrayList<>());
        List<ChatMessage> transcript = new ArrayList<>();
        transcript.add(UserMessage.from("rename it"));
        transcript.add(AiMessage.from(List.of(request)));
        var serialized = new ChatTranscriptCodec().serialize(transcript, PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT);
        batch.setChatTranscriptJson(serialized.json());
        batch.setTranscriptOmitted(serialized.omitted());
        return batch;
    }

    // ─── retention ───

    @Test
    @DisplayName("lazyRetention: turn (default) starts the next turn narrow again; conversation carries the loaded tools")
    void retention() throws Exception {
        var turnTask = task(true);
        run(new ScriptedModel().then(call("c1", "discover_tools", "{\"group\":\"group2\"}")).thenAnswer("ok"), turnTask, null);
        memory.startNextStep();
        var nextTurn = new ScriptedModel().thenAnswer("ok");
        run(nextTurn, turnTask, null);
        assertEquals(List.of(1), nextTurn.specCounts());

        var conversationTask = task(true);
        conversationTask.setLazyRetention("conversation");
        // A fresh conversation: the first one's record would carry over too.
        memory = new ConversationMemory("conv-2", "agent-1", 1, "user-1");
        run(new ScriptedModel().then(call("c1", "discover_tools", "{\"group\":\"group3\"}")).thenAnswer("ok"), conversationTask, null);
        memory.startNextStep();
        var carried = new ScriptedModel().thenAnswer("ok");
        run(carried, conversationTask, null);
        assertEquals(List.of(1 + TOOLS_PER_GROUP), carried.specCounts());
        assertNotNull(carried.requests.get(0).toolSpecifications().stream().filter(s -> s.name().equals("readArea3Op1")).findFirst()
                .orElse(null));
    }

    @Test
    @DisplayName("maxToolsInContext caps what one discovery loads")
    void capApplies() throws Exception {
        var task = task(true);
        task.setMaxToolsInContext(4);
        var model = new ScriptedModel().then(call("c1", "discover_tools", "{\"group\":\"group1\"}")).thenAnswer("ok");

        run(model, task, null);

        assertEquals(List.of(1, 1 + 4), model.specCounts());
    }

    @Test
    @DisplayName("keyword search across groups loads the matching tools only")
    void keywordSearch() throws Exception {
        var model = new ScriptedModel().then(call("c1", "discover_tools", "{\"keywords\":\"changeArea4Op4\"}")).thenAnswer("ok");

        run(model, task(true), null);

        var loaded = model.requests.get(1).toolSpecifications().stream().map(ToolSpecification::name).toList();
        assertEquals("changeArea4Op4", loaded.get(1));
        assertTrue(loaded.size() < 1 + TOOLS_PER_GROUP, loaded.toString());
    }
}
