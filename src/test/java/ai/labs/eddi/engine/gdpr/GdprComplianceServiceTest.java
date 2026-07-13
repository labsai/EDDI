/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IDatabaseLogs;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GdprComplianceService}. Verifies cascade deletion order
 * and export data aggregation.
 *
 * @author ginccc
 * @since 6.0.0
 */
class GdprComplianceServiceTest {

    private static final String USER_ID = "test-user-123";

    private IUserMemoryStore userMemoryStore;
    private IConversationMemoryStore conversationMemoryStore;
    private IUserConversationStore userConversationStore;
    private IDatabaseLogs databaseLogs;
    private IAuditStore auditStore;
    private AuditLedgerService auditLedgerService;
    private IHitlToolJournalStore hitlToolJournalStore;
    private GdprComplianceService service;
    private Instance<IAttachmentStore> attachmentStorageInstance;
    private IAttachmentStore attachmentStore;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userMemoryStore = mock(IUserMemoryStore.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        userConversationStore = mock(IUserConversationStore.class);
        databaseLogs = mock(IDatabaseLogs.class);
        auditStore = mock(IAuditStore.class);
        auditLedgerService = mock(AuditLedgerService.class);
        hitlToolJournalStore = mock(IHitlToolJournalStore.class);

        attachmentStorageInstance = mock(Instance.class);
        attachmentStore = mock(IAttachmentStore.class);
        when(attachmentStorageInstance.isResolvable()).thenReturn(false);

        service = new GdprComplianceService(
                userMemoryStore, conversationMemoryStore,
                userConversationStore, databaseLogs, auditStore,
                auditLedgerService, attachmentStorageInstance, hitlToolJournalStore);
    }

