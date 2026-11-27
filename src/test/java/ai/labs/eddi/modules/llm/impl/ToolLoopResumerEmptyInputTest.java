/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolApprovalGateSupport;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.impl.CalculatorTool;
import ai.labs.eddi.modules.llm.tools.impl.DataFormatterTool;
import ai.labs.eddi.modules.llm.tools.impl.DateTimeTool;
import ai.labs.eddi.modules.llm.tools.impl.FetchToolResponsePageTool;
import ai.labs.eddi.modules.llm.tools.impl.PdfReaderTool;
import ai.labs.eddi.modules.llm.tools.impl.TextSummarizerTool;
import ai.labs.eddi.modules.llm.tools.impl.WeatherTool;
import ai.labs.eddi.modules.llm.tools.impl.WebScraperTool;
import ai.labs.eddi.modules.llm.tools.impl.WebSearchTool;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HITL resume on its degraded path rebuilds the history from memory. For a task
 * that paused at conversation start that history has no user turn either, so
 * the rebuilt request needs the same guarantee as the first call.
 */
@DisplayName("ToolLoopResumer — the degraded rebuild keeps conversational content")
class ToolLoopResumerEmptyInputTest {

    private ToolLoopResumer resumer;

    @BeforeEach
    void setUp() {
        var orchestrator = new AgentOrchestrator(mock(CalculatorTool.class), mock(DateTimeTool.class), mock(WebSearchTool.class),
                mock(DataFormatterTool.class), mock(WebScraperTool.class), mock(TextSummarizerTool.class), mock(PdfReaderTool.class),
                mock(WeatherTool.class), mock(FetchToolResponsePageTool.class), mock(ToolExecutionService.class),
                mock(McpToolProviderManager.class), mock(A2AToolProviderManager.class), mock(IWorkflowStore.class),
                mock(IResourceClientLibrary.class), mock(IApiCallExecutor.class), mock(IJsonSerialization.class),
                mock(IMemoryItemConverter.class), mock(IUserMemoryStore.class), mock(ToolResponseTruncator.class), mock(TenantQuotaService.class),
                mock(MemorySnapshotService.class), null, null, null, null, null, mock(IHitlToolJournalStore.class),
                new ConversationHistoryBuilder(), new TokenCounterFactory());
        resumer = new ToolLoopResumer(orchestrator, mock(ToolLoopRunner.class), mock(ToolApprovalGateSupport.class), new ChatTranscriptCodec(),
                mock(IHitlToolJournalStore.class));
    }

    private static LlmConfiguration.Task task(String emptyInputPrompt) {
        var task = new LlmConfiguration.Task();
        task.setId("greeting");
        var params = new HashMap<String, String>();
        params.put("systemMessage", "You greet the user.");
        if (emptyInputPrompt != null) {
            params.put("emptyInputPrompt", emptyInputPrompt);
        }
        task.setParameters(params);
        return task;
    }

    private List<ChatMessage> rebuild(LlmConfiguration.Task task) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getConversationOutputs()).thenReturn(List.of(new ConversationOutput()));
        var batch = new PendingToolCallBatch();
        var call = new PendingToolCallBatch.PendingToolCall();
        call.setCallId("call-1");
        call.setToolName("calculate");
        call.setSource("builtin");
        call.setArgumentsRaw("{\"expression\":\"1+1\"}");
        call.setArgsTruncated(false);
        batch.setCalls(List.of(call));
        return resumer.fallbackRebuildMessages(task, memory, batch);
    }

    @Test
    @DisplayName("the default turn is present")
    void defaultTurn() {
        var messages = rebuild(task(null));

        assertTrue(messages.stream().anyMatch(m -> m instanceof UserMessage um
                && ConversationHistoryBuilder.DEFAULT_EMPTY_INPUT_PROMPT.equals(um.singleText())));
    }

    @Test
    @DisplayName("the task's emptyInputPrompt is honoured")
    void configuredTurn() {
        var messages = rebuild(task("Say hello."));

        assertEquals(1, messages.stream().filter(m -> m instanceof UserMessage um && "Say hello.".equals(um.singleText())).count());
    }
}
