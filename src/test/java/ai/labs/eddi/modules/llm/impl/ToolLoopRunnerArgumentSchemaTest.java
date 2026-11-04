/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.guardrails.ToolArgumentSchemaValidator;
import ai.labs.eddi.modules.llm.guardrails.ToolResultGuardrail;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolApprovalGateSupport;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolCacheService;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolRateLimiter;
import ai.labs.eddi.modules.llm.tools.spi.ToolArgumentValidator;
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
import dev.langchain4j.service.tool.ToolExecutor;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Argument-schema refusals happen before the approval gate: a malformed call is
 * answered NOT_EXECUTED, never paused for a human and never executed.
 */
class ToolLoopRunnerArgumentSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOOL = "invite";

    private ToolApprovalGate gate;
    private ToolLoopRunner runner;
    private LlmConfiguration.Task task;
    private final AtomicInteger executed = new AtomicInteger();
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        registry = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(registry);
        ToolCacheService cache = new ToolCacheService();
        setField(cache, "cacheFactory", new CacheFactory());
        setField(cache, "meterRegistry", new SimpleMeterRegistry());
        cache.init();
        ToolRateLimiter rateLimiter = new ToolRateLimiter();
        setField(rateLimiter, "meterRegistry", new SimpleMeterRegistry());
        rateLimiter.init();
        ToolCostTracker costTracker = new ToolCostTracker();
        setField(costTracker, "meterRegistry", new SimpleMeterRegistry());
        costTracker.init();
        ToolExecutionService service = new ToolExecutionService();
        setField(service, "cacheService", cache);
        setField(service, "rateLimiter", rateLimiter);
        setField(service, "costTracker", costTracker);
        setField(service, "meterRegistry", new SimpleMeterRegistry());
        service.init();
        ToolResponseTruncator truncator = mock(ToolResponseTruncator.class);
        lenient().when(truncator.truncateIfNeeded(any(), any(), anyString(), any(), any(), any(), any())).thenAnswer(i -> i.getArgument(2));
        gate = spy(new ToolApprovalGate());
        runner = new ToolLoopRunner(service, truncator, null, null, gate, new ToolApprovalGateSupport(new ChatTranscriptCodec()), null,
                new ToolResultGuardrail(new SimpleMeterRegistry()));
        task = new LlmConfiguration.Task();
        task.setId("arg-schema");
        task.setMaxToolContextTokens(-1);
        task.setEnableToolCaching(false);
    }

    @AfterEach
    void tearDown() {
        Metrics.globalRegistry.remove(registry);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static IConversationMemory memory() {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getUserId()).thenReturn("user-1");
        when(memory.getAgentId()).thenReturn("agent-a");
        when(memory.getConversationId()).thenReturn("conv-1");
        return memory;
    }

    /** An executor that checks the same schema an httpcall tool would. */
    private interface CheckingExecutor extends ToolExecutor, ToolArgumentValidator {
    }

    @SuppressWarnings("unchecked")
    private ToolExecutor schemaExecutor() throws Exception {
        Map<String, Object> schema = MAPPER.readValue("""
                {"type":"object","required":["requestBody"],"properties":{"requestBody":{"type":"object",
                  "required":["emails","role"],"properties":{"emails":{"type":"array","items":{"type":"string"}},
                  "role":{"type":"string","enum":["manager","curator"]}}}}}""", Map.class);
        return new CheckingExecutor() {
            @Override
            public List<String> argumentViolations(ToolExecutionRequest request) {
                return ToolArgumentSchemaValidator.check(schema, null, request.arguments()).violations();
            }

            @Override
            public String execute(ToolExecutionRequest request, Object memoryId) {
                executed.incrementAndGet();
                return "{\"httpCode\":201}";
            }
        };
    }

    private AgentOrchestrator.ToolSetup toolSetup(ToolExecutor executor) {
        var spec = ToolSpecification.builder().name(TOOL).description("invite").build();
        return new AgentOrchestrator.ToolSetup(List.of(spec), new HashMap<>(Map.of(TOOL, executor)), new HashMap<>(Map.of(TOOL, "http")),
                List.of(spec), new HashMap<>(), new HashMap<>(Map.of(TOOL, "post:/teams/invites")), new HashMap<>());
    }

    private static ChatResponse calls(String... arguments) {
        List<ToolExecutionRequest> requests = new ArrayList<>();
        for (int i = 0; i < arguments.length; i++) {
            requests.add(ToolExecutionRequest.builder().id("c" + requests.size() + "_" + arguments[i].hashCode()).name(TOOL)
                    .arguments(arguments[i]).build());
        }
        return ChatResponse.builder().aiMessage(AiMessage.from(requests)).build();
    }

    private static ToolApprovalsConfig gateEveryPost() {
        var approvals = new ToolApprovalsConfig();
        approvals.setRequireApproval(List.of("http.post:*"));
        return approvals;
    }

    private static final String MISSPELLED = "{\"requestBody\":{\"emials\":[\"a@x\"],\"role\":\"manager\"}}";
    private static final String CORRECTED = "{\"requestBody\":{\"emails\":[\"a@x\"],\"role\":\"manager\"}}";

    /**
     * The replay: the model misspells a body field, is refused with a message that
     * names the field and the declared ones, and corrects it on the next iteration;
     * only the corrected call reaches the gate (and pauses for approval).
     */
    @Test
    void misspelledFieldIsRefusedBeforeTheGateAndTheCorrectedCallIsGated() throws Exception {
        List<List<ChatMessage>> seen = new ArrayList<>();
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            seen.add(new ArrayList<>(request.messages()));
            return seen.size() == 1 ? calls(MISSPELLED) : calls(CORRECTED);
        });

        var pause = assertThrows(ToolApprovalRequiredException.class, () -> runner.executeWithTools(model, null,
                List.of(UserMessage.from("invite a@x as manager")), toolSetup(schemaExecutor()), task, memory(), gateEveryPost(), 0, 0, null));

        // the refusal the model read on its second iteration
        ToolExecutionResultMessage refusal = seen.get(1).stream().filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast).findFirst().orElseThrow();
        var envelope = MAPPER.readTree(refusal.text());
        assertEquals("NOT_EXECUTED", envelope.get("status").asText());
        assertTrue(envelope.get("violations").toString().contains("$.requestBody.emails: required"), refusal.text());
        assertTrue(envelope.get("violations").toString().contains("$.requestBody.emials: unknown field (declared fields: emails, role)"),
                refusal.text());

        // the gate saw only the corrected call, once
        verify(gate, times(1)).classify(anyList(), any(), any(), any(), any());
        verify(gate).classify(argThat((List<ToolExecutionRequest> batch) -> batch.size() == 1 && CORRECTED.equals(batch.getFirst().arguments())),
                any(), any(), any(), any());
        assertEquals(CORRECTED, pause.getBatch().getCalls().getFirst().getArgumentsRaw());
        assertEquals(0, executed.get(), "nothing executed: the bad call was refused, the good one is waiting for approval");
        assertTrue(pause.getBatch().getTraceSoFar().stream()
                .anyMatch(t -> "tool_error".equals(t.get("type")) && "argument_schema".equals(t.get("error"))),
                "trace: " + pause.getBatch().getTraceSoFar());
        assertEquals(1.0, registry.counter("eddi.tool.arguments.refused", "source", "http").count());
    }

    @Test
    void aBatchOfOnlyInvalidCallsNeverReachesTheGate() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(calls(MISSPELLED, "{\"requestBody\":{\"emails\":[],\"role\":\"boss\"}}"))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("I could not send the invite.")).build());

        var result = runner.executeWithTools(model, null, List.of(UserMessage.from("go")), toolSetup(schemaExecutor()), task, memory(),
                gateEveryPost(), 0, 0, null);

        assertEquals("I could not send the invite.", result.response());
        verify(gate, never()).classify(anyList(), any(), any(), any(), any());
        assertEquals(0, executed.get());
        assertEquals(2.0, registry.counter("eddi.tool.arguments.refused", "source", "http").count());
    }

    @Test
    void aMixedBatchRefusesTheBadCallAndGatesTheGoodOne() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(calls(MISSPELLED, CORRECTED));

        var pause = assertThrows(ToolApprovalRequiredException.class, () -> runner.executeWithTools(model, null,
                List.of(UserMessage.from("go")), toolSetup(schemaExecutor()), task, memory(), gateEveryPost(), 0, 0, null));

        verify(gate).classify(argThat((List<ToolExecutionRequest> batch) -> batch.size() == 1 && CORRECTED.equals(batch.getFirst().arguments())),
                any(), any(), any(), any());
        assertEquals(1, pause.getBatch().getCalls().size());
        assertEquals(CORRECTED, pause.getBatch().getCalls().getFirst().getArgumentsRaw());
        assertTrue(pause.getBatch().getTraceSoFar().stream()
                .anyMatch(t -> "tool_error".equals(t.get("type")) && "argument_schema".equals(t.get("error"))));
        assertEquals(0, executed.get());
    }

    @Test
    void aValidatorThatThrowsRefusesTheCall() throws Exception {
        ToolExecutor throwing = new CheckingExecutor() {
            @Override
            public List<String> argumentViolations(ToolExecutionRequest request) {
                throw new IllegalStateException("boom");
            }

            @Override
            public String execute(ToolExecutionRequest request, Object memoryId) {
                executed.incrementAndGet();
                return "ok";
            }
        };
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(calls(CORRECTED))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("done")).build());

        runner.executeWithTools(model, null, List.of(UserMessage.from("go")), toolSetup(throwing), task, memory(), gateEveryPost(), 0, 0,
                null);

        verify(gate, never()).classify(anyList(), any(), any(), any(), any());
        assertEquals(0, executed.get());
    }

    @Test
    void anExecutorWithoutAValidatorIsNeverRefused() throws Exception {
        ToolExecutor plain = (request, memoryId) -> {
            executed.incrementAndGet();
            return "ok";
        };
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(calls(MISSPELLED))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("done")).build());

        var result = runner.executeWithTools(model, null, List.of(UserMessage.from("go")), toolSetup(plain), task, memory(), null, 0, 0, null);

        assertEquals("done", result.response());
        assertEquals(1, executed.get());
    }
}
