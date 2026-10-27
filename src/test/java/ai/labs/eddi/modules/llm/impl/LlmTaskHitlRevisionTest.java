/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.hitl.HitlRejectionAction;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionCandidate;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionPolicy;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionRequest;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.DataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code hitlConfig.onRejection: REVISE} inside the LLM task: an answer is kept
 * for revision only when the policy is on, and a revision is one tool-less
 * re-ask of the same model over the frozen request, the note as the user's
 * corrective turn — never template source.
 */
@DisplayName("LlmTask — HITL revise with the reviewer's note (E2)")
class LlmTaskHitlRevisionTest {

    private static final String NOTE = "Too long. Drop the second paragraph {properties.secret}";

    private ITemplatingEngine templatingEngine;
    private ChatModelRegistry chatModelRegistry;
    private AgentOrchestrator agentOrchestrator;
    private PrePostUtils prePostUtils;
    private ChatModel chatModel;
    private ConversationMemory memory;
    private LlmTask llmTask;
    private final ChatTranscriptCodec codec = new ChatTranscriptCodec();

    @BeforeEach
    void setUp() throws Exception {
        templatingEngine = mock(ITemplatingEngine.class);
        chatModelRegistry = mock(ChatModelRegistry.class);
        agentOrchestrator = mock(AgentOrchestrator.class);
        prePostUtils = mock(PrePostUtils.class);
        chatModel = mock(ChatModel.class);
        var memoryItemConverter = mock(IMemoryItemConverter.class);
        var promptSnippetService = mock(PromptSnippetService.class);
        var globalVariableResolver = mock(GlobalVariableResolver.class);
        lenient().when(promptSnippetService.getForAgent(any())).thenReturn(Map.of());
        lenient().when(globalVariableResolver.getTemplateData()).thenReturn(Map.of());
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(memoryItemConverter.convert(any())).thenAnswer(inv -> new HashMap<String, Object>());
        lenient().when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(chatModel);
        var counterweightService = mock(CounterweightService.class);
        lenient().when(counterweightService.apply(anyString(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        var identityMaskingService = mock(IdentityMaskingService.class);
        lenient().when(identityMaskingService.apply(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        llmTask = new LlmTask(mock(IResourceClientLibrary.class), new DataFactory(), memoryItemConverter, templatingEngine,
                new JsonSerialization(new ObjectMapper()), prePostUtils, chatModelRegistry, mock(IApiCallExecutor.class), mock(IAgentStore.class),
                mock(IWorkflowStore.class), mock(RagContextProvider.class), new TokenCounterFactory(), mock(ConversationSummarizer.class),
                promptSnippetService, globalVariableResolver, counterweightService, identityMaskingService, agentOrchestrator,
                new ConversationHistoryBuilder(), new SimpleMeterRegistry(), new CallerIdentityContext(null, null));

        memory = new ConversationMemory("conv-revise", "agent-revise", 1, "user-1");
        memory.getCurrentStep().storeData(new Data<>(ACTIONS.key(), List.of("answer")));
        memory.getCurrentStep().addConversationOutputString("input", "question");
    }

    private static LlmConfiguration config(boolean addToOutput) {
        var task = new LlmConfiguration.Task();
        task.setId("writer");
        task.setType("openai");
        task.setActions(List.of("answer"));
        var params = new HashMap<String, String>();
        params.put("apiKey", "key");
        params.put("modelName", "base-model");
        params.put("systemMessage", "You write answers.");
        params.put("addToOutput", String.valueOf(addToOutput));
        task.setParameters(params);
        return new LlmConfiguration(List.of(task));
    }

    /**
     * The plain path asks through {@code chat(List)}
     * (AgentExecutionHelper#executeChatWithRetry).
     */
    private void modelAnswers(String... answers) {
        var stubbing = when(chatModel.chat(anyList()));
        for (String answer : answers) {
            stubbing = stubbing.thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(answer)).build());
        }
    }

    private HitlRevisionCandidate candidate(String draft, boolean addedToOutput, int outputMark) {
        var candidate = new HitlRevisionCandidate();
        candidate.setWorkflowId("wf1");
        candidate.setLifecycleIndex(2);
        candidate.setLlmTaskId("writer");
        candidate.setLlmTaskIndex(0);
        candidate.setDraft(draft);
        candidate.setAddedToOutput(addedToOutput);
        candidate.setOutputMark(outputMark);
        candidate.setQuickRepliesMark(0);
        candidate.setTranscriptJson(codec.serialize(List.of(SystemMessage.from("You write answers."), UserMessage.from("question")), 100_000).json());
        return candidate;
    }

    @SuppressWarnings("unchecked")
    private List<Object> outputList() {
        Object list = memory.getCurrentStep().getConversationOutput().get(MemoryKeys.OUTPUT_PREFIX);
        return list instanceof List<?> l ? (List<Object>) l : List.of();
    }

    @Nested
    @DisplayName("capturing the answer under review")
    class Capture {

        @Test
        @DisplayName("default policy: nothing is kept — an agent that did not opt in is unchanged")
        void defaultPolicyKeepsNothing() throws Exception {
            modelAnswers("the draft");

            llmTask.execute(memory, config(true));

            assertNull(memory.getHitlRevisionCandidate());
        }

        @Test
        @DisplayName("REVISE: the answer, its frozen request and the output mark are kept")
        void revisePolicyKeepsTheAnswer() throws Exception {
            memory.setHitlRevisionPolicy(new HitlRevisionPolicy(HitlRejectionAction.REVISE, 3));
            modelAnswers("the draft");

            llmTask.execute(memory, config(true));

            var candidate = memory.getHitlRevisionCandidate();
            assertNotNull(candidate);
            assertEquals("the draft", candidate.getDraft());
            assertEquals("writer", candidate.getLlmTaskId());
            assertEquals(0, candidate.getLlmTaskIndex());
            assertTrue(candidate.isAddedToOutput());
            assertEquals(0, candidate.getOutputMark());
            assertFalse(candidate.isToolTranscript());
            assertEquals(-1, candidate.getLifecycleIndex(), "the lifecycle manager stamps the position, not the task");
            List<ChatMessage> transcript = codec.deserialize(candidate.getTranscriptJson());
            assertInstanceOf(SystemMessage.class, transcript.getFirst());
            assertFalse(transcript.stream().anyMatch(m -> m instanceof AiMessage ai && "the draft".equals(ai.text())),
                    "the transcript is the request, without the answer it produced");
        }

        @Test
        @DisplayName("REVISE: a request over the size cap is kept without a transcript (nothing to revise)")
        void overCapKeepsNoTranscript() throws Exception {
            memory.setHitlRevisionPolicy(new HitlRevisionPolicy(HitlRejectionAction.REVISE, 3));
            llmTask.revisionTranscriptMaxBytes = 10;
            modelAnswers("the draft");

            llmTask.execute(memory, config(true));

            assertNotNull(memory.getHitlRevisionCandidate());
            assertNull(memory.getHitlRevisionCandidate().getTranscriptJson());
        }
    }

    @Nested
    @DisplayName("revising")
    class Revise {

        @Test
        @DisplayName("one tool-less re-ask: the request, the draft as the assistant turn, the note as the user turn")
        void reviseReasksWithTheNote() throws Exception {
            modelAnswers("the revision");

            boolean revised = llmTask.reviseAnswer(memory, config(false), new HitlRevisionRequest(candidate("the draft", false, 0), NOTE, 1));

            assertTrue(revised);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
            verify(chatModel).chat(captor.capture());
            List<ChatMessage> sent = captor.getValue();
            assertEquals(4, sent.size());
            assertInstanceOf(SystemMessage.class, sent.get(0));
            assertEquals("the draft", ((AiMessage) sent.get(2)).text());
            String corrective = ((UserMessage) sent.get(3)).singleText();
            assertTrue(corrective.contains(NOTE), "the note reaches the model verbatim: " + corrective);
            verify(chatModel, never()).chat(any(ChatRequest.class));
            assertEquals("the revision", memory.getCurrentStep().getLatestData("langchain:openai:writer").getResult());
        }

        @Test
        @DisplayName("the note is never template source")
        void noteIsNeverTemplated() throws Exception {
            modelAnswers("the revision");

            llmTask.reviseAnswer(memory, config(false), new HitlRevisionRequest(candidate("the draft", false, 0), NOTE, 1));

            verify(templatingEngine, never()).processTemplate(contains("Drop the second paragraph"), any());
        }

        @Test
        @DisplayName("the draft's output is replaced, earlier output is kept")
        void draftOutputIsReplaced() throws Exception {
            memory.getCurrentStep().addConversationOutputList(MemoryKeys.OUTPUT_PREFIX, List.of(new TextOutputItem("greeting", 0)));
            memory.getCurrentStep().addConversationOutputList(MemoryKeys.OUTPUT_PREFIX, List.of(new TextOutputItem("the draft", 0)));
            memory.getCurrentStep().addConversationOutputList(MemoryKeys.OUTPUT_PREFIX, List.of(new TextOutputItem("rule output", 0)));
            modelAnswers("the revision");

            llmTask.reviseAnswer(memory, config(true), new HitlRevisionRequest(candidate("the draft", true, 1), NOTE, 1));

            var texts = outputList().stream().map(item -> ((TextOutputItem) item).getText()).toList();
            assertEquals(List.of("greeting", "the revision"), texts);
        }

        @Test
        @DisplayName("the revision is kept as the next candidate, against the whole exchange so far")
        void revisionBecomesTheNextCandidate() throws Exception {
            memory.setHitlRevisionPolicy(new HitlRevisionPolicy(HitlRejectionAction.REVISE, 3));
            modelAnswers("the revision");

            llmTask.reviseAnswer(memory, config(false), new HitlRevisionRequest(candidate("the draft", false, 0), NOTE, 1));

            var next = memory.getHitlRevisionCandidate();
            assertNotNull(next);
            assertEquals("the revision", next.getDraft());
            List<ChatMessage> transcript = codec.deserialize(next.getTranscriptJson());
            assertEquals(4, transcript.size(), "request + draft + note");
        }

        @Test
        @DisplayName("a tool exchange is re-asked through reaskFinalAnswer, which never runs a tool")
        void toolTranscriptUsesFinalAnswerReask() throws Exception {
            when(agentOrchestrator.reaskFinalAnswer(any(), any(), any(), any(), any(), any()))
                    .thenReturn(new AgentOrchestrator.ExecutionResult("the revision", List.of(), Map.of()));
            var candidate = candidate("the draft", false, 0);
            candidate.setToolTranscript(true);

            assertTrue(llmTask.reviseAnswer(memory, config(false), new HitlRevisionRequest(candidate, NOTE, 1)));

            verify(agentOrchestrator).reaskFinalAnswer(any(), any(), any(), any(), any(), any());
            verify(chatModel, never()).chat(anyList());
        }
    }

    @Nested
    @DisplayName("when no revision can be produced, nothing is written and the caller rejects")
    class Unavailable {

        @Test
        @DisplayName("the configuration changed: the task at the index has another id")
        void configDrift() throws Exception {
            var candidate = candidate("the draft", false, 0);
            candidate.setLlmTaskId("someone-else");

            assertFalse(llmTask.reviseAnswer(memory, config(false), new HitlRevisionRequest(candidate, NOTE, 1)));
            verifyNoInteractions(chatModel);
            assertNull(memory.getCurrentStep().getLatestData("langchain:openai:writer"));
        }

        @Test
        @DisplayName("the model returns nothing")
        void blankRevision() throws Exception {
            modelAnswers("  ");

            assertFalse(llmTask.reviseAnswer(memory, config(false), new HitlRevisionRequest(candidate("the draft", false, 0), NOTE, 1)));
            assertNull(memory.getCurrentStep().getLatestData("langchain:openai:writer"));
        }

        @Test
        @DisplayName("the model call fails")
        void modelFails() throws Exception {
            when(chatModel.chat(anyList())).thenThrow(new IllegalStateException("boom"));

            assertFalse(llmTask.reviseAnswer(memory, config(false), new HitlRevisionRequest(candidate("the draft", false, 0), NOTE, 1)));
        }

        @Test
        @DisplayName("a spend limit is reached: no model call at all")
        void spendRefused() throws Exception {
            llmTask.spendGate = mock(LlmSpendGate.class);
            when(llmTask.spendGate.check(any())).thenReturn(new LlmSpendGate.Refusal("conversation_ceiling", "reached"));

            assertFalse(llmTask.reviseAnswer(memory, config(false), new HitlRevisionRequest(candidate("the draft", false, 0), NOTE, 1)));
            verifyNoInteractions(chatModel);
        }

        @Test
        @DisplayName("the kept request has no transcript (it was over the cap)")
        void noTranscript() throws Exception {
            var candidate = candidate("the draft", false, 0);
            candidate.setTranscriptJson(null);

            assertFalse(llmTask.reviseAnswer(memory, config(false), new HitlRevisionRequest(candidate, NOTE, 1)));
            verifyNoInteractions(chatModel);
        }
    }

    @Test
    @DisplayName("the persisted form survives a map round trip with any number type")
    void candidateMapRoundTrip() {
        var original = candidate("the draft", true, 3);
        original.setCascadeStepIndex(1);
        original.setRoundsUsed(2);
        Map<String, Object> stored = new HashMap<>(original.toMap());
        // BSON and JSON readers may hand numbers back as Long or Double
        stored.put("lifecycleIndex", 2L);
        stored.put("roundsUsed", 2.0d);

        var restored = HitlRevisionCandidate.fromMap(stored);

        assertEquals("wf1", restored.getWorkflowId());
        assertEquals(2, restored.getLifecycleIndex());
        assertEquals(2, restored.getRoundsUsed());
        assertEquals(1, restored.getCascadeStepIndex());
        assertEquals(3, restored.getOutputMark());
        assertTrue(restored.isAddedToOutput());
        assertEquals(original.getTranscriptJson(), restored.getTranscriptJson());
        assertNull(HitlRevisionCandidate.fromMap(new ArrayList<>()));
    }
}
