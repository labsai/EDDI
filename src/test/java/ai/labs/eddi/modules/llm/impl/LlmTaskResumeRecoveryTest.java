/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.audit.IAuditEntryCollector;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.OnError;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ResponseValidation;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * R12: a HITL-resumed turn shares the recovery policies of a live one — the
 * onError fallback, the same-model re-ask of the final answer (a single model
 * call, no tool replay) and responseValidation's actions — and says so in its
 * recovery lines.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@DisplayName("LlmTask resume — recovery parity (R12)")
class LlmTaskResumeRecoveryTest {

    private static final String CONVERSATION = "conv-resume-recovery-1";
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
    private AgentOrchestrator agentOrchestrator;
    @Mock
    private ChatModel chatModel;
    @Mock
    private IConversationMemory memory;
    @Mock
    private IWritableConversationStep currentStep;
    @Mock
    private ConversationEventSink sink;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final Map<String, Object> templateData = new HashMap<>();
    private final List<String> recoveryLines = new ArrayList<>();
    private LlmTask llmTask;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        LlmRecoveryLog.observer = line -> {
            if (line.contains("conversationId=" + CONVERSATION)) {
                recoveryLines.add(line);
            }
        };
        lenient().when(promptSnippetService.getForAgent(any())).thenReturn(Map.of());
        lenient().when(globalVariableResolver.getTemplateData()).thenReturn(Map.of());
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(globalVariableResolver.resolveValue("${vars:fast-model}")).thenReturn("gemini-resolved-1");
        lenient().when(templatingEngine.processTemplate(anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(0, String.class).replace("{|", "").replace("|}", ""));
        lenient().when(dataFactory.createData(anyString(), any())).thenAnswer(inv -> {
            IData d = mock(IData.class);
            lenient().when(d.getResult()).thenAnswer(x -> inv.getArgument(1));
            return d;
        });

        var counterweightService = mock(CounterweightService.class);
        lenient().when(counterweightService.apply(anyString(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        var identityMaskingService = mock(IdentityMaskingService.class);
        lenient().when(identityMaskingService.apply(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        llmTask = new LlmTask(resourceClientLibrary, dataFactory, memoryItemConverter, templatingEngine, new JsonSerialization(new ObjectMapper()),
                prePostUtils, chatModelRegistry, mock(IApiCallExecutor.class), mock(IAgentStore.class), mock(IWorkflowStore.class),
                ragContextProvider, new TokenCounterFactory(), mock(ConversationSummarizer.class), promptSnippetService, globalVariableResolver,
                counterweightService, identityMaskingService, agentOrchestrator, new ConversationHistoryBuilder(), meterRegistry,
                new CallerIdentityContext(null, null));

        var batch = new PendingToolCallBatch();
        batch.setLlmTaskId("taskA");
        batch.setLlmTaskIndex(0);
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        lenient().when(memory.getCurrentStep()).thenReturn(currentStep);
        lenient().when(memory.getConversationId()).thenReturn(CONVERSATION);
        lenient().when(memory.getAgentId()).thenReturn("aaaaaaaaaaaaaaaaaaaaaaaa");
        var actionData = mock(IData.class);
        lenient().when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        lenient().when(actionData.getResult()).thenReturn(List.of("action1"));
        lenient().when(memoryItemConverter.convert(memory)).thenReturn(templateData);
        var output = new ConversationOutput();
        output.put("input", "question");
        lenient().when(memory.getConversationOutputs()).thenReturn(List.of(output));
        lenient().when(memory.getConversationProperties()).thenReturn(new ConversationProperties(memory));
        lenient().when(memory.getHitlPendingToolCalls()).thenReturn(batch);
        lenient().when(memory.getHitlResumeDecision()).thenReturn(decision);
        lenient().when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(chatModel);
    }

    @AfterEach
    void tearDown() {
        LlmRecoveryLog.observer = null;
    }

    private static LlmConfiguration.Task task(Consumer<LlmConfiguration.Task> tweak) {
        var t = new LlmConfiguration.Task();
        t.setId("taskA");
        t.setType("openai");
        t.setActions(List.of("action1"));
        var params = new HashMap<String, String>();
        params.put("apiKey", "key");
        params.put("modelName", "base-model");
        params.put("addToOutput", "true");
        params.put("convertToObject", "true");
        t.setParameters(params);
        tweak.accept(t);
        return t;
    }

    private static Consumer<LlmConfiguration.Task> validation(Consumer<ResponseValidation> tweak) {
        return t -> {
            var v = new ResponseValidation();
            v.setEnabled(true);
            tweak.accept(v);
            t.setResponseValidation(v);
        };
    }

    private static Consumer<LlmConfiguration.Task> onErrorFallback() {
        return t -> {
            var onError = new OnError();
            onError.setAction(OnError.ACTION_FALLBACK);
            t.setOnError(onError);
        };
    }

    private void run(LlmConfiguration.Task task) throws Exception {
        llmTask.execute(memory, new LlmConfiguration(List.of(task)));
    }

    private static List<ChatMessage> transcript() {
        var call = ToolExecutionRequest.builder().id("call-1").name("lookup").arguments("{}").build();
        return List.of(SystemMessage.from("sys"), UserMessage.from("question"), AiMessage.from(call),
                ToolExecutionResultMessage.from(call, "{\"ok\":true}"));
    }

    private void resumeReturns(String answer, List<ChatMessage> resumeTranscript) throws Exception {
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult(answer, new ArrayList<>(), new HashMap<>(), List.of(), resumeTranscript));
    }

    private TextOutputItem outputItem() {
        var captor = ArgumentCaptor.forClass(List.class);
        verify(currentStep).addConversationOutputList(eq("output"), captor.capture());
        return assertInstanceOf(TextOutputItem.class, captor.getValue().get(0));
    }

    @Test
    @DisplayName("onError=fallback: a failed resumed model phase serves the fallback instead of failing the turn, and the pause is consumed")
    void failedResumeServesTheFallback() throws Exception {
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new LifecycleException("provider down"));

        run(task(onErrorFallback().andThen(validation(v -> v.setFallbackMessage("Please try again later.")))));

        var item = outputItem();
        assertEquals("Please try again later.", item.getText());
        assertEquals(Boolean.TRUE, item.getFallback());
        assertEquals("Please try again later.", templateData.get("taskA"));
        verify(dataFactory).createData("llm:fallback:taskA", Boolean.TRUE);
        verify(memory).setHitlPendingToolCalls(null);
        assertEquals(1, recoveryLines.stream().filter(l -> l.contains(" action=fallback ")).count(), recoveryLines.toString());
    }

    @Test
    @DisplayName("without onError the failure still propagates and the pause stays pending (default behaviour)")
    void failedResumeWithoutOnErrorPropagates() throws Exception {
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new LifecycleException("provider down"));

        assertThrows(LifecycleException.class, () -> run(task(t -> {
        })));

        verify(memory, never()).setHitlPendingToolCalls(null);
        assertTrue(recoveryLines.isEmpty(), recoveryLines.toString());
    }

    @Test
    @DisplayName("onInvalidJson=retry: the resumed answer is re-asked once, over the resumed transcript, and no tool loop runs again")
    void invalidResumedAnswerIsReasked() throws Exception {
        resumeReturns(PROSE, transcript());
        when(agentOrchestrator.reaskFinalAnswer(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult(VALID, List.of(), Map.of()));

        run(task(validation(v -> v.setOnInvalidJson("retry"))));

        verify(agentOrchestrator, times(1)).resumeToolLoop(any(), any(), any(), any(), any(), anyBoolean(), any());
        var messages = ArgumentCaptor.forClass(List.class);
        verify(agentOrchestrator, times(1)).reaskFinalAnswer(eq(chatModel), messages.capture(), any(), any(), eq(memory), any());
        List<ChatMessage> sent = messages.getValue();
        assertEquals(transcript().size() + 2, sent.size(), "transcript + the bad answer + the corrective message");
        assertInstanceOf(ToolExecutionResultMessage.class, sent.get(3));
        assertInstanceOf(UserMessage.class, sent.get(sent.size() - 1));
        assertEquals(Map.of("answer", "hi"), templateData.get("taskA"));
        assertEquals(1, recoveryLines.stream().filter(l -> l.contains(" action=retry outcome=recovered ")).count(), recoveryLines.toString());
    }

    @Test
    @DisplayName("a resume that recorded no transcript cannot re-ask: nothing is guessed, responseValidation's fallbackAction decides")
    void noTranscriptNoReask() throws Exception {
        resumeReturns(PROSE, List.of());

        run(task(validation(v -> {
            v.setOnInvalidJson("retry");
            v.setFallbackMessage("Please try again.");
        })));

        verify(agentOrchestrator, never()).reaskFinalAnswer(any(), any(), any(), any(), any(), any());
        assertEquals("Please try again.", templateData.get("taskA"));
        verify(dataFactory).createData("llm:fallback:taskA", Boolean.TRUE);
    }

    @Test
    @DisplayName("responseValidation applies to a resumed answer: an empty reply with onEmpty=fallback serves the fallback")
    void responseValidationAppliesOnResume() throws Exception {
        resumeReturns("", List.of());

        run(task(validation(v -> {
            v.setOnEmpty("fallback");
            v.setFallbackMessage("Nothing to say.");
        })));

        assertEquals("Nothing to say.", outputItem().getText());
        verify(dataFactory).createData("llm:fallback:taskA", Boolean.TRUE);
    }

    @Test
    @DisplayName("a task that may re-ask is buffered: the resumed answer reaches the stream once, no streaming bridge is built")
    void retryTaskIsBufferedOnResume() throws Exception {
        when(memory.getEventSink()).thenReturn(sink);
        resumeReturns(VALID, transcript());

        run(task(validation(v -> v.setOnInvalidJson("retry"))));

        verify(chatModelRegistry, never()).getOrCreateStreaming(anyString(), any());
        verify(sink, times(1)).onToken(VALID);
    }

    @Test
    @DisplayName("audit:model_name of a resumed turn is the resolved model, not the ${vars:...} template")
    void resumedAuditNameIsResolved() throws Exception {
        when(memory.getAuditCollector()).thenReturn(mock(IAuditEntryCollector.class));
        resumeReturns(VALID, List.of());
        var task = task(t -> {
        });
        task.getParameters().put("modelName", "${vars:fast-model}");

        run(task);

        verify(dataFactory).createData(MemoryKeys.AUDIT_MODEL_NAME, "gemini-resolved-1");
    }

    @Test
    @DisplayName("answeredFrom drops the final text answer and keeps everything the model saw")
    void answeredFromDropsTheFinalAnswer() {
        var call = ToolExecutionRequest.builder().id("c").name("t").arguments("{}").build();
        List<ChatMessage> seen = List.of(UserMessage.from("q"), AiMessage.from(call), ToolExecutionResultMessage.from(call, "r"));
        List<ChatMessage> withAnswer = new ArrayList<>(seen);
        withAnswer.add(AiMessage.from("the answer"));

        assertEquals(seen, ToolLoopResumer.answeredFrom(withAnswer));
        assertEquals(seen, ToolLoopResumer.answeredFrom(seen), "an exhausted loop ends on tool results: nothing to drop");
        assertTrue(ToolLoopResumer.answeredFrom(List.of()).isEmpty());
    }
}
