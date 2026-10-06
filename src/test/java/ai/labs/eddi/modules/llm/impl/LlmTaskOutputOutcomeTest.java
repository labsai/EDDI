/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
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
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel.Step;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * R3: {@code convertToObject} never throws on malformed model output, on either
 * the live path or the HITL resume path, and records what it found under
 * {@code llm:output:outcome:<taskId>} and {@code eddi.llm.output{outcome}}. The
 * live path is driven by the scripted {@link FaultInjectingChatModel}.
 */
@DisplayName("LlmTask — output outcome (R3)")
class LlmTaskOutputOutcomeTest {

    private static final String OUTCOME_KEY = "llm:output:outcome:taskA";

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

        llmTask = new LlmTask(resourceClientLibrary, dataFactory, memoryItemConverter, templatingEngine,
                new JsonSerialization(new ObjectMapper()), prePostUtils, chatModelRegistry,
                mock(IApiCallExecutor.class), mock(IAgentStore.class), mock(IWorkflowStore.class),
                ragContextProvider, new TokenCounterFactory(), conversationSummarizer,
                promptSnippetService, globalVariableResolver, counterweightService,
                identityMaskingService, agentOrchestrator, new ConversationHistoryBuilder(),
                meterRegistry, new CallerIdentityContext(null, null));
    }

    private static LlmConfiguration.Task task(boolean convertToObject, boolean tools) {
        var t = new LlmConfiguration.Task();
        t.setId("taskA");
        t.setType("openai");
        t.setActions(List.of("action1"));
        var params = new HashMap<String, String>();
        params.put("apiKey", "key");
        params.put("modelName", "base-model");
        params.put("systemMessage", "You are helpful.");
        if (convertToObject) {
            params.put("convertToObject", "true");
        }
        t.setParameters(params);
        t.setEnableBuiltInTools(tools);
        return t;
    }

    private void liveModelReplies(Step... script) throws Exception {
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(FaultInjectingChatModel.script(script));
    }

    private void resumeReplies(String reply) throws Exception {
        var batch = new PendingToolCallBatch();
        batch.setLlmTaskId("taskA");
        batch.setLlmTaskIndex(0);
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        when(memory.getHitlPendingToolCalls()).thenReturn(batch);
        when(memory.getHitlResumeDecision()).thenReturn(decision);
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(mock(ChatModel.class));
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult(reply, new ArrayList<>()));
    }

    private void runLive(boolean convertToObject) throws Exception {
        llmTask.execute(memory, new LlmConfiguration(List.of(task(convertToObject, false))));
    }

    private void runResume() throws Exception {
        llmTask.execute(memory, new LlmConfiguration(List.of(task(true, true))));
    }

    private double counted(String outcome) {
        var counter = meterRegistry.find("eddi.llm.output").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Nested
    @DisplayName("live path")
    class Live {

        @Test
        @DisplayName("truncated JSON does not fail the turn; the raw string is stored and the outcome is invalid")
        void truncatedJsonDoesNotThrow() throws Exception {
            String truncated = "{\"htmlResponseText\": \"half an ans";
            liveModelReplies(Step.text(truncated));

            assertDoesNotThrow(() -> runLive(true));

            assertEquals(truncated, templateData.get("taskA"));
            verify(dataFactory).createData(OUTCOME_KEY, "invalid");
            assertEquals(1, counted("invalid"));
        }

        @Test
        @DisplayName("prose does not fail the turn either")
        void proseDoesNotThrow() throws Exception {
            liveModelReplies(Step.text("Sorry, I can only answer in prose."));

            assertDoesNotThrow(() -> runLive(true));

            assertEquals("Sorry, I can only answer in prose.", templateData.get("taskA"));
            verify(dataFactory).createData(OUTCOME_KEY, "invalid");
        }

        @Test
        @DisplayName("a fenced reply is repaired into an object")
        void fencedReplyIsRepaired() throws Exception {
            liveModelReplies(Step.text("```json\n{\"htmlResponseText\":\"hi\"}\n```"));

            runLive(true);

            assertEquals(Map.of("htmlResponseText", "hi"), templateData.get("taskA"));
            verify(dataFactory).createData(OUTCOME_KEY, "repaired");
            assertEquals(1, counted("repaired"));
        }

        @Test
        @DisplayName("a clean reply is valid")
        void cleanReplyIsValid() throws Exception {
            liveModelReplies(Step.text("{\"htmlResponseText\":\"hi\"}"));

            runLive(true);

            assertEquals(Map.of("htmlResponseText", "hi"), templateData.get("taskA"));
            verify(dataFactory).createData(OUTCOME_KEY, "valid");
            assertEquals(1, counted("valid"));
        }

        @Test
        @DisplayName("an empty reply is recorded as empty")
        void emptyReply() throws Exception {
            liveModelReplies(Step.empty());

            assertDoesNotThrow(() -> runLive(true));

            verify(dataFactory).createData(OUTCOME_KEY, "empty");
            assertEquals(1, counted("empty"));
        }

        @Test
        @DisplayName("without convertToObject nothing is parsed and no outcome is recorded")
        void noConvertNoOutcome() throws Exception {
            liveModelReplies(Step.text("```json\n{}\n```"));

            runLive(false);

            assertEquals("```json\n{}\n```", templateData.get("taskA"));
            verify(dataFactory, never()).createData(eq(OUTCOME_KEY), any());
            assertEquals(0, meterRegistry.find("eddi.llm.output").counters().size());
        }

        @Test
        @DisplayName("the outcome key is not a client-visible snapshot key")
        void outcomeKeyStaysOutOfSnapshots() {
            // ConversationMemoryUtilities passes only input:initial, actions*, output*
            // and quickReplies* keys into a client snapshot.
            String key = LlmTask.KEY_OUTPUT_OUTCOME;
            assertTrue(key.startsWith("llm:"));
            assertFalse(key.startsWith("output") || key.startsWith("actions") || key.startsWith("quickReplies") || key.startsWith("input"));
        }
    }

    @Nested
    @DisplayName("HITL resume path")
    class Resume {

        @Test
        @DisplayName("truncated JSON after a resume does not fail the turn and records the outcome")
        void truncatedJsonDoesNotThrow() throws Exception {
            resumeReplies("{\"htmlResponseText\": \"cut o");

            assertDoesNotThrow(() -> runResume());

            assertEquals("{\"htmlResponseText\": \"cut o", templateData.get("taskA"));
            verify(dataFactory).createData(OUTCOME_KEY, "invalid");
            assertEquals(1, counted("invalid"));
        }

        @Test
        @DisplayName("a fenced reply after a resume is repaired the same way as on the live path")
        void fencedReplyIsRepaired() throws Exception {
            resumeReplies("```json\n{\"htmlResponseText\":\"ok\"}\n```");

            runResume();

            assertEquals(Map.of("htmlResponseText", "ok"), templateData.get("taskA"));
            verify(dataFactory).createData(OUTCOME_KEY, "repaired");
        }
    }
}
