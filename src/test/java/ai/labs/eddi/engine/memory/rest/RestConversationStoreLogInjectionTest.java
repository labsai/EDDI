/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.utils.LogCaptureSupport;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Date;
import java.util.List;

import static ai.labs.eddi.utils.LogCaptureSupport.assertNoForgedRecordBoundary;
import static ai.labs.eddi.utils.LogCaptureSupport.captureLogsOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CWE-117 regression tests for {@link RestConversationStore} (code scanning
 * alerts #112, #113 and #114).
 *
 * <p>
 * Three sinks, two sources. {@code conversationId} is a path parameter on
 * {@code DELETE /conversationstore/conversations/{id}} and reaches the two
 * attachment-cleanup lines verbatim. The exception message on the
 * descriptor-skip line and the attachment-failure line is not the developer's
 * text either: a store routinely quotes back the value it was handed, which is
 * the caller's.
 * </p>
 *
 * <p>
 * The file already sanitized its neighbouring lines — the permanent-delete
 * INFO, the soft-delete INFO, the not-found message — so these three were
 * missed rather than deliberately left.
 * </p>
 */
@DisplayName("RestConversationStore log injection (CWE-117)")
class RestConversationStoreLogInjectionTest {

    /**
     * A real 24-character hex id, because {@code extractResourceId} resolves an id
     * only out of a hex path segment; a semantic name yields a null id and the
     * descriptor is skipped before it ever reaches the line under test.
     */
    private static final String CONVERSATION_ID = "0123456789abcdef01234567";

    private IConversationDescriptorStore conversationDescriptorStore;
    private IConversationMemoryStore conversationMemoryStore;
    private IAttachmentStore attachmentStore;
    private RestConversationStore store;

    /**
     * Builds the store with attachment storage resolvable and authorization
     * disabled, so neither the ownership gate nor the owner filter stands between
     * the call and the log line under test.
     */
    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        conversationDescriptorStore = mock(IConversationDescriptorStore.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        attachmentStore = mock(IAttachmentStore.class);
        Instance<IAttachmentStore> attachmentStorageInstance = mock(Instance.class);

        lenient().when(attachmentStorageInstance.isResolvable()).thenReturn(true);
        lenient().when(attachmentStorageInstance.get()).thenReturn(attachmentStore);

        // Authorization disabled: every caller is treated as an admin, so the
        // ownership gate and the per-descriptor owner filter are both out of the way
        // and the only thing under test is what reaches the log.
        var guard = new ConversationAccessGuard(null, new OwnershipValidator(false),
                mock(IConversationDescriptorStore.class));

        store = new RestConversationStore(mock(IDocumentDescriptorStore.class), conversationDescriptorStore,
                conversationMemoryStore, mock(IConversationService.class), mock(IUserMemoryStore.class),
                mock(IRuntime.class), guard, mock(ResourceAccessGuard.class), 30, 90, attachmentStorageInstance);
    }

    /** A descriptor the listing picks up and tries to populate. */
    private ConversationDescriptor descriptor() {
        var descriptor = new ConversationDescriptor();
        descriptor.setResource(URI.create(
                "eddi://ai.labs.conversation/conversationstore/conversations/" + CONVERSATION_ID + "?version=1"));
        descriptor.setAgentName("Some Agent");
        descriptor.setLastModifiedOn(new Date());
        return descriptor;
    }

    /**
     * The listing swallows a corrupt descriptor and logs the cause. That cause is
     * not the developer's text: a store routinely quotes back the value it was
     * handed, which is the caller's.
     */
    @Test
    @DisplayName("a forged exception message cannot forge a record on the descriptor-skip line")
    void sanitizesTheExceptionMessageOnTheDescriptorSkipLine() throws Exception {
        when(conversationDescriptorStore.readDescriptors(anyString(), any(), eq(0), anyInt(), anyBoolean()))
                .thenReturn(List.of(descriptor()));
        // Fails inside populateDataToDescriptor, i.e. inside the per-descriptor
        // catch that logs the message. Page 1 comes back empty, so the back-fill
        // loop ends and exactly one line lands in the window.
        when(conversationMemoryStore.loadConversationMemorySnapshot(anyString()))
                .thenThrow(new IllegalStateException("descriptor is corrupt" + LogCaptureSupport.FORGED_RECORD));

        List<String> logged = captureLogsOf(RestConversationStore.class,
                () -> store.readConversationDescriptors(0, 20, null, null, null, null, null, null));

        assertNoForgedRecordBoundary(logged, "the descriptor-skip line of RestConversationStore");
        assertTrue(logged.stream().anyMatch(value -> value.contains("descriptor is corrupt")),
                "the cause is sanitized, not dropped — an operator still needs to know why it was skipped: " + logged);
    }

    /**
     * The success half of the attachment cleanup, reached only when the store
     * reports a non-zero count. The id on it is the caller's path parameter.
     */
    @Test
    @DisplayName("a forged conversationId cannot forge a record on the attachments-deleted line")
    void sanitizesTheConversationIdOnTheAttachmentsDeletedLine() throws Exception {
        String forgedConversationId = CONVERSATION_ID + LogCaptureSupport.FORGED_RECORD;
        // Greater than zero, which is what gates the line under test.
        when(attachmentStore.deleteByConversation(anyString())).thenReturn(3L);

        List<String> logged = captureLogsOf(RestConversationStore.class,
                () -> deletePermanently(forgedConversationId));

        assertNoForgedRecordBoundary(logged, "the attachments-deleted line of RestConversationStore");
        assertTrue(logged.stream().anyMatch(value -> value.contains(CONVERSATION_ID)),
                "the conversation id is sanitized, not dropped: " + logged);
    }

    /**
     * The failure half, which carries two tainted values on one line — the caller's
     * id and the store's message. Both are forged here, so the test holds whichever
     * of the two {@code sanitize(…)} calls is removed.
     */
    @Test
    @DisplayName("a forged conversationId and cause cannot forge a record on the attachment-failure line")
    void sanitizesTheAttachmentFailureLine() throws Exception {
        String forgedConversationId = CONVERSATION_ID + LogCaptureSupport.FORGED_RECORD;
        when(attachmentStore.deleteByConversation(anyString()))
                .thenThrow(new IllegalStateException("blob store unavailable" + LogCaptureSupport.FORGED_RECORD));

        List<String> logged = captureLogsOf(RestConversationStore.class,
                () -> deletePermanently(forgedConversationId));

        assertNoForgedRecordBoundary(logged, "the attachment-failure line of RestConversationStore");
        assertTrue(logged.stream().anyMatch(value -> value.contains("blob store unavailable")),
                "the cause is sanitized, not dropped: " + logged);
        assertTrue(logged.stream().anyMatch(value -> value.contains(CONVERSATION_ID)),
                "the conversation id is sanitized, not dropped: " + logged);
    }

    /**
     * {@code deleteConversationLog(id, true)} is the only caller-reachable route to
     * {@code deleteAttachmentsForConversation} that needs no live conversation
     * behind it.
     */
    private void deletePermanently(String conversationId) {
        try {
            store.deleteConversationLog(conversationId, true);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
