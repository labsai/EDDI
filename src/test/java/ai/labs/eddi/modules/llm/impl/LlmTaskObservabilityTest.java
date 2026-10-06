/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.audit.IAuditEntryCollector;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.impl.LlmCircuitBreakers.Key;
import ai.labs.eddi.modules.llm.impl.LlmCircuitBreakers.State;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CircuitBreakerConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ResponseValidation;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel.Step;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * R11 through the whole LLM task: model names in the audit and the circuit key
 * are the resolved ones, every recovery leaves exactly one structured line, and
 * a failed single-model call is counted as {@code eddi.llm.failure}.
 */
@DisplayName("LlmTask — observability (R11)")
class LlmTaskObservabilityTest {

    private static final String CONVERSATION = "conv-observability-1";
    private static final String AGENT = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String VALID = "{\"answer\":\"hi\"}";
    private static final String PROSE = "Sure, here you go.";

    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IDataFactory dataFactory;
    @Mock
    private IMemoryItemConverter memoryItemConverter;
    @Mock
    private ITemplatingEngine templatingEngine;
    @Mock
    private PrePostUtils prePostUtils;
    @Mock
    private ChatModelRegistry chatModelRegistry;
    @Mock
    private RagContextProvider ragContextProvider;
    @Mock
    private PromptSnippetService promptSnippetService;
    @Mock
    private GlobalVariableResolver globalVariableResolver;
    @Mock
    private ConversationSummarizer conversationSummarizer;
    @Mock
    private CounterweightService counterweightService;
    @Mock
    private IdentityMaskingService identityMaskingService;
    @Mock
    private AgentOrchestrator agentOrchestrator;
    @Mock
    private IConversationMemory memory;
    @Mock
    private IWritableConversationStep currentStep;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AtomicLong now = new AtomicLong(9_000_000L);
    private final Map<String, Object> templateData = new HashMap<>();
    private final List<String> recoveryLines = new ArrayList<>();
    private LlmCircuitBreakers breakers;
    private LlmTask llmTask;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        LlmRecoveryLog.observer = line -> {
            if (line.contains("conversationId=" + CONVERSATION)) {
                recoveryLines.add(line);
            }
        };
        lenient().when(promptSnippetService.getAll()).thenReturn(Map.of());
        lenient().when(promptSnippetService.getForAgent(any())).thenReturn(Map.of());
        lenient().when(globalVariableResolver.getTemplateData()).thenReturn(Map.of());
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(globalVariableResolver.resolveValue("${vars:fast-model}")).thenReturn("gemini-resolved-1");
        lenient().when(counterweightService.apply(anyString(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(identityMaskingService.apply(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(dataFactory.createData(anyString(), any())).thenAnswer(inv -> {
            IData<?> d = mock(IData.class);
            lenient().when(d.getResult()).thenAnswer(x -> inv.getArgument(1));
            return d;
        });
        lenient().when(memory.getCurrentStep()).thenReturn(currentStep);
        lenient().when(memory.getConversationId()).thenReturn(CONVERSATION);
        lenient().when(memory.getAgentId()).thenReturn(AGENT);
        lenient().when(memory.getAgentVersion()).thenReturn(3);
        lenient().when(memory.getAuditCollector()).thenReturn(mock(IAuditEntryCollector.class));
        var actionData = mock(IData.class);
        lenient().when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        lenient().when(actionData.getResult()).thenReturn(List.of("action1"));
        lenient().when(memoryItemConverter.convert(memory)).thenReturn(templateData);
        // The real engine strips the raw-section markers that protect a reference.
        lenient().when(templatingEngine.processTemplate(anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(0, String.class).replace("{|", "").replace("|}", ""));
        var inputData = mock(IData.class);
        lenient().when(currentStep.getLatestData("input")).thenReturn(inputData);
        lenient().when(inputData.getResult()).thenReturn("question");
        lenient().when(memory.getConversationProperties()).thenReturn(new ConversationProperties(memory));
        var output = new ConversationOutput();
        output.put("input", "question");
        lenient().when(memory.getConversationOutputs()).thenReturn(List.of(output));

        llmTask = new LlmTask(resourceClientLibrary, dataFactory, memoryItemConverter, templatingEngine, new JsonSerialization(new ObjectMapper()),
                prePostUtils, chatModelRegistry, mock(IApiCallExecutor.class), mock(IAgentStore.class), mock(IWorkflowStore.class),
                ragContextProvider, new TokenCounterFactory(), conversationSummarizer, promptSnippetService, globalVariableResolver,
                counterweightService, identityMaskingService, agentOrchestrator, new ConversationHistoryBuilder(), meterRegistry,
                new CallerIdentityContext(null, null));
        breakers = new LlmCircuitBreakers(meterRegistry, now::get);
        llmTask.setCircuitBreakers(breakers);
    }

    @AfterEach
    void tearDown() {
        LlmRecoveryLog.observer = null;
    }

    private static LlmConfiguration.Task task(String modelName, Consumer<ResponseValidation> validation) {
        var t = new LlmConfiguration.Task();
        t.setId("taskA");
        t.setType("openai");
        t.setActions(List.of("action1"));
        var params = new HashMap<String, String>();
        params.put("apiKey", "key");
        params.put("modelName", modelName);
        params.put("systemMessage", "You are helpful.");
        params.put("convertToObject", "true");
        t.setParameters(params);
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(1);
        t.setRetry(retry);
        var v = new ResponseValidation();
        v.setEnabled(true);
        validation.accept(v);
        t.setResponseValidation(v);
        return t;
    }

    private void run(LlmConfiguration.Task task) throws Exception {
        llmTask.execute(memory, new LlmConfiguration(List.of(task)));
    }

    private List<String> linesFor(String action) {
        return recoveryLines.stream().filter(l -> l.contains(" action=" + action + " ")).toList();
    }

    @Test
    @DisplayName("audit:model_name records the model the variable resolves to, not the ${vars:...} template")
    void auditRecordsResolvedModelName() throws Exception {
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(FaultInjectingChatModel.script(Step.text(VALID)));

        run(task("${vars:fast-model}", v -> {
        }));

        verify(dataFactory).createData(MemoryKeys.AUDIT_MODEL_NAME, "gemini-resolved-1");
        verify(dataFactory, never()).createData(MemoryKeys.AUDIT_MODEL_NAME, "${vars:fast-model}");
    }

    @Test
    @DisplayName("an unknown variable leaves the configured text, never fails the turn")
    void unresolvableReferenceKeepsTheTemplate() throws Exception {
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(FaultInjectingChatModel.script(Step.text(VALID)));
        when(globalVariableResolver.resolveValue("${vars:gone}")).thenThrow(new IllegalStateException("vault down"));

        run(task("${vars:gone}", v -> {
        }));

        verify(dataFactory).createData(MemoryKeys.AUDIT_MODEL_NAME, "${vars:gone}");
    }

    @Test
    @DisplayName("the circuit of a model named by a variable is keyed by the resolved name")
    void circuitKeyUsesTheResolvedName() throws Exception {
        var model = FaultInjectingChatModel.script(Step.status(400)).repeatLast();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        var task = task("${vars:fast-model}", v -> {
        });
        var cb = new CircuitBreakerConfig();
        cb.setEnabled(true);
        cb.setWindow(3);
        cb.setThreshold(2);
        cb.setCoolDownMs(60_000);
        task.setCircuitBreaker(cb);

        assertThrows(Exception.class, () -> run(task));
        assertThrows(Exception.class, () -> run(task));

        assertEquals(State.OPEN, breakers.stateOf(new Key(AGENT, 3, "openai", "gemini-resolved-1")));
    }

    @Test
    @DisplayName("a re-ask that fixes the reply leaves exactly one retry line, with its outcome, attempt and duration")
    void reaskLeavesOneLine() throws Exception {
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(FaultInjectingChatModel.script(Step.text(PROSE), Step.text(VALID)));

        run(task("base-model", v -> v.setOnInvalidJson("retry")));

        assertEquals(1, recoveryLines.size(), recoveryLines.toString());
        String line = linesFor("retry").get(0);
        assertTrue(line.startsWith("LLM recovery conversationId=" + CONVERSATION + " agentId=" + AGENT + " class=invalid_json action=retry "), line);
        assertTrue(line.contains(" outcome=recovered attempt=1 durationMs="), line);
    }

    @Test
    @DisplayName("an exhausted re-ask and the fallback after it are one line each, and nothing else")
    void exhaustedReaskThenFallbackIsTwoLines() throws Exception {
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(FaultInjectingChatModel.script(Step.text(PROSE)).repeatLast());

        run(task("base-model", v -> v.setOnInvalidJson("retry")));

        assertEquals(2, recoveryLines.size(), recoveryLines.toString());
        assertTrue(linesFor("retry").get(0).contains(" outcome=still_invalid "));
        assertEquals(1, linesFor("fallback").size());
        assertTrue(linesFor("fallback").get(0).contains(" outcome=served attempt=1 "));
    }

    @Test
    @DisplayName("a locally repaired reply (a code fence) leaves one repair line and counts recovery{repair}")
    void repairLeavesOneLine() throws Exception {
        when(chatModelRegistry.getOrCreate(anyString(), any()))
                .thenReturn(FaultInjectingChatModel.script(Step.text("```json\n" + VALID + "\n```")));

        run(task("base-model", v -> {
        }));

        assertEquals(1, recoveryLines.size(), recoveryLines.toString());
        assertTrue(linesFor("repair").get(0).contains(" class=invalid_json action=repair outcome=recovered attempt=1 "));
        assertEquals(1.0, meterRegistry.find("eddi.llm.recovery").tag("action", "repair").tag("outcome", "recovered").counter().count());
        assertEquals(Map.of("answer", "hi"), templateData.get("taskA"));
    }

    @Test
    @DisplayName("a clean reply leaves no recovery line")
    void cleanReplyLeavesNothing() throws Exception {
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(FaultInjectingChatModel.script(Step.text(VALID)));

        run(task("base-model", v -> v.setOnInvalidJson("retry")));

        assertTrue(recoveryLines.isEmpty(), recoveryLines.toString());
    }

    @Test
    @DisplayName("a failed single-model call counts eddi.llm.failure{class,model} with the resolved model")
    void singleModelFailureIsCounted() throws Exception {
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(FaultInjectingChatModel.script(Step.status(400)).repeatLast());

        assertThrows(Exception.class, () -> run(task("${vars:fast-model}", v -> {
        })));

        var counter = meterRegistry.find("eddi.llm.failure").tag("model", "gemini-resolved-1").counter();
        assertEquals(1.0, counter.count());
        assertEquals("BAD_REQUEST", counter.getId().getTag("class"));
    }

    @Test
    @DisplayName("a cascade that fails on every step counts one failure per step, not one more for the task")
    void cascadeFailuresAreNotCountedTwice() throws Exception {
        when(chatModelRegistry.getOrCreate(eq("openai"), any())).thenReturn(FaultInjectingChatModel.script(Step.status(400)).repeatLast());
        when(chatModelRegistry.getOrCreate(eq("anthropic"), any())).thenReturn(FaultInjectingChatModel.script(Step.status(400)).repeatLast());
        var task = task("base-model", v -> {
        });
        var cascade = new LlmConfiguration.ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("heuristic");
        var first = new LlmConfiguration.CascadeStep();
        first.setType("openai");
        var second = new LlmConfiguration.CascadeStep();
        second.setType("anthropic");
        cascade.setSteps(List.of(first, second));
        task.setModelCascade(cascade);

        assertThrows(Exception.class, () -> run(task));

        assertEquals(2.0, meterRegistry.find("eddi.llm.failure").counters().stream().mapToDouble(c -> c.count()).sum());
    }

    @Test
    @DisplayName("an open circuit is a recovery line (circuit_skip) and recovery{circuit_skip}, not a model failure")
    void openCircuitLeavesASkipLine() throws Exception {
        var model = FaultInjectingChatModel.script(Step.status(400)).repeatLast();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        var task = task("base-model", v -> {
        });
        var cb = new CircuitBreakerConfig();
        cb.setEnabled(true);
        cb.setWindow(3);
        cb.setThreshold(2);
        cb.setCoolDownMs(60_000);
        task.setCircuitBreaker(cb);
        assertThrows(Exception.class, () -> run(task));
        assertThrows(Exception.class, () -> run(task));
        double failuresBefore = meterRegistry.find("eddi.llm.failure").counters().stream().mapToDouble(c -> c.count()).sum();

        assertThrows(Exception.class, () -> run(task));

        assertEquals(1, linesFor("circuit_skip").size(), recoveryLines.toString());
        assertTrue(linesFor("circuit_skip").get(0).contains(" class=BAD_REQUEST action=circuit_skip outcome=skipped "));
        assertEquals(failuresBefore, meterRegistry.find("eddi.llm.failure").counters().stream().mapToDouble(c -> c.count()).sum(),
                "the circuit's own refusal is not a failure of the model");
    }
}
