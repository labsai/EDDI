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
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.httpclient.ICompleteListener;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.DataFactory;
import ai.labs.eddi.engine.memory.MemoryItemConverter;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.apicalls.impl.ApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.apicalls.impl.RequestRedactor;
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
import ai.labs.eddi.modules.properties.impl.SecretPropertyVault;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import ai.labs.eddi.secrets.SecretResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An approved, pinned, gated httpcall whose header reads a secret context value
 * ({@code Bearer {context.userToken}}), driven through the real resume loop
 * with the real httpcall provider, the real {@link ApiCallExecutor} resolution
 * and a real Qute engine; only the HTTP client is replaced, by a recorder, so
 * the header that would go on the wire is asserted.
 * <p>
 * The pin was taken while the token was live (the gate runs in the turn that
 * carried it). On resume the token is either re-supplied (the live
 * {@code Context} is back in the step, which is what the resume's injection
 * restores) or it is the placeholder, as before the feature.
 */
@DisplayName("Resume: approved httpcall that uses a secret context header")
class ResumeSecretContextApprovedCallTest {

    private static final String TOKEN = "tok-aaaa-bbbb-cccc-1111";
    private static final String OTHER_TOKEN = "tok-zzzz-yyyy-xxxx-2222";
    private static final String PLACEHOLDER = MemoryKeys.SECRET_CONTEXT_PLACEHOLDER;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IHttpClient httpClient;
    private final List<RecordingRequest> sent = new ArrayList<>();
    private IHitlToolJournalStore journalStore;
    private AgentOrchestrator orchestrator;
    private ConversationMemory memory;

    /** Records what the executor builds: headers, query, body, uri, method. */
    static final class RecordingRequest implements IRequest {
        private final URI uri;
        private final IHttpClient.Method method;
        final Map<String, String> headers = new LinkedHashMap<>();
        final Map<String, String> query = new LinkedHashMap<>();
        String body;
        boolean wasSent;

        RecordingRequest(URI uri, IHttpClient.Method method) {
            this.uri = uri;
            this.method = method;
        }

        @Override
        public IRequest setBasicAuthentication(String username, String password, String realm, boolean preemptive) {
            return this;
        }

        @Override
        public IRequest setQueryParam(String key, String value) {
            query.put(key, value);
            return this;
        }

        @Override
        public IRequest setUserAgent(String userAgent) {
            return this;
        }

        @Override
        public IRequest setHttpHeader(String headerName, String value) {
            headers.put(headerName, value);
            return this;
        }

        @Override
        public IRequest setBodyEntity(String content, String encoding, String contentType) {
            body = content;
            return this;
        }

        @Override
        public IRequest setMaxResponseSize(int byteSize) {
            return this;
        }

        @Override
        public IRequest setTimeout(long timeout, TimeUnit timeUnit) {
            return this;
        }

        @Override
        public IRequest setFollowRedirects(boolean follow) {
            return this;
        }

        @Override
        public IResponse send() {
            wasSent = true;
            IResponse response = mock(IResponse.class);
            when(response.getHttpCode()).thenReturn(200);
            when(response.getContentAsString()).thenReturn("{\"ok\":true}");
            when(response.getHttpHeader()).thenReturn(new HashMap<>());
            return response;
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put(KEY_URI, uri.toString());
            map.put(KEY_METHOD, method.name());
            map.put(KEY_HEADERS, new HashMap<>(headers));
            map.put(KEY_QUERY_PARAMS, new HashMap<>(query));
            if (body != null) {
                map.put(KEY_BODY, body);
            }
            return map;
        }

