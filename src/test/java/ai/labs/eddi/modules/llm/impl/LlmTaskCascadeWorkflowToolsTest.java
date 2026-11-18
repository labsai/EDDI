/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import ai.labs.eddi.modules.llm.tools.impl.*;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static ai.labs.eddi.engine.memory.MemoryKeys.LANGCHAIN_TRACE_PREFIX;
import static dev.langchain4j.data.message.AiMessage.aiMessage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * A task whose tools come only from its workflow — {@code httpcalls} or
 * {@code mcpcalls}, with the task's own {@code tools}, built-ins and
 * {@code a2aAgents} unset — has tools without a cascade, so it has them with
 * one. {@link LlmConfiguration.Task#isAgentMode()} cannot see such tools; the
 * turn asks {@link IAgentOrchestrator#offersTools} instead, and the cascade's
 * tool decision, {@code enableInAgentMode: false} and the cost reservation's
 * round count all follow that one answer.
 * <p>
 * The cascade executor is the real one inside {@code LlmTask}; the orchestrator
 * is a mock, so "the tool loop ran" is the orchestrator having been entered,
 * and "it did not" is the step's model being chatted directly.
 */
@DisplayName("LlmTask — a cascade keeps the workflow's tools")
class LlmTaskCascadeWorkflowToolsTest {

    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IDataFactory dataFactory;
    @Mock
    private IMemoryItemConverter memoryItemConverter;
    @Mock
    private ITemplatingEngine templatingEngine;
    @Mock
    private IJsonSerialization jsonSerialization;
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
    private ChatModel chatModel;
    @Mock
    private IConversationMemory memory;
    @Mock
    private IWritableConversationStep currentStep;

    private LlmTask llmTask;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        lenient().when(promptSnippetService.getForAgent(any())).thenReturn(Map.of());
        lenient().when(globalVariableResolver.getTemplateData()).thenReturn(Map.of());
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(counterweightService.apply(anyString(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(identityMaskingService.apply(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        llmTask = new LlmTask(resourceClientLibrary, dataFactory, memoryItemConverter, templatingEngine, jsonSerialization, prePostUtils,
                chatModelRegistry, mock(IApiCallExecutor.class), mock(IAgentStore.class), mock(IWorkflowStore.class), ragContextProvider,
                new TokenCounterFactory(), conversationSummarizer, promptSnippetService, globalVariableResolver, counterweightService,
                identityMaskingService, agentOrchestrator, new ConversationHistoryBuilder(), new SimpleMeterRegistry(),
                new CallerIdentityContext(null, null));

        lenient().when(dataFactory.createData(anyString(), any())).thenAnswer(inv -> {
            IData<?> d = mock(IData.class);
            lenient().when(d.getResult()).thenAnswer(x -> inv.getArgument(1));
            return d;
        });

        when(memory.getCurrentStep()).thenReturn(currentStep);
        var actionData = mock(IData.class);
        lenient().when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        lenient().when(actionData.getResult()).thenReturn(List.of("action1"));
        lenient().when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
        var conversationOutput = new ConversationOutput();
        conversationOutput.put("input", "what is the weather?");
        lenient().when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));
        lenient().when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(chatModel);
        lenient().when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        var inputData = mock(IData.class);
        lenient().when(currentStep.getLatestData("input")).thenReturn(inputData);
        lenient().when(inputData.getResult()).thenReturn("what is the weather?");

        // The workflow carries an httpcall: the assembly the tool loop runs on has a
        // tool, although the task's own configuration names none.
        lenient().when(agentOrchestrator.offersTools(any(), any())).thenReturn(true);
    }

    /**
     * No {@code tools}, no built-ins, no {@code a2aAgents}: the tools are the
     * workflow's.
     */
    private static LlmConfiguration.Task workflowToolTask() {
        var task = new LlmConfiguration.Task();
        task.setId("taskA");
        task.setType("openai");
        task.setActions(List.of("action1"));
        var params = new HashMap<String, String>();
        params.put("apiKey", "key");
        task.setParameters(params);
        return task;
    }

    private static ModelCascadeConfig oneStepCascade(boolean enableInAgentMode) {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEnableInAgentMode(enableInAgentMode);
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        step.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step));
        return cascade;
    }

    private static AgentOrchestrator.ExecutionResult toolAnswer() {
        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(new HashMap<>(Map.of("tool", "getWeather", "result", "sunny")));
        return new AgentOrchestrator.ExecutionResult("tool answer", trace);
    }

    private static ChatResponse plainAnswer() {
        return ChatResponse.builder().aiMessage(aiMessage("plain answer")).build();
    }

    @Test
    @DisplayName("a cascade step runs the tool loop for a task whose tools come from the workflow")
    void cascadeRunsToolLoopForWorkflowTools() throws Exception {
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(toolAnswer());
        var task = workflowToolTask();
        task.setModelCascade(oneStepCascade(true));
        assertFalse(task.isAgentMode(), "precondition: the configuration-only check sees no tools");

        llmTask.execute(memory, new LlmConfiguration(List.of(task)));

        verify(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
        verify(chatModel, never()).chat(any(ChatRequest.class));
        verify(chatModel, never()).chat(anyList());
        verify(dataFactory).createData(eq(LANGCHAIN_TRACE_PREFIX + "openai:taskA"), any());
        // usedToolMode: the tool loop's answer is the task's output.
        verify(dataFactory).createData(eq(LlmTask.LANGCHAIN_OUTPUT_IDENTIFIER + ":openai"), eq("tool answer"));
        verify(dataFactory).createData(startsWith("langchain:cascade:trace:"), any());
    }

    @Test
    @DisplayName("enableInAgentMode false skips the cascade for a task whose tools come from the workflow")
    void enableInAgentModeFalseSkipsCascade() throws Exception {
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(toolAnswer());
        var task = workflowToolTask();
        task.setModelCascade(oneStepCascade(false));

        llmTask.execute(memory, new LlmConfiguration(List.of(task)));

        // The single-model tool loop ran (the 9-argument entry LlmTask uses), and the
        // cascade did not: no cascade trace, no step entering the loop.
        verify(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
        verify(agentOrchestrator, never()).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
        verify(dataFactory, never()).createData(startsWith("langchain:cascade:trace:"), any());
        verify(dataFactory).createData(eq(LlmTask.LANGCHAIN_OUTPUT_IDENTIFIER + ":openai"), eq("tool answer"));
    }

    @Test
    @DisplayName("a task without tools still runs its cascade in legacy mode, and the orchestrator is asked")
    void noToolsKeepsLegacyCascade() throws Exception {
        when(agentOrchestrator.offersTools(any(), any())).thenReturn(false);
        when(chatModel.chat(anyList())).thenReturn(plainAnswer());
        var task = workflowToolTask();
        task.setModelCascade(oneStepCascade(true));

        llmTask.execute(memory, new LlmConfiguration(List.of(task)));

        verify(agentOrchestrator).offersTools(any(), any());
        verify(agentOrchestrator, never()).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
        verify(chatModel).chat(anyList());
    }

    @Test
    @DisplayName("a plain task without a cascade or a reservation never pays for a second tool assembly")
    void plainTaskDoesNotAskOrchestrator() throws Exception {
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(toolAnswer());

        llmTask.execute(memory, new LlmConfiguration(List.of(workflowToolTask())));

        verify(agentOrchestrator, never()).offersTools(any(), any());
    }

    @Test
    @DisplayName("the cost reservation holds 1 + maxToolIterations rounds for a task whose tools come from the workflow")
    void reservationCountsToolRounds() throws Exception {
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(toolAnswer());

        double withTools = heldAmount(true);
        double withoutTools = heldAmount(false);

        assertTrue(withoutTools > 0.0, "precondition: the task is priced, so the hold is not zero");
        // Every later round carries the opening prompt plus the tool context cap at the
        // same output cap, so four rounds hold at least four times what one does.
        assertTrue(withTools >= 4 * withoutTools - 1e-12, "expected 4 rounds: " + withTools + " vs one round " + withoutTools);
    }

    @Test
    @DisplayName("a spend refusal shows its fallback for a task whose tools come from the workflow, as the tool loop's answer would be")
    void spendRefusalShowsFallbackForWorkflowTools() throws Exception {
        var gate = mock(LlmSpendGate.class);
        when(gate.check(memory)).thenReturn(new LlmSpendGate.Refusal("conversation_ceiling", "spent"));
        llmTask.spendGate = gate;

        llmTask.execute(memory, new LlmConfiguration(List.of(workflowToolTask())));

        verify(agentOrchestrator).offersTools(any(), any());
        verify(agentOrchestrator, never()).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
        // No addToOutput on the task: only the tool mode puts the answer in the output.
        verify(dataFactory).createData(eq(LlmTask.LANGCHAIN_OUTPUT_IDENTIFIER + ":openai"), any());
    }

    private double heldAmount(boolean offersTools) throws Exception {
        when(agentOrchestrator.offersTools(any(), any())).thenReturn(offersTools);
        var gate = mock(LlmSpendGate.class);
        when(gate.reservationEnabled()).thenReturn(true);
        when(gate.reservationDefaultMaxOutputTokens()).thenReturn(1000);
        llmTask.spendGate = gate;
        var task = workflowToolTask();
        task.getParameters().put("maxTokens", "500");
        task.setInputPricePer1M(2.0);
        task.setOutputPricePer1M(10.0);
        task.setMaxToolIterations(3);
        task.setMaxToolContextTokens(1000);
        if (!offersTools) {
            when(chatModel.chat(anyList())).thenReturn(plainAnswer());
        }

        llmTask.execute(memory, new LlmConfiguration(List.of(task)));

        var held = ArgumentCaptor.forClass(Double.class);
        verify(gate).holdModelPhase(eq(memory), held.capture());
        return held.getValue();
    }
}
