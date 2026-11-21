/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.attachments.AttachmentShareService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentAccessDeniedException;
import ai.labs.eddi.engine.attachments.InMemoryAttachmentStore;
import ai.labs.eddi.engine.attachments.SharePolicy;
import ai.labs.eddi.engine.memory.AttachmentContextExtractor;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * WP-1.2: {@code converseWithAgent(…, attachments)} hands whole files of the
 * calling conversation to the delegate — only to a conversation the caller
 * started, never a withheld file, never past the share depth — and the delegate
 * receives them as {@code attachment_<n>} stored references it holds a grant
 * for. Realistic 24-hex ids throughout, so a refusal's {@code verify(never())}
 * cannot pass for a null id.
 */
@DisplayName("ConverseWithAgentTool — files through delegation")
class ConverseWithAgentToolAttachmentsTest {

    private static final String CALLER = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String STARTED = "bbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String OTHER_OF_SAME_USER = "cccccccccccccccccccccccc";
    private static final String STRANGER = "dddddddddddddddddddddddd";
    private static final String REPORT = "0123456789abcdef01234567";
    private static final String NOTES = "1123456789abcdef01234567";
    private static final String FOREIGN = "f123456789abcdef01234567";

    private IConversationService conversationService;
    private InMemoryAttachmentStore store;
    private ConversationMemory memory;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        var own = new ConversationMemorySnapshot();
        own.setUserId("user-1");
        own.setAgentId("agent-b");
        lenient().when(conversationService.getConversationMemorySnapshot(anyString())).thenReturn(own);
        lenient().when(conversationService.startConversation(any(), anyString(), any(), any()))
                .thenReturn(new ConversationResult(STARTED, null));
        lenient().doAnswer(invocation -> {
            IConversationService.ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        store = spy(new InMemoryAttachmentStore()
                .put(REPORT, "report.pdf", "application/pdf", 1_000, CALLER)
                .put(NOTES, "notes.txt", "text/plain", 10, CALLER)
                .put(FOREIGN, "salary.pdf", "application/pdf", 1_000, STRANGER));
        memory = new ConversationMemory(CALLER, "agent-a", 1, "user-1");
    }

    private static DynamicAgentConfig permissive() {
        var config = new DynamicAgentConfig();
        config.setEnabled(true);
        config.setAllowDelegation(true);
        return config;
    }

    private SharePolicy policy(int maxFiles, int currentDepth) {
        return new SharePolicy(maxFiles, 10, 3, currentDepth, Set.of(), TurnCounter.onStep(memory, "documents:shares:t"));
    }

    private ConverseWithAgentAndFilesTool tool(SharePolicy policy) {
        var binding = new AttachmentShareBinding(memory, new AttachmentShareService(store, null, null), store, policy);
        return new ConverseWithAgentAndFilesTool(
                new ConverseWithAgentTool(conversationService, "user-1", permissive(), 0, null, null, TurnCounter.local(), binding));
    }

    private ConverseWithAgentAndFilesTool tool() {
        return tool(policy(5, 0));
    }

    private InputData sentInput() throws Exception {
        ArgumentCaptor<InputData> captor = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), captor.capture(), anyBoolean(),
                any());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attachmentValue(InputData input, int index) {
        Context context = input.getContext().get(AttachmentContextExtractor.ATTACHMENT_PREFIX + index);
        return context == null ? null : (Map<String, Object>) context.getValue();
    }

    @Test
    @DisplayName("a file shared into a new conversation reaches the delegate's turn, and only the delegate holds a grant")
    void sharesIntoTheConversationItStarts() throws Exception {
        String result = tool().converseWithAgent("agent-b", "please summarise", null, List.of("report.pdf"));

        assertTrue(result.startsWith("📎 Shared with the agent: report.pdf."), result);
        assertTrue(result.contains("✅ Agent response (conversationId: " + STARTED + ")"), result);
        InputData input = sentInput();
        assertEquals(Map.of("storageRef", REPORT, "fileName", "report.pdf"), attachmentValue(input, 0));
        assertNull(attachmentValue(input, 1));
        assertEquals("1", String.valueOf(input.getContext().get(ConverseWithAgentTool.CONTEXT_ATTACHMENT_SHARE_DEPTH).getValue()));
        assertTrue(store.isGranted(REPORT, STARTED));
        assertEquals(1, store.grantCount(REPORT));
        assertEquals(0, store.grantCount(NOTES));
    }

