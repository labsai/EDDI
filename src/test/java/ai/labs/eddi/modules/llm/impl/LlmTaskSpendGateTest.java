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
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.tenancy.ITenantQuotaStore;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ResponseValidation;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static ai.labs.eddi.engine.memory.MemoryKeys.AUDIT_COST;
import static ai.labs.eddi.engine.memory.MemoryKeys.AUDIT_TOKEN_USAGE;
import static ai.labs.eddi.engine.memory.MemoryKeys.LLM_SPEND_REFUSED_PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.doubleThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * The pre-model-call spend gate ({@link LlmSpendGate}) through the whole LLM
 * task, and the cost evidence the tenant budget charges.
 */
@DisplayName("LlmTask — spend gate")
class LlmTaskSpendGateTest {

    private static final String AGENT = "aaaaaaaaaaaaaaaaaaaaaaaa";

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
    private final Map<String, Object> templateData = new HashMap<>();
    private TenantQuotaService tenantQuotaService;
    private LlmTask llmTask;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        lenient().when(promptSnippetService.getAll()).thenReturn(Map.of());
        lenient().when(promptSnippetService.getForAgent(any())).thenReturn(Map.of());
        lenient().when(globalVariableResolver.getTemplateData()).thenReturn(Map.of());
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(counterweightService.apply(anyString(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(identityMaskingService.apply(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(dataFactory.createData(anyString(), any())).thenAnswer(inv -> {
            IData<?> d = mock(IData.class);
            lenient().when(d.getResult()).thenAnswer(x -> inv.getArgument(1));
            return d;
        });
        lenient().when(memory.getCurrentStep()).thenReturn(currentStep);
        lenient().when(memory.getAgentId()).thenReturn(AGENT);
        lenient().when(memory.getAgentVersion()).thenReturn(3);
        var actionData = mock(IData.class);
        lenient().when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        lenient().when(actionData.getResult()).thenReturn(List.of("action1"));
        lenient().when(memoryItemConverter.convert(memory)).thenReturn(templateData);
        lenient().when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
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
        tenantQuotaService = mock(TenantQuotaService.class);
        lenient().when(tenantQuotaService.getDefaultTenantId()).thenReturn("default");
        lenient().when(tenantQuotaService.checkCostBudget("default")).thenReturn(QuotaCheckResult.OK);
        llmTask.spendGate = new LlmSpendGate(tenantQuotaService, meterRegistry);
    }

    private static LlmConfiguration.Task task() {
        var t = new LlmConfiguration.Task();
        t.setId("taskA");
        t.setType("openai");
        t.setActions(List.of("action1"));
        var params = new HashMap<String, String>();
        params.put("apiKey", "key");
        params.put("modelName", "base-model");
        params.put("systemMessage", "You are helpful.");
        params.put("addToOutput", "true");
        t.setParameters(params);
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(1);
        t.setRetry(retry);
        return t;
    }

    private void run(LlmConfiguration.Task task) throws Exception {
        llmTask.execute(memory, new LlmConfiguration(List.of(task)));
    }

    /** A model that answers "hi" and reports 1000 input / 500 output tokens. */
    private static final class CountingModel implements ChatModel {
        int calls;

        @Override
        public ChatResponse doChat(ChatRequest request) {
            calls++;
            return ChatResponse.builder().aiMessage(AiMessage.from("hi")).tokenUsage(new TokenUsage(1000, 500)).build();
        }
    }

    @Test
    @DisplayName("tenant budget spent: the model is not called, the fallback is served without failing the turn, and the step says why")
    void spentTenantBudgetSkipsTheModel() throws Exception {
        var model = new CountingModel();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        when(tenantQuotaService.checkCostBudget("default")).thenReturn(QuotaCheckResult.denied("Monthly cost budget ($1.00) exceeded"));
        var task = task();
        var v = new ResponseValidation();
        v.setFallbackMessage("Budget reached, please contact support.");
        task.setResponseValidation(v);

        run(task); // default onError (error) — must not throw

        assertEquals(0, model.calls);
        assertEquals("Budget reached, please contact support.", templateData.get("taskA"));
        verify(dataFactory).createData(eq(LLM_SPEND_REFUSED_PREFIX + "taskA"),
                argThat(r -> r instanceof Map<?, ?> m && LlmSpendGate.REASON_TENANT_BUDGET.equals(m.get("reason"))));
        assertEquals(1.0, meterRegistry.counter(LlmSpendGate.METRIC, "reason", LlmSpendGate.REASON_TENANT_BUDGET).count());
    }

    @Test
    @DisplayName("a quota store that cannot answer refuses the model call too (fail closed, like the tool-call gate)")
    void unavailableAccountingRefuses() throws Exception {
        var model = new CountingModel();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        when(tenantQuotaService.checkCostBudget("default")).thenReturn(ITenantQuotaStore.accountingUnavailable());

        run(task());

        assertEquals(0, model.calls);
        assertEquals(ResponseValidation.DEFAULT_FALLBACK_MESSAGE, templateData.get("taskA"));
        assertEquals(1.0, meterRegistry.counter(LlmSpendGate.METRIC, "reason", LlmSpendGate.REASON_TENANT_ACCOUNTING_UNAVAILABLE).count());
    }

    @Test
    @DisplayName("within budget the model is called as before, and nothing is refused")
    void withinBudgetCallsTheModel() throws Exception {
        var model = new CountingModel();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);

        run(task());

        assertEquals(1, model.calls);
        assertEquals("hi", templateData.get("taskA"));
        verify(dataFactory, never()).createData(eq(LLM_SPEND_REFUSED_PREFIX + "taskA"), any());
    }

    @Test
    @DisplayName("audit:cost is recorded with the audit ledger OFF — it is what the tenant budget charges")
    void costIsRecordedWithoutTheAuditLedger() throws Exception {
        var model = new CountingModel();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        assertNull(memory.getAuditCollector(), "precondition: ledger off");
        var task = task();
        task.setInputPricePer1M(1.0);
        task.setOutputPricePer1M(2.0);

        run(task);

        // 1000 in x $1/1M + 500 out x $2/1M = $0.002
        verify(dataFactory).createData(eq(AUDIT_COST), doubleThat(d -> Math.abs(d - 0.002) < 1e-12));
    }

    @Test
    @DisplayName("conversation ceiling reached: the model is not called and the tenant budget is not even asked")
    void conversationCeilingSkipsTheModel() throws Exception {
        var model = new CountingModel();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        when(memory.getConversationCostCeilingUsd()).thenReturn(OptionalDouble.of(0.10));
        IConversationMemory.IConversationStepStack steps = mock(IConversationMemory.IConversationStepStack.class);
        IData<Object> spent = mock(IData.class);
        when(spent.getResult()).thenReturn(0.10);
        when(steps.getExactDataPerStep(AUDIT_COST)).thenReturn(List.of(spent));
        when(memory.getAllSteps()).thenReturn(steps);

        run(task());

        assertEquals(0, model.calls);
        assertEquals(ResponseValidation.DEFAULT_FALLBACK_MESSAGE, templateData.get("taskA"));
        verify(dataFactory).createData(eq(LLM_SPEND_REFUSED_PREFIX + "taskA"),
                argThat(r -> r instanceof Map<?, ?> m && LlmSpendGate.REASON_CONVERSATION_CEILING.equals(m.get("reason"))));
        verify(tenantQuotaService, never()).checkCostBudget(anyString());
    }

    @Test
    @DisplayName("no ceiling configured: the model is called and the conversation's cost is never summed")
    void noCeilingNeverWalksTheSteps() throws Exception {
        var model = new CountingModel();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        IConversationMemory.IConversationStepStack steps = mock(IConversationMemory.IConversationStepStack.class);
        when(memory.getAllSteps()).thenReturn(steps);

        run(task());

        assertEquals(1, model.calls);
        verify(steps, never()).getExactDataPerStep(AUDIT_COST);
    }

    /** A pause whose run spent 1000 input tokens and $0.025 before it. */
    private static ToolApprovalRequiredException pauseWithSpend(PendingToolCallBatch batch) {
        var pause = new ToolApprovalRequiredException("approve", batch);
        pause.addSpend(Map.of("inputTokens", 1000), 0.025);
        return pause;
    }

    @Test
    @DisplayName("a tool loop that pauses: what it spent so far goes on the step's audit:cost and audit:token_usage")
    void pauseRecordsTheSpendSoFar() throws Exception {
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(new CountingModel());
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenThrow(pauseWithSpend(new PendingToolCallBatch()));

        assertThrows(ToolApprovalRequiredException.class, () -> run(task()));

        // The pause snapshot persists the step, so the resume's preflight, loop
        // ceiling and total all read this.
        verify(dataFactory).createData(eq(AUDIT_COST), doubleThat(d -> Math.abs(d - 0.025) < 1e-12));
        verify(dataFactory).createData(eq(AUDIT_TOKEN_USAGE),
                argThat(u -> u instanceof Map<?, ?> m && Long.valueOf(1000).equals(m.get("inputTokens"))));
    }

    @Test
    @DisplayName("a resume that pauses again records its own segment once, not twice")
    void rePauseRecordsItsSegmentOnce() throws Exception {
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(new CountingModel());
        var batch = new PendingToolCallBatch();
        batch.setLlmTaskId("taskA");
        batch.setLlmTaskIndex(0);
        when(memory.getHitlPendingToolCalls()).thenReturn(batch);
        when(memory.getHitlResumeDecision()).thenReturn(mock(HitlDecision.class));
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(pauseWithSpend(new PendingToolCallBatch()));

        assertThrows(ToolApprovalRequiredException.class, () -> run(task()));

        verify(dataFactory, times(1)).createData(eq(AUDIT_COST), any());
    }
}
