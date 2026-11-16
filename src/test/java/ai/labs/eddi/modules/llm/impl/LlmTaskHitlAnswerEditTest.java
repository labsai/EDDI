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
import ai.labs.eddi.engine.hitl.audit.HitlApprovalAudit;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionCandidate;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionPolicy;
import ai.labs.eddi.engine.lifecycle.model.ReviewerEditRequest;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.DataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.guardrails.GuardrailAction;
import ai.labs.eddi.modules.llm.guardrails.GuardrailOutcome;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import ai.labs.eddi.modules.llm.guardrails.GuardrailService;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LlmTask — an approver's edit replaces the draft")
class LlmTaskHitlAnswerEditTest {

    private ITemplatingEngine templatingEngine;
    private ChatModel chatModel;
    private PrePostUtils prePostUtils;
    private ConversationMemory memory;
    private LlmTask llmTask;

    @BeforeEach
    void setUp() throws Exception {
        templatingEngine = mock(ITemplatingEngine.class);
        var chatModelRegistry = mock(ChatModelRegistry.class);
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
                promptSnippetService, globalVariableResolver, counterweightService, identityMaskingService, mock(AgentOrchestrator.class),
                new ConversationHistoryBuilder(), new SimpleMeterRegistry(), new CallerIdentityContext(null, null));