    @Test
    void deleteUserData_cascadesAcrossAllStores() throws Exception {
        // Given
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(5L);
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID))
                .thenReturn(3L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(2L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString()))
                .thenReturn(10L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString()))
                .thenReturn(15L);

        // When
        GdprDeletionResult result = service.deleteUserData(USER_ID);

        // Then
        assertEquals(USER_ID, result.userId());
        assertEquals(5, result.memoriesDeleted());
        assertEquals(3, result.conversationsDeleted());
        assertEquals(2, result.conversationMappingsDeleted());
        assertEquals(10, result.logsPseudonymized());
        assertEquals(15, result.auditEntriesPseudonymized());
        assertNotNull(result.completedAt());

        // Verify cascade order: all stores called
        verify(userMemoryStore).deleteAllForUser(USER_ID);
        verify(conversationMemoryStore).deleteConversationsByUserId(USER_ID);
        verify(userConversationStore).deleteAllForUser(USER_ID);
        verify(databaseLogs).pseudonymizeByUserId(eq(USER_ID), anyString());
        verify(auditStore).pseudonymizeByUserId(eq(USER_ID), anyString());
    }

    @Test
    void deleteUserData_deletesHitlToolJournalEntries() throws Exception {
        // Given — user has two conversations, each with journal entries
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenReturn(List.of("conv-1", "conv-2"));
        when(hitlToolJournalStore.deleteByConversationId("conv-1")).thenReturn(2L);
        when(hitlToolJournalStore.deleteByConversationId("conv-2")).thenReturn(3L);
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(2L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        // When
        service.deleteUserData(USER_ID);

        // Then — journal deletion runs per conversation, BEFORE conversations are
        // deleted
        var inOrder = inOrder(hitlToolJournalStore, conversationMemoryStore);
        inOrder.verify(hitlToolJournalStore).deleteByConversationId("conv-1");
        inOrder.verify(hitlToolJournalStore).deleteByConversationId("conv-2");
        inOrder.verify(conversationMemoryStore).deleteConversationsByUserId(USER_ID);
    }

    @Test
    void deleteUserData_continuesWhenJournalDeleteFails() throws Exception {
        // Given — journal store throws, but the cascade must continue
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenReturn(List.of("conv-1"));
        when(hitlToolJournalStore.deleteByConversationId("conv-1"))
                .thenThrow(new RuntimeException("Journal store unavailable"));
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(1L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        // When — should not throw
        GdprDeletionResult result = service.deleteUserData(USER_ID);

        // Then — cascade continued past the journal failure
        assertNotNull(result);
        assertEquals(1, result.conversationsDeleted());
        verify(conversationMemoryStore).deleteConversationsByUserId(USER_ID);
    }

    @Test
    void deleteUserData_pseudonymUsesConsistentHash() throws Exception {
        // Given
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID))
                .thenReturn(0L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString()))
                .thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString()))
                .thenReturn(0L);

        // When
        service.deleteUserData(USER_ID);

        // Then — same pseudonym used for both logs and audit
        var logsCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        var auditCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(databaseLogs).pseudonymizeByUserId(eq(USER_ID),
                logsCaptor.capture());
        verify(auditStore).pseudonymizeByUserId(eq(USER_ID),
                auditCaptor.capture());

        String logsPseudonym = logsCaptor.getValue();
        String auditPseudonym = auditCaptor.getValue();
        assertEquals(logsPseudonym, auditPseudonym,
                "Same pseudonym must be used across all stores");
        assertTrue(logsPseudonym.startsWith("gdpr-erased:"),
                "Pseudonym must have gdpr-erased: prefix");
    }

    @Test
    void deleteUserData_continuesOnPartialFailure() throws Exception {
        // Given — memory store throws, but others should still execute
        when(userMemoryStore.countEntries(USER_ID))
                .thenThrow(new RuntimeException("DB connection failed"));
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID))
                .thenReturn(2L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(1L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString()))
                .thenReturn(5L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString()))
                .thenReturn(8L);

        // When
        GdprDeletionResult result = service.deleteUserData(USER_ID);

        // Then — partial results, but no exception thrown
        assertEquals(0, result.memoriesDeleted()); // failed
        assertEquals(2, result.conversationsDeleted()); // succeeded
        assertEquals(1, result.conversationMappingsDeleted());
        assertEquals(5, result.logsPseudonymized());
        assertEquals(8, result.auditEntriesPseudonymized());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteUserData_deletesAttachmentsWhenStorageAvailable() throws Exception {
        // Given — attachment storage is resolvable
        Instance<IAttachmentStore> attachInstance = mock(Instance.class);
        when(attachInstance.isResolvable()).thenReturn(true);
        var attachmentStorage = mock(IAttachmentStore.class);
        when(attachInstance.get()).thenReturn(attachmentStorage);
        when(attachmentStorage.deleteByConversation("conv-1")).thenReturn(2L);
        when(attachmentStorage.deleteByConversation("conv-2")).thenReturn(3L);

        var serviceWithAttachments = new GdprComplianceService(
                userMemoryStore, conversationMemoryStore,
                userConversationStore, databaseLogs, auditStore,
                auditLedgerService, attachInstance, hitlToolJournalStore);

        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenReturn(List.of("conv-1", "conv-2"));
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID))
                .thenReturn(2L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        // When
        serviceWithAttachments.deleteUserData(USER_ID);

        // Then
        verify(attachmentStorage).deleteByConversation("conv-1");
        verify(attachmentStorage).deleteByConversation("conv-2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteUserData_handlesAttachmentFailureGracefully() throws Exception {
        // Given — attachment storage throws
        Instance<IAttachmentStore> attachInstance = mock(Instance.class);
        when(attachInstance.isResolvable()).thenReturn(true);
        var attachmentStorage = mock(IAttachmentStore.class);
        when(attachInstance.get()).thenReturn(attachmentStorage);

        var serviceWithAttachments = new GdprComplianceService(
                userMemoryStore, conversationMemoryStore,
                userConversationStore, databaseLogs, auditStore,
                auditLedgerService, attachInstance, hitlToolJournalStore);

        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenThrow(new RuntimeException("Attachment storage error"));
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID))
                .thenReturn(0L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        // When — should not throw
        GdprDeletionResult result = serviceWithAttachments.deleteUserData(USER_ID);

        // Then — cascade continues
        assertNotNull(result);
        verify(conversationMemoryStore).deleteConversationsByUserId(USER_ID);
    }

    @Test
    void exportUserData_aggregatesAllStores() throws Exception {
        // Given
        var memory1 = mock(UserMemoryEntry.class);
        when(userMemoryStore.getAllEntries(USER_ID))
                .thenReturn(List.of(memory1));

        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenReturn(List.of("conv-1"));

        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId("agent-1");
        snapshot.setAgentVersion(1);
        snapshot.setConversationState(ConversationState.ENDED);
        when(conversationMemoryStore.loadConversationMemorySnapshot("conv-1"))
                .thenReturn(snapshot);

        var userConv = mock(UserConversation.class);
        when(userConversationStore.getAllForUser(USER_ID))
                .thenReturn(List.of(userConv));

        // When
        UserDataExport export = service.exportUserData(USER_ID);

        // Then
        assertEquals(USER_ID, export.userId());
        assertNotNull(export.exportedAt());
        assertEquals(1, export.memories().size());
        assertEquals(1, export.conversations().size());
        assertEquals(1, export.managedConversations().size());

        var convExport = export.conversations().getFirst();
        assertEquals("conv-1", convExport.conversationId());
        assertEquals("agent-1", convExport.agentId());
        assertEquals(ConversationState.ENDED, convExport.state());
    }

    @Test
    void exportUserData_includesAttachmentMetadata() throws Exception {
        when(userMemoryStore.getAllEntries(USER_ID)).thenReturn(List.of());
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of("conv-1"));
        when(conversationMemoryStore.loadConversationMemorySnapshot("conv-1")).thenReturn(null);
        when(userConversationStore.getAllForUser(USER_ID)).thenReturn(List.of());
        when(auditStore.getEntriesByUserId(eq(USER_ID), anyInt(), anyInt())).thenReturn(List.of());

        when(attachmentStorageInstance.isResolvable()).thenReturn(true);
        when(attachmentStorageInstance.get()).thenReturn(attachmentStore);
        when(attachmentStore.listByConversation("conv-1")).thenReturn(List.of(
                new IAttachmentStore.Attachment("ref-1", "report.pdf", "application/pdf", 2048, "conv-1")));

        UserDataExport export = service.exportUserData(USER_ID);

        assertEquals(1, export.attachments().size());
        var a = export.attachments().getFirst();
        assertEquals("conv-1", a.conversationId());
        assertEquals("ref-1", a.storageRef());
        assertEquals("report.pdf", a.fileName());
        assertEquals("application/pdf", a.mimeType());
        assertEquals(2048, a.sizeBytes());
    }

    @Test
    void exportUserData_handlesEmptyData() throws Exception {
        // Given — user has no data
        when(userMemoryStore.getAllEntries(USER_ID)).thenReturn(List.of());
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenReturn(List.of());
        when(userConversationStore.getAllForUser(USER_ID))
                .thenReturn(List.of());
        when(auditStore.getEntriesByUserId(eq(USER_ID), anyInt(), anyInt()))
                .thenReturn(List.of());

        // When
        UserDataExport export = service.exportUserData(USER_ID);

        // Then
        assertEquals(USER_ID, export.userId());
        assertTrue(export.memories().isEmpty());
        assertTrue(export.conversations().isEmpty());
        assertTrue(export.managedConversations().isEmpty());
        assertTrue(export.auditEntries().isEmpty());
    }

    @Test
    void exportUserData_skipsFailedSnapshotLoads() throws Exception {
        // Given — one snapshot loads fine, the other fails
        when(userMemoryStore.getAllEntries(USER_ID)).thenReturn(List.of());
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenReturn(List.of("conv-ok", "conv-fail"));

        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId("agent-1");
        snapshot.setAgentVersion(1);
        snapshot.setConversationState(ConversationState.READY);
        when(conversationMemoryStore.loadConversationMemorySnapshot("conv-ok"))
                .thenReturn(snapshot);
        when(conversationMemoryStore.loadConversationMemorySnapshot("conv-fail"))
                .thenThrow(new RuntimeException("Corrupt snapshot"));

        when(userConversationStore.getAllForUser(USER_ID)).thenReturn(List.of());
        when(auditStore.getEntriesByUserId(eq(USER_ID), anyInt(), anyInt()))
                .thenReturn(List.of());

        // When
        UserDataExport export = service.exportUserData(USER_ID);

        // Then — only the successful one is included
        assertEquals(1, export.conversations().size());
        assertEquals("conv-ok", export.conversations().getFirst().conversationId());
    }

    @Test
    void exportUserData_handlesMemoryStoreFailure() throws Exception {
        // Given — memory store throws
        when(userMemoryStore.getAllEntries(USER_ID))
                .thenThrow(new RuntimeException("Memory store unavailable"));
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenReturn(List.of());
        when(userConversationStore.getAllForUser(USER_ID)).thenReturn(List.of());
        when(auditStore.getEntriesByUserId(eq(USER_ID), anyInt(), anyInt()))
                .thenReturn(List.of());

        // When — should not throw
        UserDataExport export = service.exportUserData(USER_ID);

        // Then — memories empty but export still succeeds
        assertTrue(export.memories().isEmpty());
    }

    @Test
    void exportUserData_handlesManagedConversationFailure() throws Exception {
        // Given
        when(userMemoryStore.getAllEntries(USER_ID)).thenReturn(List.of());
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenReturn(List.of());
        when(userConversationStore.getAllForUser(USER_ID))
                .thenThrow(new RuntimeException("Store unavailable"));
        when(auditStore.getEntriesByUserId(eq(USER_ID), anyInt(), anyInt()))
                .thenReturn(List.of());

        // When
        UserDataExport export = service.exportUserData(USER_ID);

        // Then
        assertTrue(export.managedConversations().isEmpty());
    }

    // ==================== Audit entries in export ====================

    @Test
    void exportUserData_includesAuditEntries() throws Exception {
        // Given
        when(userMemoryStore.getAllEntries(USER_ID)).thenReturn(List.of());
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenReturn(List.of());
        when(userConversationStore.getAllForUser(USER_ID))
                .thenReturn(List.of());

        var auditEntry = new ai.labs.eddi.engine.audit.model.AuditEntry(
                "ae-1", "conv-1", "agent-1", 1, USER_ID, "unrestricted",
                0, "ai.labs.llm", "llm", 0, 150L,
                java.util.Map.of(), java.util.Map.of(),
                java.util.Map.of("model", "gpt-4"), null,
                List.of("chat_complete"), 0.003,
                java.time.Instant.now(), null, null);
        when(auditStore.getEntriesByUserId(eq(USER_ID), eq(0), eq(10_000)))
                .thenReturn(List.of(auditEntry));

        // When
        UserDataExport export = service.exportUserData(USER_ID);

        // Then
        assertEquals(1, export.auditEntries().size());
        var ae = export.auditEntries().getFirst();
        assertEquals("conv-1", ae.conversationId());
        assertEquals("agent-1", ae.agentId());
        assertEquals("llm", ae.taskType());
        assertEquals(150L, ae.durationMs());
    }

    @Test
    void exportUserData_handlesAuditStoreFailureGracefully() throws Exception {
        // Given — audit store throws, shouldn't prevent export
        when(userMemoryStore.getAllEntries(USER_ID)).thenReturn(List.of());
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenReturn(List.of());
        when(userConversationStore.getAllForUser(USER_ID))
                .thenReturn(List.of());
        when(auditStore.getEntriesByUserId(eq(USER_ID), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("Audit DB unavailable"));

        // When
        UserDataExport export = service.exportUserData(USER_ID);

        // Then — export succeeds with empty audit entries
        assertNotNull(export);
        assertTrue(export.auditEntries().isEmpty());
    }

    // ==================== Processing restriction (Art. 18) ====================

    @Test
    void restrictProcessing_storesRestrictionFlag() throws Exception {
        // When
        service.restrictProcessing(USER_ID);

        // Then — should upsert a user memory entry with the restriction key
        verify(userMemoryStore).upsert(argThat(entry -> "_gdpr_processing_restricted".equals(entry.key())
                && "true".equals(entry.value())
                && entry.userId().equals(USER_ID)));
    }

    @Test
    void restrictProcessing_writesAuditEntry() throws Exception {
        // When
        service.restrictProcessing(USER_ID);

        // Then — should submit an audit entry
        verify(auditLedgerService).submit(any());
    }

    @Test
    void restrictProcessing_throwsRuntimeExceptionOnFailure() throws Exception {
        // Given — upsert fails
        doThrow(new RuntimeException("DB error")).when(userMemoryStore).upsert(any());

        // When/Then — should propagate as RuntimeException
        assertThrows(RuntimeException.class,
                () -> service.restrictProcessing(USER_ID));
    }

    @Test
    void unrestrictProcessing_deletesRestrictionFlag() throws Exception {
        // Given — restriction exists
        var entry = new UserMemoryEntry(
                "entry-id", USER_ID, "_gdpr_processing_restricted", "true",
                "gdpr", ai.labs.eddi.configs.properties.model.Property.Visibility.global,
                null, List.of(), null, false, 0,
                java.time.Instant.now(), java.time.Instant.now());
        when(userMemoryStore.getByKey(USER_ID, "_gdpr_processing_restricted"))
                .thenReturn(Optional.of(entry));

        // When
        service.unrestrictProcessing(USER_ID);

        // Then — should delete the entry by ID
        verify(userMemoryStore).deleteEntry("entry-id");
    }

    @Test
    void unrestrictProcessing_noopIfNotRestricted() throws Exception {
        // Given — no restriction exists
        when(userMemoryStore.getByKey(USER_ID, "_gdpr_processing_restricted"))
                .thenReturn(Optional.empty());

        // When
        service.unrestrictProcessing(USER_ID);

        // Then — deleteEntry should NOT be called
        verify(userMemoryStore, never()).deleteEntry(any());
    }

    @Test
    void unrestrictProcessing_throwsRuntimeExceptionOnFailure() throws Exception {
        // Given — getByKey fails
        when(userMemoryStore.getByKey(USER_ID, "_gdpr_processing_restricted"))
                .thenThrow(new RuntimeException("DB error"));

        // When/Then
        assertThrows(RuntimeException.class,
                () -> service.unrestrictProcessing(USER_ID));
    }

    @Test
    void isProcessingRestricted_returnsTrueWhenRestricted() throws Exception {
        // Given
        var entry = new UserMemoryEntry(
                "entry-id", USER_ID, "_gdpr_processing_restricted", "true",
                "gdpr", ai.labs.eddi.configs.properties.model.Property.Visibility.global,
                null, List.of(), null, false, 0,
                java.time.Instant.now(), java.time.Instant.now());
        when(userMemoryStore.getByKey(USER_ID, "_gdpr_processing_restricted"))
                .thenReturn(Optional.of(entry));

        // When/Then
        assertTrue(service.isProcessingRestricted(USER_ID));
    }

    @Test
    void isProcessingRestricted_returnsFalseWhenNotRestricted() throws Exception {
        // Given — no entry
        when(userMemoryStore.getByKey(USER_ID, "_gdpr_processing_restricted"))
                .thenReturn(Optional.empty());

        // When/Then
        assertFalse(service.isProcessingRestricted(USER_ID));
    }

    @Test
    void isProcessingRestricted_handlesBooleanValueType() throws Exception {
        // Given — value stored as Boolean true instead of String "true"
        var entry = new UserMemoryEntry(
                "entry-id", USER_ID, "_gdpr_processing_restricted", Boolean.TRUE,
                "gdpr", ai.labs.eddi.configs.properties.model.Property.Visibility.global,
                null, List.of(), null, false, 0,
                java.time.Instant.now(), java.time.Instant.now());
        when(userMemoryStore.getByKey(USER_ID, "_gdpr_processing_restricted"))
                .thenReturn(Optional.of(entry));

        // When/Then — String.valueOf(Boolean.TRUE) == "true"
        assertTrue(service.isProcessingRestricted(USER_ID));
    }

    @Test
    void isProcessingRestricted_failsClosedOnException() throws Exception {
        // Given — store throws
        when(userMemoryStore.getByKey(eq(USER_ID), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        // When/Then — should return true (fail-closed) for safety
        assertTrue(service.isProcessingRestricted(USER_ID));
    }

    @Test
    void isProcessingRestricted_returnsFalseWhenValueIsNotTrue() throws Exception {
        // Given — value is "false" not "true"
        var entry = new UserMemoryEntry(
                "entry-id", USER_ID, "_gdpr_processing_restricted", "false",
                "gdpr", ai.labs.eddi.configs.properties.model.Property.Visibility.global,
                null, List.of(), null, false, 0,
                java.time.Instant.now(), java.time.Instant.now());
        when(userMemoryStore.getByKey(USER_ID, "_gdpr_processing_restricted"))
                .thenReturn(Optional.of(entry));

        // When/Then
        assertFalse(service.isProcessingRestricted(USER_ID));
    }

    @Test
    void deleteUserData_continuesWhenConversationDeleteFails() throws Exception {
        // Given
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID))
                .thenThrow(new RuntimeException("Conversation store unavailable"));
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        // When
        GdprDeletionResult result = service.deleteUserData(USER_ID);

        // Then — conversations count is 0, but cascade continued
        assertEquals(0, result.conversationsDeleted());
        verify(userConversationStore).deleteAllForUser(USER_ID);
    }

    @Test
    void deleteUserData_continuesWhenMappingDeleteFails() throws Exception {
        // Given
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(0L);
        when(userConversationStore.deleteAllForUser(USER_ID))
                .thenThrow(new RuntimeException("Mapping store unavailable"));
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        // When
        GdprDeletionResult result = service.deleteUserData(USER_ID);

        // Then
        assertEquals(0, result.conversationMappingsDeleted());
        verify(databaseLogs).pseudonymizeByUserId(eq(USER_ID), anyString());
    }

    @Test
    void deleteUserData_continuesWhenLogPseudonymizeFails() throws Exception {
        // Given
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(0L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString()))
                .thenThrow(new RuntimeException("Log store unavailable"));
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        // When
        GdprDeletionResult result = service.deleteUserData(USER_ID);

        // Then
        assertEquals(0, result.logsPseudonymized());
        verify(auditStore).pseudonymizeByUserId(eq(USER_ID), anyString());
    }

    @Test
    void deleteUserData_continuesWhenAuditPseudonymizeFails() throws Exception {
        // Given
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(0L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString()))
                .thenThrow(new RuntimeException("Audit store unavailable"));

        // When
        GdprDeletionResult result = service.deleteUserData(USER_ID);

        // Then
        assertEquals(0, result.auditEntriesPseudonymized());
        assertNotNull(result.completedAt());
    }

    @Test
    void deleteUserData_submitsComplianceAuditEntry() throws Exception {
        // Given
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(0L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        // When
        service.deleteUserData(USER_ID);

        // Then — audit ledger should get a compliance entry
        verify(auditLedgerService).submit(any());
    }

    @Test
    void exportUserData_submitsComplianceAuditEntry() throws Exception {
        // Given
        when(userMemoryStore.getAllEntries(USER_ID)).thenReturn(List.of());
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of());
        when(userConversationStore.getAllForUser(USER_ID)).thenReturn(List.of());
        when(auditStore.getEntriesByUserId(eq(USER_ID), anyInt(), anyInt())).thenReturn(List.of());

        // When
        service.exportUserData(USER_ID);

        // Then
        verify(auditLedgerService).submit(any());
    }

    @Test
    void deleteUserData_handlesAuditLedgerServiceFailure() throws Exception {
        // Given — audit ledger submission throws
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(0L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        doThrow(new RuntimeException("Audit ledger unavailable")).when(auditLedgerService).submit(any());

        // When — should not throw (audit failure must not break GDPR operation)
        GdprDeletionResult result = service.deleteUserData(USER_ID);

        // Then
        assertNotNull(result);
    }

    @Test
    void exportUserData_handlesConversationListFailure() throws Exception {
        // Given — getConversationIdsByUserId throws
        when(userMemoryStore.getAllEntries(USER_ID)).thenReturn(List.of());
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenThrow(new RuntimeException("Conversation store unavailable"));
        when(userConversationStore.getAllForUser(USER_ID)).thenReturn(List.of());
        when(auditStore.getEntriesByUserId(eq(USER_ID), anyInt(), anyInt())).thenReturn(List.of());

        // When
        UserDataExport export = service.exportUserData(USER_ID);

        // Then — conversations list is empty but export succeeds
        assertTrue(export.conversations().isEmpty());
    }
}