    @Test
    @DisplayName("the started conversation and the share are recorded on the caller's step — never a storage reference")
    void recordsTheStartAndTheShare() {
        tool().converseWithAgent("agent-b", "please summarise", null, List.of("report.pdf"));

        IData<List<String>> started = memory.getCurrentStep().getData(MemoryKeys.DELEGATIONS_STARTED);
        assertEquals(List.of(STARTED), started.getResult());
        IData<List<Map<String, String>>> shared = memory.getCurrentStep().getData(MemoryKeys.ATTACHMENTS_SHARED);
        assertEquals(List.of(Map.of("fileName", "report.pdf", "targetAgentId", "agent-b", "reason", "DELEGATION")), shared.getResult());
        assertFalse(shared.getResult().toString().contains(REPORT));
    }

    @Test
    @DisplayName("end to end: the delegate's pipeline parses the context into a stored reference it may read, and a stranger may not")
    void theDelegateCanReadWhatItReceives() throws Exception {
        tool().converseWithAgent("agent-b", "please summarise", null, List.of("report.pdf"));

        List<Attachment> parsed = AttachmentContextExtractor.extractAttachments(sentInput().getContext());
        assertEquals(1, parsed.size());
        assertEquals(REPORT, parsed.get(0).getStorageRef());
        assertNotNull(store.getMetadata(REPORT, STARTED));
        assertThrows(AttachmentAccessDeniedException.class, () -> store.getMetadata(REPORT, OTHER_OF_SAME_USER));
    }

    @Test
    @DisplayName("a same-user conversation this one did not start receives no file, and the message still goes")
    void conversationNotStartedHereGetsNothing() throws Exception {
        String result = tool().converseWithAgent("agent-b", "follow-up", OTHER_OF_SAME_USER, List.of("report.pdf"));

        verify(store, never()).grantAccess(any(), any());
        assertTrue(result.startsWith("⚠️ Not shared: report.pdf (" + AttachmentShareService.Refusal.TARGET.text() + ")."), result);
        assertTrue(result.contains("✅ Agent response (conversationId: " + OTHER_OF_SAME_USER + ")"), result);
        InputData input = sentInput();
        assertNull(attachmentValue(input, 0));
        assertNull(input.getContext().get(ConverseWithAgentTool.CONTEXT_ATTACHMENT_SHARE_DEPTH));
        verify(conversationService, never()).startConversation(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("a conversation this one started on an earlier turn may receive files later")
    void conversationStartedEarlierMayReceiveFiles() throws Exception {
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.DELEGATIONS_STARTED.key(), List.of(OTHER_OF_SAME_USER)));
        memory.startNextStep();

        String result = tool().converseWithAgent("agent-b", "and this one", OTHER_OF_SAME_USER, List.of("notes.txt"));

        assertTrue(result.startsWith("📎 Shared with the agent: notes.txt."), result);
        assertTrue(store.isGranted(NOTES, OTHER_OF_SAME_USER));
        assertEquals(NOTES, attachmentValue(sentInput(), 0).get("storageRef"));
    }

