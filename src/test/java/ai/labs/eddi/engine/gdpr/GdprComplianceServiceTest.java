/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.configs.groups.ISharedArtifactStore;
import ai.labs.eddi.configs.groups.mongo.GroupConversationStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.IConversationCheckpointStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IDatabaseLogs;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.engine.triggermanagement.rest.RestUserConversationStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private IConversationDescriptorStore conversationDescriptorStore;
    private IConversationCheckpointStore checkpointStore;
    private GroupConversationStore groupConversationStore;
    private Instance<GroupConversationStore> groupConversationStoreInstance;
    private ISharedArtifactStore sharedArtifactStore;
    private Instance<ISharedArtifactStore> sharedArtifactStoreInstance;
    private IScheduleStore scheduleStore;
    private CacheFactory cacheFactory;

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
        conversationDescriptorStore = mock(IConversationDescriptorStore.class);
        checkpointStore = mock(IConversationCheckpointStore.class);
        groupConversationStore = mock(GroupConversationStore.class);
        groupConversationStoreInstance = mock(Instance.class);
        when(groupConversationStoreInstance.isResolvable()).thenReturn(true);
        when(groupConversationStoreInstance.get()).thenReturn(groupConversationStore);
        sharedArtifactStore = mock(ISharedArtifactStore.class);
        sharedArtifactStoreInstance = mock(Instance.class);
        when(sharedArtifactStoreInstance.isResolvable()).thenReturn(true);
        when(sharedArtifactStoreInstance.get()).thenReturn(sharedArtifactStore);
        scheduleStore = mock(IScheduleStore.class);
        // A real cache factory, not a mock: the cache-invalidation test needs the
        // same Caffeine instance the REST store reads through.
        cacheFactory = new CacheFactory();

        attachmentStorageInstance = mock(Instance.class);
        attachmentStore = mock(IAttachmentStore.class);
        when(attachmentStorageInstance.isResolvable()).thenReturn(false);

        service = newService(attachmentStorageInstance);
    }

    private GdprComplianceService newService(Instance<IAttachmentStore> attachments) {
        return new GdprComplianceService(
                userMemoryStore, conversationMemoryStore,
                userConversationStore, databaseLogs, auditStore,
                auditLedgerService, attachments, hitlToolJournalStore,
                conversationDescriptorStore, checkpointStore,
                groupConversationStoreInstance, sharedArtifactStoreInstance, scheduleStore, cacheFactory);
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
        var inOrder = inOrder(hitlToolJournalStore, conversationDescriptorStore, conversationMemoryStore);
        inOrder.verify(hitlToolJournalStore).deleteByConversationId("conv-1");
        inOrder.verify(hitlToolJournalStore).deleteByConversationId("conv-2");
        inOrder.verify(conversationDescriptorStore).deleteAllDescriptor("conv-1");
        inOrder.verify(conversationDescriptorStore).deleteAllDescriptor("conv-2");
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

        var serviceWithAttachments = newService(attachInstance);

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

        var serviceWithAttachments = newService(attachInstance);

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

    /**
     * Finding 12. The conversation block and the attachment block each called
     * {@code getConversationIdsByUserId} — a second full lookup of the same list on
     * an operation that is already the heaviest read in the system.
     */
    @Test
    void exportUserData_resolvesTheConversationIdListOnce() throws Exception {
        when(userMemoryStore.getAllEntries(USER_ID)).thenReturn(List.of());
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of("conv-1"));
        when(conversationMemoryStore.loadConversationMemorySnapshot("conv-1")).thenReturn(null);
        when(userConversationStore.getAllForUser(USER_ID)).thenReturn(List.of());
        when(auditStore.getEntriesByUserId(eq(USER_ID), anyInt(), anyInt())).thenReturn(List.of());
        when(attachmentStorageInstance.isResolvable()).thenReturn(true);
        when(attachmentStorageInstance.get()).thenReturn(attachmentStore);
        when(attachmentStore.listByConversation("conv-1")).thenReturn(List.of());

        newService(attachmentStorageInstance).exportUserData(USER_ID);

        verify(conversationMemoryStore, times(1)).getConversationIdsByUserId(USER_ID);
    }

    /**
     * Finding 12. Each snapshot is a full document load and the whole bundle is
     * assembled in memory on the request thread, so the same cap that already
     * bounded the audit half of the response now bounds the conversation half. A
     * user with thousands of conversations otherwise held a JAX-RS worker for
     * minutes and produced a response measured in hundreds of megabytes.
     */
    @Test
    void exportUserData_capsTheNumberOfConversationSnapshots() throws Exception {
        var manyIds = new ArrayList<String>();
        for (int i = 0; i < GdprComplianceService.CONVERSATION_EXPORT_LIMIT + 25; i++) {
            manyIds.add("conv-" + i);
        }
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId("agent-1");
        snapshot.setAgentVersion(1);
        snapshot.setConversationState(ConversationState.READY);

        when(userMemoryStore.getAllEntries(USER_ID)).thenReturn(List.of());
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(manyIds);
        when(conversationMemoryStore.loadConversationMemorySnapshot(anyString())).thenReturn(snapshot);
        when(userConversationStore.getAllForUser(USER_ID)).thenReturn(List.of());
        when(auditStore.getEntriesByUserId(eq(USER_ID), anyInt(), anyInt())).thenReturn(List.of());

        UserDataExport export = service.exportUserData(USER_ID);

        assertEquals(GdprComplianceService.CONVERSATION_EXPORT_LIMIT, export.conversations().size());
        verify(conversationMemoryStore, times(GdprComplianceService.CONVERSATION_EXPORT_LIMIT))
                .loadConversationMemorySnapshot(anyString());
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

        var auditEntry = new AuditEntry(
                "ae-1", "conv-1", "agent-1", 1, USER_ID, "unrestricted",
                0, "ai.labs.llm", "llm", 0, 150L,
                Map.of(), Map.of(),
                Map.of("model", "gpt-4"), null,
                List.of("chat_complete"), 0.003,
                Instant.now(), null, null);
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
                "gdpr", Property.Visibility.global,
                null, List.of(), null, false, 0,
                Instant.now(), Instant.now());
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
                "gdpr", Property.Visibility.global,
                null, List.of(), null, false, 0,
                Instant.now(), Instant.now());
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
                "gdpr", Property.Visibility.global,
                null, List.of(), null, false, 0,
                Instant.now(), Instant.now());
        when(userMemoryStore.getByKey(USER_ID, "_gdpr_processing_restricted"))
                .thenReturn(Optional.of(entry));

        // When/Then — String.valueOf(Boolean.TRUE) == "true"
        assertTrue(service.isProcessingRestricted(USER_ID));
    }

    /**
     * Still fail-closed — the turn does not proceed — but no longer fail-dishonest.
     * Returning {@code true} here made every turn for every user a {@code 403}
     * carrying "Processing is restricted for this user (GDPR Art. 18)" whenever the
     * store hiccuped: a false legal statement about the user, and a status code
     * that hides the outage from monitoring keyed on 5xx. The distinct exception is
     * mapped to 503.
     */
    @Test
    void isProcessingRestricted_failsClosedButHonestlyOnException() throws Exception {
        // Given — store throws
        when(userMemoryStore.getByKey(eq(USER_ID), any()))
                .thenThrow(new RuntimeException("Connection refused"));

        // When/Then — processing is still blocked, but as an availability failure
        assertThrows(ProcessingRestrictionUnavailableException.class,
                () -> service.isProcessingRestricted(USER_ID));
    }

    /**
     * The flag is read at conversation start and again on every say/sayStreaming,
     * so an uncached lookup is a store round trip per turn on the hottest path in
     * the system — for a value only an admin endpoint ever changes.
     */
    @Test
    void isProcessingRestricted_isCachedAcrossTurns() throws Exception {
        when(userMemoryStore.getByKey(USER_ID, "_gdpr_processing_restricted"))
                .thenReturn(Optional.empty());

        assertFalse(service.isProcessingRestricted(USER_ID));
        assertFalse(service.isProcessingRestricted(USER_ID));
        assertFalse(service.isProcessingRestricted(USER_ID));

        verify(userMemoryStore, times(1)).getByKey(USER_ID, "_gdpr_processing_restricted");
    }

    /**
     * A cache that outlived a restriction would keep processing a user an admin has
     * just restricted, so both admin endpoints have to publish through it.
     */
    @Test
    void restrictProcessing_takesEffectImmediatelyDespiteTheCache() throws Exception {
        when(userMemoryStore.getByKey(USER_ID, "_gdpr_processing_restricted"))
                .thenReturn(Optional.empty());
        assertFalse(service.isProcessingRestricted(USER_ID), "precondition: cached as unrestricted");

        service.restrictProcessing(USER_ID);

        assertTrue(service.isProcessingRestricted(USER_ID),
                "the restriction must apply to the very next turn, not after the cache TTL");
    }

    /**
     * The other direction, and the one the caching change actually regressed. A
     * cached {@code true} that outlives the lift keeps answering "restricted" for
     * up to {@code RESTRICTION_CACHE_TTL} on that node, so a user whose Art. 18
     * restriction an admin has just removed keeps receiving 403 "Processing is
     * restricted for this user (GDPR Art. 18)" on every turn — a false legal
     * statement about someone the admin has already cleared.
     * <p>
     * The store deliberately keeps answering "restricted" below: only the cache
     * publish in {@code unrestrictProcessing} can make the next read come back
     * false, so nothing else can satisfy this assertion.
     */
    @Test
    void unrestrictProcessing_takesEffectImmediatelyDespiteTheCache() throws Exception {
        var flag = new UserMemoryEntry(
                "entry-id", USER_ID, "_gdpr_processing_restricted", "true",
                "gdpr", Property.Visibility.global,
                null, List.of(), null, false, 0,
                Instant.now(), Instant.now());
        when(userMemoryStore.getByKey(USER_ID, "_gdpr_processing_restricted"))
                .thenReturn(Optional.of(flag));
        assertTrue(service.isProcessingRestricted(USER_ID), "precondition: cached as restricted");

        service.unrestrictProcessing(USER_ID);

        assertFalse(service.isProcessingRestricted(USER_ID),
                "the lift must apply to the very next turn, not after the cache TTL");
    }

    @Test
    void isProcessingRestricted_returnsFalseWhenValueIsNotTrue() throws Exception {
        // Given — value is "false" not "true"
        var entry = new UserMemoryEntry(
                "entry-id", USER_ID, "_gdpr_processing_restricted", "false",
                "gdpr", Property.Visibility.global,
                null, List.of(), null, false, 0,
                Instant.now(), Instant.now());
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

        // ...and the caller can tell that 0 from "the user had no conversations".
        // Without this an admin files the Art. 17 request as fulfilled while the
        // conversations are still there.
        assertFalse(result.complete());
        assertEquals(List.of("conversations"), result.failedSteps());
    }

    /**
     * The clean run is the other half of the same contract: nothing failed, so
     * nothing may be reported as failed.
     */
    @Test
    void deleteUserData_reportsCompleteWhenNothingFailed() throws Exception {
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(1L);
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(1L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        GdprDeletionResult result = service.deleteUserData(USER_ID);

        assertTrue(result.complete());
        assertEquals(List.of(), result.failedSteps());
    }

    /**
     * The count was assigned <em>before</em> the delete, so a delete that threw
     * still reported N memories erased when zero were — the response claiming work
     * the store never did.
     */
    @Test
    void deleteUserData_doesNotClaimMemoriesItFailedToDelete() throws Exception {
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(42L);
        doThrow(new RuntimeException("memory store unavailable")).when(userMemoryStore).deleteAllForUser(USER_ID);
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(0L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        GdprDeletionResult result = service.deleteUserData(USER_ID);

        assertEquals(0, result.memoriesDeleted(), "nothing was deleted, so nothing may be reported as deleted");
        assertFalse(result.complete());
        assertTrue(result.failedSteps().contains("userMemories"));
    }

    /**
     * Finding m1: the per-conversation loops used to sit inside one try, so the
     * first conversation whose delete threw ended the sweep — every remaining
     * conversation kept the PII the cascade exists to remove, and the response
     * still looked plausible.
     */
    @Test
    void deleteUserData_oneFailingConversationDoesNotStopTheCascade() throws Exception {
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenReturn(List.of("conv-1", "conv-2", "conv-3"));
        when(hitlToolJournalStore.deleteByConversationId("conv-1")).thenReturn(1L);
        when(hitlToolJournalStore.deleteByConversationId("conv-2"))
                .thenThrow(new RuntimeException("GridFS chunk missing"));
        when(hitlToolJournalStore.deleteByConversationId("conv-3")).thenReturn(1L);
        when(checkpointStore.deleteByConversationId(anyString())).thenReturn(1L);
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(3L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        GdprDeletionResult result = service.deleteUserData(USER_ID);

        verify(hitlToolJournalStore).deleteByConversationId("conv-3");
        verify(conversationDescriptorStore).deleteAllDescriptor("conv-3");
        verify(checkpointStore).deleteByConversationId("conv-3");
        assertEquals(2, result.journalEntriesDeleted(), "the conversations either side of the failure must still be erased");
        assertEquals(3, result.checkpointsDeleted());
        assertFalse(result.complete());
        assertTrue(result.failedSteps().contains("hitlToolJournal"));
    }

    /**
     * Finding 11: attachments, journal entries, checkpoints, group transcripts,
     * artifacts and schedules were computed and written to the audit ledger but
     * never returned, so the DPO producing an Art. 17 confirmation had to read the
     * server log to learn whether they had been touched.
     */
    @Test
    void deleteUserData_reportsEveryCounterItComputes() throws Exception {
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of("conv-1"));
        when(attachmentStorageInstance.isResolvable()).thenReturn(true);
        when(attachmentStorageInstance.get()).thenReturn(attachmentStore);
        when(attachmentStore.deleteByConversation("conv-1")).thenReturn(4L);
        when(hitlToolJournalStore.deleteByConversationId("conv-1")).thenReturn(3L);
        when(checkpointStore.deleteByConversationId("conv-1")).thenReturn(2L);
        when(groupConversationStore.deleteAllForUser(USER_ID)).thenReturn(6L);
        when(sharedArtifactStore.deleteAllForUser(USER_ID)).thenReturn(7L);
        when(scheduleStore.deleteSchedulesByUserId(USER_ID)).thenReturn(8);
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(1L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        GdprDeletionResult result = newService(attachmentStorageInstance).deleteUserData(USER_ID);

        assertEquals(4, result.attachmentsDeleted());
        assertEquals(3, result.journalEntriesDeleted());
        assertEquals(2, result.checkpointsDeleted());
        assertEquals(6, result.groupConversationsDeleted());
        assertEquals(7, result.sharedArtifactsDeleted());
        assertEquals(8, result.schedulesDeleted());
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

    // ==================== G14: cache invalidation ====================

    /**
     * Erasure used to delete the mapping rows straight from the store while the
     * REST read path served them out of a Caffeine cache that has no TTL and was
     * never invalidated — so {@code readUserConversation} kept returning the erased
     * mapping for the lifetime of the process.
     * <p>
     * The test drives the REAL cache through the REAL REST store, so it fails if
     * the invalidation is removed.
     */
    @Test
    void deleteUserData_invalidatesCachedConversationMappings() throws Exception {
        var mapping = new UserConversation("support", USER_ID, Deployment.Environment.production, "agent-1", "conv-1");

        // authorization.enabled=false — the ownership check is a no-op here, this
        // test is about cache invalidation, not access control.
        var restStore = new RestUserConversationStore(userConversationStore, cacheFactory,
                mock(SecurityIdentity.class), new OwnershipValidator(false));
        when(userConversationStore.readUserConversation("support", USER_ID)).thenReturn(mapping);
        when(userConversationStore.getAllForUser(USER_ID)).thenReturn(List.of(mapping));

        // Warm the cache the way a real read does.
        assertEquals("conv-1", restStore.readUserConversation("support", USER_ID).getConversationId());

        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(0L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(1L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        service.deleteUserData(USER_ID);

        // The store no longer has the row — the only way a value could come back
        // now is the stale cache entry.
        when(userConversationStore.readUserConversation("support", USER_ID)).thenReturn(null);

        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> restStore.readUserConversation("support", USER_ID),
                "a post-erasure read must return nothing, not the cached mapping");
    }

    @Test
    void deleteUserData_readsMappingIntentsBeforeDeletingThem() throws Exception {
        var mapping = new UserConversation("support", USER_ID, Deployment.Environment.production, "agent-1", "conv-1");
        when(userConversationStore.getAllForUser(USER_ID)).thenReturn(List.of(mapping));
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(0L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(1L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        service.deleteUserData(USER_ID);

        // Once the rows are gone there is no way left to derive the cache keys.
        var inOrder = inOrder(userConversationStore);
        inOrder.verify(userConversationStore).getAllForUser(USER_ID);
        inOrder.verify(userConversationStore).deleteAllForUser(USER_ID);
    }

    // ==================== G15: the three missed stores ====================

    @Test
    void deleteUserData_deletesCheckpointsGroupTranscriptsAndSchedules() throws Exception {
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenReturn(List.of("conv-1", "conv-2"));
        when(checkpointStore.deleteByConversationId("conv-1")).thenReturn(2L);
        when(checkpointStore.deleteByConversationId("conv-2")).thenReturn(1L);
        when(groupConversationStore.deleteAllForUser(USER_ID)).thenReturn(4L);
        when(scheduleStore.deleteSchedulesByUserId(USER_ID)).thenReturn(3);
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(2L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        service.deleteUserData(USER_ID);

        // Checkpoints carry a copy of the conversation properties (PII) and must go
        // BEFORE the snapshots they hang off are bulk-deleted.
        var inOrder = inOrder(checkpointStore, conversationMemoryStore);
        inOrder.verify(checkpointStore).deleteByConversationId("conv-1");
        inOrder.verify(checkpointStore).deleteByConversationId("conv-2");
        inOrder.verify(conversationMemoryStore).deleteConversationsByUserId(USER_ID);

        verify(groupConversationStore).deleteAllForUser(USER_ID);
        verify(scheduleStore).deleteSchedulesByUserId(USER_ID);
    }

    @Test
    void deleteUserData_continuesWhenTheNewStoresFail() throws Exception {
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of("conv-1"));
        when(checkpointStore.deleteByConversationId("conv-1")).thenThrow(new RuntimeException("checkpoint store down"));
        when(groupConversationStore.deleteAllForUser(USER_ID)).thenThrow(new RuntimeException("group store down"));
        when(sharedArtifactStore.deleteAllForUser(USER_ID)).thenThrow(new RuntimeException("artifact store down"));
        when(scheduleStore.deleteSchedulesByUserId(USER_ID)).thenThrow(new RuntimeException("schedule store down"));
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(1L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        GdprDeletionResult result = service.deleteUserData(USER_ID);

        assertEquals(1, result.conversationsDeleted());
        verify(scheduleStore).deleteSchedulesByUserId(USER_ID);
    }

    /**
     * I17: shared artifacts carry ownerUserId, so the cascade sweeps them
     * user-keyed — independent of whether their parent discussions still exist.
     */
    @Test
    void deleteUserData_deletesSharedArtifacts() throws Exception {
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of());
        when(sharedArtifactStore.deleteAllForUser(USER_ID)).thenReturn(3L);
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(0L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        service.deleteUserData(USER_ID);

        verify(sharedArtifactStore).deleteAllForUser(USER_ID);
    }

    @Test
    void deleteUserData_skipsSharedArtifacts_whenStoreNotResolvable() throws Exception {
        when(sharedArtifactStoreInstance.isResolvable()).thenReturn(false);
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of());
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(0L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        assertDoesNotThrow(() -> service.deleteUserData(USER_ID));

        verify(sharedArtifactStore, never()).deleteAllForUser(any());
    }
}