        @Override
        public void send(ICompleteListener completeListener) {
            throw new UnsupportedOperationException();
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        WorkflowTraversal.clearCache();

        httpClient = mock(IHttpClient.class);
        when(httpClient.newRequest(any(URI.class), any(IHttpClient.Method.class))).thenAnswer(inv -> {
            var request = new RecordingRequest(inv.getArgument(0), inv.getArgument(1));
            sent.add(request);
            return request;
        });

        IJsonSerialization json = mock(IJsonSerialization.class);
        when(json.deserialize(anyString(), any(Class.class))).thenAnswer(inv -> MAPPER.readValue((String) inv.getArgument(0), Map.class));
        when(json.serialize(any())).thenAnswer(inv -> MAPPER.writeValueAsString(inv.getArgument(0)));

        SecretResolver secretResolver = mock(SecretResolver.class);
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.resolveValue(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(callerIdentityResolver.redactCallerToken(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(0));

        MemoryItemConverter converter = new MemoryItemConverter();
        var realEngine = Engine.builder().addDefaults().strictRendering(false).build();
        var prePostUtils = new PrePostUtils(json, converter, new TemplatingEngine(realEngine), new DataFactory(),
                mock(SecretPropertyVault.class));
        var executor = new ApiCallExecutor(httpClient, json, mock(IRuntime.class), prePostUtils, globalVariableResolver, secretResolver,
                callerIdentityResolver, mock(CallerIdentityContext.class), new RequestRedactor(callerIdentityResolver), null, false, 30_000L,
                2_000_000);

        // The agent -> workflow -> httpcalls config chain the provider discovers.
        var agentStore = mock(IAgentStore.class);
        var workflowStore = mock(IWorkflowStore.class);
        var resourceClientLibrary = mock(IResourceClientLibrary.class);
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1")));
        when(agentStore.read("agent-1", 1)).thenReturn(agentConfig);
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.httpcalls"));
        step.getConfig().put("uri", "eddi://ai.labs.apicalls/apicallstore/apicalls/c1?version=1");
        var workflow = new WorkflowConfiguration();
        workflow.getWorkflowSteps().add(step);
        when(workflowStore.read("wf1", 1)).thenReturn(workflow);

        var request = new Request();
        request.setMethod("POST");
        request.setPath("/items/{itemId}");
        request.setHeaders(new LinkedHashMap<>(Map.of("Authorization", "Bearer {context.userToken}")));
        request.setBody("{\"note\":\"{context.note}\"}");
        var call = new ApiCall();
        call.setName("updateItem");
        call.setDescription("Update an item as the signed-in user");
        call.setParameters(Map.of("itemId", "the item"));
        call.setSaveResponse(false);
        call.setFireAndForget(false);
        call.setRequest(request);
        var calls = new ApiCallsConfiguration();
        calls.setTargetServerUrl("http://api.example.com");
        calls.setHttpCalls(List.of(call));
        when(resourceClientLibrary.getResource(any(URI.class), any(Class.class))).thenReturn(calls);

        var toolExecutionService = mock(ToolExecutionService.class);
        var tenantQuotaService = mock(TenantQuotaService.class);
        when(tenantQuotaService.getDefaultTenantId()).thenReturn("t");
        when(tenantQuotaService.checkCostBudget(any())).thenReturn(QuotaCheckResult.OK);
        when(toolExecutionService.executeToolWrapped(any(ToolInvocation.class), anyString(), nullable(String.class), any(), any(Supplier.class),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt(), anyInt())).thenAnswer(inv -> ((Supplier<String>) inv.getArgument(4)).get());

        journalStore = mock(IHitlToolJournalStore.class);
        when(journalStore.tryClaim(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        var truncator = mock(ToolResponseTruncator.class);
        when(truncator.truncateIfNeeded(any(), anyString(), anyString(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(2));

        orchestrator = new AgentOrchestrator(mock(CalculatorTool.class), mock(DateTimeTool.class), mock(WebSearchTool.class),
                mock(DataFormatterTool.class), mock(WebScraperTool.class), mock(TextSummarizerTool.class), mock(PdfReaderTool.class),
                mock(WeatherTool.class), mock(FetchToolResponsePageTool.class), toolExecutionService, mock(McpToolProviderManager.class),
                mock(A2AToolProviderManager.class), workflowStore, resourceClientLibrary, executor, json, converter, mock(IUserMemoryStore.class),
                truncator, tenantQuotaService, mock(MemorySnapshotService.class), null, null, null, null, agentStore, journalStore,
                new ConversationHistoryBuilder(), new TokenCounterFactory());

        memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
    }

    private static Context context(String value, boolean secret) {
        var context = new Context(Context.ContextType.string, value);
        if (secret) {
            context.setSecret(true);
        }
        return context;
    }

    private void putContext(String key, Context value) {
        var existing = memory.getCurrentStep().<Context>getData("context:" + key);
        if (existing != null) {
            existing.setResult(value);
        } else {
            memory.getCurrentStep().storeData(new Data<>("context:" + key, value));
        }
    }

    private LlmConfiguration.Task httpTask() {
        var task = new LlmConfiguration.Task();
        task.setId("llmTask-A");
        task.setEnableBuiltInTools(false);
        task.setEnableHttpCallTools(true);
        task.setEnableMcpCallTools(false);
        return task;
    }

    private static ToolExecutionRequest toolRequest(String itemId) {
        return ToolExecutionRequest.builder().id("c1").name("updateItem").arguments("{\"itemId\":\"" + itemId + "\"}").build();
    }

    /** The fingerprint the gate pinned: resolved while the secret was live. */
    private String pinAtGateTime(String itemId) {
        var setup = orchestrator.buildToolSetup(httpTask(), memory);
        var resolver = setup.toolRequestResolvers().get("updateItem");
        assertNotNull(resolver, "the httpcall tool must be discovered with a resolver");
        try {
            return resolver.resolve(toolRequest(itemId)).fingerprint();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private PendingToolCallBatch batchFor(String itemId, String fingerprint) {
        var gated = new PendingToolCallBatch.PendingToolCall();
        gated.setCallId("c1");
        gated.setToolName("updateItem");
        gated.setSource("http");
        gated.setArgumentsRaw("{\"itemId\":\"" + itemId + "\"}");
        gated.setArgsTruncated(false);
        gated.setRequestFingerprint(fingerprint);
        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        batch.setLlmTaskId("llmTask-A");
        batch.setLlmTaskIndex(0);
        batch.setIterationIndex(0);
        batch.setActivatedToolNames(List.of());
        batch.setPauseCountThisTurn(1);
        batch.setAutoApproveCount(0);
        batch.setCalls(List.of(gated));
        batch.setTraceSoFar(new ArrayList<>());
        List<ChatMessage> transcript = new ArrayList<>();
        transcript.add(UserMessage.from("update it"));
        transcript.add(AiMessage.from(List.of(toolRequest(itemId))));
        var serialized = new ChatTranscriptCodec().serialize(transcript, PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT);
        batch.setChatTranscriptJson(serialized.json());
        batch.setTranscriptOmitted(serialized.omitted());
        return batch;
    }

    private String resume(PendingToolCallBatch batch) throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(chatModel.chat(captor.capture())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("done")).build());
        var decision = new HitlDecision();
        decision.setVerdict(HitlDecision.HitlVerdict.APPROVED);
        decision.setDecidedBy("reviewer-1");
        orchestrator.resumeToolLoop(chatModel, httpTask(), memory, batch, decision, true);
        lastToolResult = captor.getValue().messages().stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).text())
                .findFirst().orElse("");
        return lastToolResult;
    }

    private String lastToolResult = "";

    private RecordingRequest theRequestOnTheWire() {
        var onTheWire = sent.stream().filter(r -> r.wasSent).toList();
        assertEquals(1, onTheWire.size(), "exactly one request must have been sent; the model was told: " + lastToolResult);
        return onTheWire.getFirst();
    }

    @Test
    @DisplayName("re-supplied identical token: the approved call executes and carries the real Authorization header")
    void reSuppliedTokenExecutes() throws Exception {
        putContext("userToken", context(TOKEN, true));
        putContext("note", context("n1", false));
        String pin = pinAtGateTime("7");
        // The resume's injection puts the live value back in front of the resumed
        // tasks.
        sent.clear();

        String toolResult = resume(batchFor("7", pin));

        assertFalse(toolResult.contains("NOT_EXECUTED"), toolResult);
        var request = theRequestOnTheWire();
        assertEquals("Bearer " + TOKEN, request.headers.get("Authorization"));
        assertEquals("POST", request.toMap().get(IRequest.KEY_METHOD));
        assertTrue(request.toMap().get(IRequest.KEY_URI).toString().endsWith("/items/7"));
    }

    @Test
    @DisplayName("a rotated token under Authorization still matches: headers are fingerprinted redacted, and the new value is what is sent")
    void rotatedTokenInAuthorizationMatches() throws Exception {
        putContext("userToken", context(TOKEN, true));
        putContext("note", context("n1", false));
        String pin = pinAtGateTime("7");
        putContext("userToken", context(OTHER_TOKEN, true));
        sent.clear();

        resume(batchFor("7", pin));

        assertEquals("Bearer " + OTHER_TOKEN, theRequestOnTheWire().headers.get("Authorization"));
    }

    @Test
    @DisplayName("no context supplied: the call is refused as 'request changed', nothing is sent")
    void withoutContextRefused() throws Exception {
        putContext("userToken", context(TOKEN, true));
        putContext("note", context("n1", false));
        String pin = pinAtGateTime("7");
        // What the paused turn stored: the placeholder.
        putContext("userToken", context(PLACEHOLDER, true));
        sent.clear();

        String toolResult = resume(batchFor("7", pin));

        assertTrue(toolResult.contains("NOT_EXECUTED") && toolResult.contains("changed after it was approved"), toolResult);
        assertTrue(sent.stream().noneMatch(r -> r.wasSent), "nothing may be sent with the placeholder");
    }

    @Test
    @DisplayName("a changed URI is still refused even with the token re-supplied")
    void changedUriRefused() throws Exception {
        putContext("userToken", context(TOKEN, true));
        putContext("note", context("n1", false));
        String pin = pinAtGateTime("7");
        sent.clear();

        // The pin describes item 7; the resumed call would hit item 8.
        String toolResult = resume(batchFor("8", pin));

        assertTrue(toolResult.contains("NOT_EXECUTED") && toolResult.contains("changed after it was approved"), toolResult);
        assertTrue(sent.stream().noneMatch(r -> r.wasSent));
    }

    @Test
    @DisplayName("a changed body value is still refused even with the token re-supplied")
    void changedBodyRefused() throws Exception {
        putContext("userToken", context(TOKEN, true));
        putContext("note", context("n1", false));
        String pin = pinAtGateTime("7");
        putContext("note", context("n2-different", false));
        sent.clear();

        String toolResult = resume(batchFor("7", pin));

        assertTrue(toolResult.contains("NOT_EXECUTED") && toolResult.contains("changed after it was approved"), toolResult);
        assertTrue(sent.stream().noneMatch(r -> r.wasSent));
    }
}