    @Test
    @DisplayName("a conversation started here that no longer runs the named agent receives no file")
    void startedConversationOfAnotherAgentGetsNothing() throws Exception {
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.DELEGATIONS_STARTED.key(), List.of(OTHER_OF_SAME_USER)));
        memory.startNextStep();

        String result = tool().converseWithAgent("agent-x", "and this one", OTHER_OF_SAME_USER, List.of("notes.txt"));

        verify(store, never()).grantAccess(any(), any());
        assertTrue(result.startsWith("⚠️ Not shared: notes.txt (" + AttachmentShareService.Refusal.TARGET.text() + ")."), result);
        assertNull(memory.getCurrentStep().getData(MemoryKeys.ATTACHMENTS_SHARED));
    }

    @Test
    @DisplayName("a file of another conversation is not found, and nothing is granted")
    void foreignFileIsNotFound() throws Exception {
        String result = tool().converseWithAgent("agent-b", "hi", null, List.of("salary.pdf", FOREIGN));

        verify(store, never()).grantAccess(any(), any());
        assertTrue(result.startsWith("⚠️ Not shared: salary.pdf (not found); " + FOREIGN + " (not found)."), result);
    }

    @Test
    @DisplayName("a withheld file is neither offered nor shareable")
    void withheldFileIsNotShareable() throws Exception {
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.ATTACHMENTS.key(),
                List.of(new Attachment("application/pdf", "report.pdf", 1_000, REPORT))));
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.LLM_INPUT_WITHHELD_PREFIX + "t", true));
        memory.startNextStep();

        String result = tool().converseWithAgent("agent-b", "hi", null, List.of("report.pdf"));

        verify(store, never()).grantAccess(eq(REPORT), any());
        assertTrue(result.startsWith("⚠️ Not shared: report.pdf (not found)."), result);
    }

    @Test
    @DisplayName("at the share depth limit nothing is passed on")
    void depthAtTheLimitSharesNothing() throws Exception {
        String result = tool(policy(5, 3)).converseWithAgent("agent-b", "hi", null, List.of("report.pdf"));

        verify(store, never()).grantAccess(any(), any());
        assertTrue(result.startsWith("⚠️ Not shared: report.pdf (" + AttachmentShareService.Refusal.DEPTH.text() + ")."), result);
    }

    @Test
    @DisplayName("the files-per-call cap applies, and the result says so")
    void capIsAppliedAndNamed() throws Exception {
        String result = tool(policy(1, 0)).converseWithAgent("agent-b", "hi", null, List.of("report.pdf", "notes.txt"));

        assertTrue(result.startsWith("📎 Shared with the agent: report.pdf.\n⚠️ Not shared: notes.txt ("
                + AttachmentShareService.Refusal.FILES_PER_SHARE.text() + "). At most 1 files can be shared per call."), result);
        assertTrue(store.isGranted(REPORT, STARTED));
        assertFalse(store.isGranted(NOTES, STARTED));
        assertNull(attachmentValue(sentInput(), 1));
    }

    @Test
    @DisplayName("no files asked for: exactly the three-argument call, and the store is never touched")
    void noAttachmentsTouchNoStore() {
        IAttachmentStore untouched = mock(IAttachmentStore.class);
        var binding = new AttachmentShareBinding(memory, new AttachmentShareService(untouched, null, null), untouched, policy(5, 0));
        var config = permissive();
        config.setMaxDelegationsPerTask(10);
        var core = new ConverseWithAgentTool(conversationService, "user-1", config, 0, null, null, TurnCounter.local(), binding);
        var withFiles = new ConverseWithAgentAndFilesTool(core);

        assertTrue(withFiles.converseWithAgent("agent-b", "hi", null, null).startsWith("✅"));
        assertTrue(withFiles.converseWithAgent("agent-b", "hi", null, List.of()).startsWith("✅"));
        assertTrue(withFiles.converseWithAgent("agent-b", "hi", null, List.of(" ")).startsWith("✅"));
        assertTrue(core.converseWithAgent("agent-b", "hi", null).startsWith("✅"));

        verifyNoInteractions(untouched);
    }

    @Test
    @DisplayName("a guardrail refusal shares nothing: the files go only after every check has passed")
    void guardrailRefusalSharesNothing() throws Exception {
        var disabled = permissive();
        disabled.setAllowDelegation(false);
        var binding = new AttachmentShareBinding(memory, new AttachmentShareService(store, null, null), store, policy(5, 0));
        var withFiles = new ConverseWithAgentAndFilesTool(
                new ConverseWithAgentTool(conversationService, "user-1", disabled, 0, null, null, TurnCounter.local(), binding));

        String result = withFiles.converseWithAgent("agent-b", "hi", null, List.of("report.pdf"));

        assertTrue(result.startsWith("⚠️ Delegating to another agent is not enabled"), result);
        verify(store, never()).grantAccess(any(), any());
        verify(store, never()).listAccessible(any());
    }
}