        memory = new ConversationMemory("conv-edit", "agent-edit", 1, "user-1");
        memory.getCurrentStep().storeData(new Data<>(ACTIONS.key(), List.of("answer")));
    }

    private static LlmConfiguration config(boolean convertToObject, boolean guarded) {
        var task = new LlmConfiguration.Task();
        task.setId("writer");
        task.setType("openai");
        task.setActions(List.of("answer"));
        var params = new HashMap<String, String>();
        params.put("apiKey", "key");
        params.put("modelName", "base-model");
        params.put("systemMessage", "You write answers.");
        params.put("addToOutput", "true");
        if (convertToObject) {
            params.put("convertToObject", "true");
        }
        task.setParameters(params);
        if (guarded) {
            var guardrail = new LlmConfiguration.GuardrailConfig();
            guardrail.setType("pii");
            guardrail.setAction("block");
            task.setOutputGuardrails(List.of(guardrail));
        }
        return new LlmConfiguration(List.of(task));
    }

    private static HitlRevisionCandidate candidate(String taskId, int outputMark) {
        var candidate = new HitlRevisionCandidate();
        candidate.setWorkflowId("wf1");
        candidate.setLifecycleIndex(2);
        candidate.setLlmTaskId(taskId);
        candidate.setLlmTaskIndex(0);
        candidate.setDraft("The release is Friday.");
        candidate.setAddedToOutput(true);
        candidate.setOutputMark(outputMark);
        return candidate;
    }

    @SuppressWarnings("unchecked")
    private List<Object> outputList() {
        Object list = memory.getCurrentStep().getConversationOutput().get(MemoryKeys.OUTPUT_PREFIX);
        return list instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static String textOf(Object item) {
        return item instanceof TextOutputItem text ? text.getText() : String.valueOf(((Map<?, ?>) item).get("text"));
    }

    @Test
    @DisplayName("edits on, SKIP: only the draft is kept (no frozen request), with its format")
    void captureDraftOnly() throws Exception {
        memory.setHitlRevisionPolicy(new HitlRevisionPolicy(HitlRejectionAction.SKIP, 0, true, true));
        when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("the draft")).build());

        llmTask.execute(memory, config(false, false));

        HitlRevisionCandidate candidate = memory.getHitlRevisionCandidate();
        assertNotNull(candidate);
        assertEquals("the draft", candidate.getDraft());
        assertNull(candidate.getTranscriptJson(), "a frozen request is only for REVISE");
        assertEquals(HitlRevisionCandidate.FORMAT_TEXT, candidate.getAnswerFormat());
    }

    @Test
    @DisplayName("a convertToObject task's draft is JSON")
    void jsonFormat() throws Exception {
        memory.setHitlRevisionPolicy(new HitlRevisionPolicy(HitlRejectionAction.SKIP, 0, true, true));
        var response = ChatResponse.builder().aiMessage(AiMessage.from("{\"a\":1}")).build();
        lenient().when(chatModel.chat(anyList())).thenReturn(response);
        lenient().when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        llmTask.execute(memory, config(true, false));

        assertEquals(HitlRevisionCandidate.FORMAT_JSON, memory.getHitlRevisionCandidate().getAnswerFormat());
    }

    @Test
    @DisplayName("applied: replaces the draft's own output item in place, keeps later output, stays literal, runs nothing")
    void appliedInPlace() throws Exception {
        var step = memory.getCurrentStep();
        step.addConversationOutputList(MemoryKeys.OUTPUT_PREFIX, List.of(new TextOutputItem("earlier", 0)));
        step.addConversationOutputList(MemoryKeys.OUTPUT_PREFIX, List.of(new TextOutputItem("The release is Friday.", 0)));
        step.addConversationOutputList(MemoryKeys.OUTPUT_PREFIX, List.of(new TextOutputItem("a later task's output", 0)));
        String edit = "The release is Monday. {properties.secret}";

        var outcome = llmTask.replaceAnswer(memory, config(false, false), new ReviewerEditRequest(candidate("writer", 1), edit, "alice", true));

        assertTrue(outcome.applied());
        List<Object> output = outputList();
        assertEquals(3, output.size(), "nothing is truncated");
        assertEquals("earlier", textOf(output.get(0)));
        assertEquals(edit, textOf(output.get(1)), "the edit is data: the placeholder stays literal");
        assertEquals("a later task's output", textOf(output.get(2)));
        assertEquals(edit, step.getLatestData("langchain:openai:writer").getResult());
        assertEquals(edit, step.getLatestData("output:text:langchain:openai").getResult());
        verify(templatingEngine, never()).processTemplate(contains("properties.secret"), any());
        verify(prePostUtils, never()).runPostResponse(any(), any(), any(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("a changed configuration refuses the edit as CONFIG_DRIFT")
    void configDrift() throws Exception {
        var outcome = llmTask.replaceAnswer(memory, config(false, false), new ReviewerEditRequest(candidate("renamed", 0), "x", "alice", true));
        assertFalse(outcome.applied());
        assertEquals(HitlApprovalAudit.REFUSED_CONFIG_DRIFT, outcome.refusal());
        assertFalse(llmTask.replaceAnswer(memory, "not a config", new ReviewerEditRequest(candidate("writer", 0), "x", "a", true)).applied());
    }

    @Test
    @DisplayName("screening: block refuses, redact rewrites, screen=false skips the guardrails")
    void screening() throws Exception {
        GuardrailService guardrails = mock(GuardrailService.class);
        llmTask.guardrailService = guardrails;
        var blocked = new GuardrailOutcome("pii", GuardrailPhase.OUTPUT, GuardrailAction.BLOCK, "phone number", 0);
        when(guardrails.runOutput(any(), eq("call 555"), isNull(), any(), any()))
                .thenReturn(new GuardrailService.OutputResult("blocked", blocked, List.of(), 0.0));
        when(guardrails.runOutput(any(), eq("mail me"), isNull(), any(), any()))
                .thenReturn(new GuardrailService.OutputResult("mail [redacted]", null, List.of(), 0.0));
        memory.getCurrentStep().addConversationOutputList(MemoryKeys.OUTPUT_PREFIX, List.of(new TextOutputItem("draft", 0)));

        var refused = llmTask.replaceAnswer(memory, config(false, true), new ReviewerEditRequest(candidate("writer", 0), "call 555", "a", true));
        assertFalse(refused.applied());
        assertEquals(HitlApprovalAudit.REFUSED_SCREENED_OUT, refused.refusal());
        assertEquals("pii", refused.guardrail());
        assertEquals("draft", textOf(outputList().get(0)), "a refused edit writes nothing");

        var redacted = llmTask.replaceAnswer(memory, config(false, true), new ReviewerEditRequest(candidate("writer", 0), "mail me", "a", true));
        assertTrue(redacted.applied());
        assertEquals("mail [redacted]", textOf(outputList().get(0)));

        var unscreened = llmTask.replaceAnswer(memory, config(false, true), new ReviewerEditRequest(candidate("writer", 0), "call 555", "a", false));
        assertTrue(unscreened.applied());
        assertEquals("call 555", textOf(outputList().get(0)));
    }
}
