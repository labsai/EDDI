/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.attachments.AttachmentShareService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.InMemoryAttachmentStore;
import ai.labs.eddi.engine.documents.DocumentBounds;
import ai.labs.eddi.engine.documents.DocumentSettings;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.modules.llm.model.DocumentsConfig;
import ai.labs.eddi.modules.llm.tools.ConverseWithAgentAndFilesTool;
import ai.labs.eddi.modules.llm.tools.ConverseWithAgentTool;
import ai.labs.eddi.modules.llm.tools.ToolNameResolver;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * WP-1.2: which {@code converseWithAgent} the provider assembles (I8), what its
 * specification looks like, and the share depth across a chain of delegations.
 */
@DisplayName("DynamicAgentToolsProvider — converseWithAgent with files")
class DynamicAgentToolsProviderAttachmentsTest {

    private static final String CALLER = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String STARTED = "bbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String REPORT = "0123456789abcdef01234567";
    private static final List<String> WHITELIST = List.of("converse_with_agent");

    private IConversationService conversationService;
    private InMemoryAttachmentStore store;
    private ConversationMemory memory;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        lenient().when(conversationService.startConversation(any(), anyString(), any(), any()))
                .thenReturn(new ConversationResult(STARTED, null));
        lenient().doAnswer(invocation -> {
            IConversationService.ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
        store = spy(new InMemoryAttachmentStore().put(REPORT, "report.pdf", "application/pdf", 1_000, CALLER));
        memory = new ConversationMemory(CALLER, "agent-a", 1, "user-1");
    }

    private DynamicAgentToolsProvider provider(IAttachmentStore attachmentStore, DocumentSettings settings, int maxPerTurn) {
        var sharing = new DynamicAgentToolsProvider.FileSharing(new AttachmentShareService(attachmentStore, null, null), attachmentStore,
                settings, maxPerTurn);
        return new DynamicAgentToolsProvider(mock(AgentSetupService.class), mock(CapabilityRegistryService.class), conversationService,
                mock(IAgentFactory.class), mock(IAgentStore.class), mock(IDeploymentStore.class), new LiveDiscussionRegistry(),
                mock(IAgentGroupStore.class), null, sharing);
    }

    private DynamicAgentToolsProvider provider() {
        return provider(store, DocumentSettings.none(), 5);
    }

    private static DocumentsConfig delegation(Boolean on) {
        var sharing = new DocumentsConfig.Sharing();
        sharing.setDelegation(on);
        var documents = new DocumentsConfig();
        documents.setSharing(sharing);
        return documents;
    }

    private void attachReport() {
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.ATTACHMENTS.key(),
                List.of(new Attachment("application/pdf", "report.pdf", 1_000, REPORT))));
    }

    private Object assemble(DynamicAgentToolsProvider provider, DocumentsConfig documents) {
        return assemble(provider, documents, "t");
    }

    private Object assemble(DynamicAgentToolsProvider provider, DocumentsConfig documents, String taskId) {
        List<Object> tools = new ArrayList<>();
        provider.addDynamicAgentTools(tools, WHITELIST, memory, null, taskId, documents);
        assertEquals(1, tools.size(), tools.toString());
        return tools.get(0);
    }

    private static List<String> parameterNames(ToolSpecification spec) {
        return new ArrayList<>(spec.parameters().properties().keySet());
    }

    @Test
    @DisplayName("delegation sharing on and a stored file in memory: the four-argument tool, built without touching the store")
    void filesToolWhenOnAndAFileIsInMemory() {
        attachReport();

        Object tool = assemble(provider(), delegation(true));

        assertInstanceOf(ConverseWithAgentAndFilesTool.class, tool);
        verifyNoInteractions(store);
        var reflected = ToolObjectReflector.reflect(List.of(tool));
        assertEquals(1, reflected.specs().size());
        ToolSpecification spec = reflected.specs().get(0);
        assertEquals("converseWithAgent", spec.name());
        assertEquals(List.of("agentId", "message", "conversationId", "attachments"), parameterNames(spec));
        assertTrue(spec.parameters().required().containsAll(List.of("agentId", "message")));
        assertTrue(!spec.parameters().required().contains("attachments"), spec.parameters().required().toString());
        // Approval patterns, slug and cache must treat it as the delegation tool it is.
        assertEquals(Map.of("converseWithAgent", "dynamic"), reflected.toolSources());
        assertEquals(Map.of("converseWithAgent", "converse_with_agent"), reflected.toolCanonicalNames());
        assertEquals("converse_with_agent", ToolNameResolver.canonicalForClass(ConverseWithAgentAndFilesTool.class.getSimpleName()));
    }

    @Test
    @DisplayName("the key absent, or false: the three-argument tool, its specification unchanged")
    void plainToolWhenTheKeyIsAbsentOrFalse() {
        attachReport();

        for (DocumentsConfig documents : new DocumentsConfig[]{null, new DocumentsConfig(), delegation(false), delegation(null)}) {
            Object tool = assemble(provider(), documents);
            assertInstanceOf(ConverseWithAgentTool.class, tool);
            ToolSpecification spec = ToolObjectReflector.reflect(List.of(tool)).specs().get(0);
            assertEquals(List.of("agentId", "message", "conversationId"), parameterNames(spec));
            assertEquals("Send a message to another deployed EDDI agent and receive its response. "
                    + "Use this for inter-agent delegation or consultation. "
                    + "Provide an existing conversationId for multi-turn conversations, "
                    + "or omit it to start a new conversation.", spec.description());
        }
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("a file-less turn: the three-argument tool, and no store call at all")
    void plainToolOnAFileLessTurn() {
        Object tool = assemble(provider(), delegation(true));

        assertInstanceOf(ConverseWithAgentTool.class, tool);
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("a file from an earlier turn counts, read from memory")
    void fileFromAnEarlierTurnCounts() {
        attachReport();
        memory.startNextStep();

        assertInstanceOf(ConverseWithAgentAndFilesTool.class, assemble(provider(), delegation(true)));
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("no share service or store wired: the three-argument tool")
    void plainToolWithoutSharingWired() {
        attachReport();
        var unwired = new DynamicAgentToolsProvider(mock(AgentSetupService.class), mock(CapabilityRegistryService.class), conversationService,
                mock(IAgentFactory.class), mock(IAgentStore.class), mock(IDeploymentStore.class), new LiveDiscussionRegistry(),
                mock(IAgentGroupStore.class), null, new DynamicAgentToolsProvider.FileSharing(null, null, null, 5));

        assertInstanceOf(ConverseWithAgentTool.class, assemble(unwired, delegation(true)));
    }

    @Test
    @DisplayName("an operator default turns delegation sharing on for an agent without a documents block")
    void operatorDefaultTurnsItOn() {
        attachReport();
        var settings = DocumentSettings.of(Map.of("sharing.delegation", "true"), DocumentBounds.none());

        assertInstanceOf(ConverseWithAgentAndFilesTool.class, assemble(provider(store, settings, 5), null));
    }

    @Test
    @DisplayName("with sharing on, a conversation started without files is still recorded as started here")
    void threeArgumentToolRecordsWhatItStarts() {
        var tool = (ConverseWithAgentTool) assemble(provider(), delegation(true));

        tool.converseWithAgent("agent-b", "hi", null);

        assertEquals(List.of(STARTED), memory.getCurrentStep().getData(MemoryKeys.DELEGATIONS_STARTED).getResult());
    }

    @Test
    @DisplayName("with sharing off, nothing is recorded: an agent that configures nothing stores what it stored before")
    void nothingRecordedWhenOff() {
        var tool = (ConverseWithAgentTool) assemble(provider(), null);

        tool.converseWithAgent("agent-b", "hi", null);

        assertNull(memory.getCurrentStep().getData(MemoryKeys.DELEGATIONS_STARTED));
    }

    @Test
    @DisplayName("a share never promises more files than a delegate's turn takes (eddi.attachments.max-per-turn)")
    void filesPerShareCappedAtMaxPerTurn() {
        store.put("1123456789abcdef01234567", "notes.txt", "text/plain", 10, CALLER);
        attachReport();
        var tool = (ConverseWithAgentAndFilesTool) assemble(provider(store, DocumentSettings.none(), 1), delegation(true));

        String result = tool.converseWithAgent("agent-b", "hi", null, List.of("report.pdf", "notes.txt"));

        assertTrue(result.contains("At most 1 files can be shared per call."), result);
        assertTrue(store.isGranted(REPORT, STARTED));
    }

    @Test
    @DisplayName("A→B→C→D at maxDepth 3: D, three hops from the user, cannot pass the file on")
    void chainStopsAtMaxDepth() throws Exception {
        // D's memory: the file reached it with attachmentShareDepth=3 on the message
        // turn.
        memory.getCurrentStep().storeData(new Data<>("context:" + ConverseWithAgentTool.CONTEXT_ATTACHMENT_SHARE_DEPTH,
                new Context(Context.ContextType.string, "3")));
        attachReport();
        assertEquals(3, DynamicAgentToolsProvider.resolveAttachmentShareDepth(memory));

        var tool = (ConverseWithAgentAndFilesTool) assemble(provider(), delegation(true));
        String result = tool.converseWithAgent("agent-e", "hi", null, List.of("report.pdf"));

        assertTrue(result.startsWith("⚠️ Not shared: report.pdf (" + AttachmentShareService.Refusal.DEPTH.text() + ")."), result);
        verify(store, never()).grantAccess(any(), any());
    }

    @Test
    @DisplayName("C, two hops from the user, still may: and the file reaches D at depth 3")
    void chainBelowMaxDepthShares() throws Exception {
        memory.getCurrentStep().storeData(new Data<>("context:" + ConverseWithAgentTool.CONTEXT_ATTACHMENT_SHARE_DEPTH,
                new Context(Context.ContextType.string, "2")));
        attachReport();

        var tool = (ConverseWithAgentAndFilesTool) assemble(provider(), delegation(true));
        String result = tool.converseWithAgent("agent-d", "hi", null, List.of("report.pdf"));

        assertTrue(result.startsWith("📎 Shared with the agent: report.pdf."), result);
        assertTrue(store.isGranted(REPORT, STARTED));
        ArgumentCaptor<InputData> sent = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), sent.capture(), anyBoolean(),
                any());
        assertEquals("3", String.valueOf(sent.getValue().getContext().get(ConverseWithAgentTool.CONTEXT_ATTACHMENT_SHARE_DEPTH).getValue()));
    }

    @Test
    @DisplayName("maxSharesPerTurn counts the whole turn: a second LLM task cannot share past what the first used up")
    void sharesPerTurnCountAcrossTasks() throws Exception {
        String notes = "1123456789abcdef01234567";
        store.put(notes, "notes.txt", "text/plain", 10, CALLER);
        attachReport();
        var documents = delegation(true);
        documents.getSharing().setMaxSharesPerTurn(1);

        var first = (ConverseWithAgentAndFilesTool) assemble(provider(), documents, "t1");
        String firstResult = first.converseWithAgent("agent-b", "hi", null, List.of("report.pdf"));
        var second = (ConverseWithAgentAndFilesTool) assemble(provider(), documents, "t2");
        String secondResult = second.converseWithAgent("agent-c", "hi", null, List.of("notes.txt"));

        assertTrue(firstResult.startsWith("📎 Shared with the agent: report.pdf."), firstResult);
        assertTrue(secondResult.startsWith("⚠️ Not shared: notes.txt (" + AttachmentShareService.Refusal.SHARES_PER_TURN.text() + ")."),
                secondResult);
        verify(store, never()).grantAccess(eq(notes), any());
    }

    @Test
    @DisplayName("the share depth survives a later turn that does not carry it")
    void shareDepthFallsBackToEarlierSteps() {
        memory.getCurrentStep().storeData(new Data<>("context:" + ConverseWithAgentTool.CONTEXT_ATTACHMENT_SHARE_DEPTH,
                new Context(Context.ContextType.string, "2")));
        memory.startNextStep();

        assertEquals(2, DynamicAgentToolsProvider.resolveAttachmentShareDepth(memory));
    }
}
