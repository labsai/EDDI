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
import ai.labs.eddi.engine.caching.ICache;
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
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * A service with restriction caching switched <em>on</em>
     * ({@code eddi.gdpr.restriction-cache-ttl-seconds=30}) — the single-node /
     * conversation-affinity optimisation, which is opt-in and not the shipped
     * default. The cache semantics below (publish on restrict, publish on
     * unrestrict, monotone-toward-restriction on a concurrent miss) only exist when
     * it is switched on, so they are pinned against a service that has it. The
     * default is pinned separately, by
     * {@code isProcessingRestricted_defaultConfiguration_*}.
     */
    private GdprComplianceService newService(Instance<IAttachmentStore> attachments) {
        return new GdprComplianceService(
                userMemoryStore, conversationMemoryStore,
                userConversationStore, databaseLogs, auditStore,
                auditLedgerService, attachments, hitlToolJournalStore,
                conversationDescriptorStore, checkpointStore,
                groupConversationStoreInstance, sharedArtifactStoreInstance, scheduleStore, cacheFactory, 30L);
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

    /**
     * A service with {@code eddi.gdpr.restriction-cache-ttl-seconds=0}, the setting
     * a multi-replica deployment without conversation affinity is expected to use.
     */
    private GdprComplianceService newServiceWithoutRestrictionCache() {
        return new GdprComplianceService(
                userMemoryStore, conversationMemoryStore,
                userConversationStore, databaseLogs, auditStore,
                auditLedgerService, attachmentStorageInstance, hitlToolJournalStore,
                conversationDescriptorStore, checkpointStore,
                groupConversationStoreInstance, sharedArtifactStoreInstance, scheduleStore,
                cacheFactory, 0L);
    }

    /**
     * The cache is node-local with no cross-node invalidation, so a cached negative
     * verdict is what lets node B keep processing a user node A has just restricted
     * — for the whole TTL. Setting the TTL to 0 has to remove the cache outright,
     * not merely shorten it: every call reads the store.
     */
    @Test
    void isProcessingRestricted_cachingDisabled_readsTheStoreOnEveryTurn() throws Exception {
        var uncached = newServiceWithoutRestrictionCache();
        when(userMemoryStore.getByKey(USER_ID, "_gdpr_processing_restricted"))
                .thenReturn(Optional.empty());

        assertFalse(uncached.isProcessingRestricted(USER_ID));
        assertFalse(uncached.isProcessingRestricted(USER_ID));
        assertFalse(uncached.isProcessingRestricted(USER_ID));

        verify(userMemoryStore, times(3)).getByKey(USER_ID, "_gdpr_processing_restricted");
    }

    /**
     * The behaviour the previous test protects, stated as the reviewer's scenario:
     * another node applies the Art. 18 restriction — this node never saw the admin
     * call, so nothing invalidates anything here — and the very next turn must be
     * blocked rather than served from a cached {@code false}.
     */
    @Test
    void isProcessingRestricted_cachingDisabled_seesAnotherNodesRestrictionOnTheNextTurn() throws Exception {
        var uncached = newServiceWithoutRestrictionCache();
        var flag = new UserMemoryEntry(
                "entry-id", USER_ID, "_gdpr_processing_restricted", "true",
                "gdpr", Property.Visibility.global,
                null, List.of(), null, false, 0,
                Instant.now(), Instant.now());
        // First turn: not restricted anywhere. Then another node writes the flag.
        when(userMemoryStore.getByKey(USER_ID, "_gdpr_processing_restricted"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(flag));

        assertFalse(uncached.isProcessingRestricted(USER_ID), "precondition: not restricted yet");

        assertTrue(uncached.isProcessingRestricted(USER_ID),
                "a restriction applied on another node must bite on the next turn, not after a TTL");
    }

    /**
     * A service built the way CDI builds it when nothing sets
     * {@code eddi.gdpr.restriction-cache-ttl-seconds} — i.e. carrying the shipped
     * default of that property, whatever it is.
     */
    private GdprComplianceService newServiceWithDefaultConfiguration() {
        return new GdprComplianceService(
                userMemoryStore, conversationMemoryStore,
                userConversationStore, databaseLogs, auditStore,
                auditLedgerService, attachmentStorageInstance, hitlToolJournalStore,
                conversationDescriptorStore, checkpointStore,
                groupConversationStoreInstance, sharedArtifactStoreInstance, scheduleStore, cacheFactory);
    }

    /**
     * The two tests above only hold for a deployment that has explicitly set the
     * TTL to 0. This one pins the <strong>shipped default</strong>, which is the
     * setting almost every deployment actually runs: out of the box, no restriction
     * verdict may be cached, because the cache is node-local with no cross-node
     * invalidation and a cached {@code false} therefore suspends an Art. 18 legal
     * control on every other replica for the length of the TTL.
     * <p>
     * The scenario is the reviewer's: this node answers one turn while the user is
     * unrestricted, another node applies the restriction (nothing invalidates
     * anything here — this node never saw the admin call), and the very next turn
     * on this node must be blocked. A default TTL above 0 fails this: the second
     * call is served from the cache and returns {@code false}.
     */
    @Test
    void isProcessingRestricted_defaultConfiguration_doesNotServeAStaleUnrestrictedVerdict() throws Exception {
        var shipped = newServiceWithDefaultConfiguration();
        var flag = new UserMemoryEntry(
                "entry-id", USER_ID, "_gdpr_processing_restricted", "true",
                "gdpr", Property.Visibility.global,
                null, List.of(), null, false, 0,
                Instant.now(), Instant.now());
        when(userMemoryStore.getByKey(USER_ID, "_gdpr_processing_restricted"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(flag));

        assertFalse(shipped.isProcessingRestricted(USER_ID), "precondition: not restricted yet");

        assertTrue(shipped.isProcessingRestricted(USER_ID),
                "with the shipped default, a restriction applied on another replica must bite on the "
                        + "very next turn — the default must not cache a negative verdict");
        verify(userMemoryStore, times(2)).getByKey(USER_ID, "_gdpr_processing_restricted");
    }

    /**
     * The other half of the same finding: while a negative verdict is cached the
     * node keeps processing straight through a store outage, because it never asks
     * the store. With the cache off, an outage always surfaces as the availability
     * failure it is — which is what fail-closed means here.
     */
    @Test
    void isProcessingRestricted_cachingDisabled_failsClosedOnAnOutageAfterASuccessfulRead() throws Exception {
        var uncached = newServiceWithoutRestrictionCache();
        when(userMemoryStore.getByKey(USER_ID, "_gdpr_processing_restricted"))
                .thenReturn(Optional.empty())
                .thenThrow(new RuntimeException("Connection refused"));

        assertFalse(uncached.isProcessingRestricted(USER_ID), "precondition: one good read");

        assertThrows(ProcessingRestrictionUnavailableException.class,
                () -> uncached.isProcessingRestricted(USER_ID),
                "a cached false must not absorb the outage");
    }

    /**
     * Switching the cache off must not break the admin writes, which publish
     * through it on the default setting.
     */
    @Test
    void restrictAndUnrestrict_cachingDisabled_stillWriteTheStore() throws Exception {
        var uncached = newServiceWithoutRestrictionCache();
        var flag = new UserMemoryEntry(
                "entry-id", USER_ID, "_gdpr_processing_restricted", "true",
                "gdpr", Property.Visibility.global,
                null, List.of(), null, false, 0,
                Instant.now(), Instant.now());
        when(userMemoryStore.getByKey(USER_ID, "_gdpr_processing_restricted"))
                .thenReturn(Optional.of(flag));

        assertDoesNotThrow(() -> uncached.restrictProcessing(USER_ID));
        assertDoesNotThrow(() -> uncached.unrestrictProcessing(USER_ID));

        verify(userMemoryStore).upsert(any(UserMemoryEntry.class));
        verify(userMemoryStore).deleteEntry("entry-id");
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

    /**
     * Finding r4. The cap was reported in the server log only, so a data
     * portability request for a user with 1,200 conversations returned 200 OK and a
     * bundle silently missing 200 of them — an arbitrary 200 on MongoDB, whose
     * natural order is not insertion order. A DPO hands that to the data subject
     * believing it complete, which is the same misreporting the erasure half of
     * this class answers 207 for.
     */
    @Test
    void exportUserData_marksTheBundleTruncatedWhenTheCapBites() throws Exception {
        var manyIds = new ArrayList<String>();
        int total = GdprComplianceService.CONVERSATION_EXPORT_LIMIT + 25;
        for (int i = 0; i < total; i++) {
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

        assertTrue(export.conversationsTruncated(),
                "an incomplete Art. 15 bundle must say so in the payload, not only in the server log");
        assertEquals(total, export.totalConversations(),
                "the caller has to be able to see how much is missing");
    }

    /** A bundle inside the cap is complete, and says so. */
    @Test
    void exportUserData_isNotMarkedTruncatedWithinTheCap() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId("agent-1");
        snapshot.setAgentVersion(1);
        snapshot.setConversationState(ConversationState.READY);

        when(userMemoryStore.getAllEntries(USER_ID)).thenReturn(List.of());
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of("conv-1", "conv-2"));
        when(conversationMemoryStore.loadConversationMemorySnapshot(anyString())).thenReturn(snapshot);
        when(userConversationStore.getAllForUser(USER_ID)).thenReturn(List.of());
        when(auditStore.getEntriesByUserId(eq(USER_ID), anyInt(), anyInt())).thenReturn(List.of());

        UserDataExport export = service.exportUserData(USER_ID);

        assertFalse(export.conversationsTruncated());
        assertEquals(2, export.totalConversations());
    }

    /**
     * A snapshot that fails to load is a conversation missing from the bundle, and
     * it used to go missing silently: logged at WARN, dropped, and every
     * completeness marker in the payload still saying the bundle was whole. Well
     * inside the cap, so {@code conversationsTruncated} — the only signal there was
     * — stayed false, and the DPO handed the data subject an Art. 15 answer the
     * code knew was short.
     * <p>
     * The cap keeps its own marker: the two are different omissions and a caller
     * chasing 998 of 1,000 needs to know which happened.
     */
    @Test
    void exportUserData_namesTheConversationsItCouldNotLoad() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId("agent-1");
        snapshot.setAgentVersion(1);
        snapshot.setConversationState(ConversationState.READY);

        when(userMemoryStore.getAllEntries(USER_ID)).thenReturn(List.of());
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenReturn(List.of("conv-1", "conv-2", "conv-3"));
        when(conversationMemoryStore.loadConversationMemorySnapshot("conv-1")).thenReturn(snapshot);
        when(conversationMemoryStore.loadConversationMemorySnapshot("conv-2"))
                .thenThrow(new RuntimeException("GridFS chunk missing"));
        // Absent rather than throwing: the id came from the same store moments ago,
        // so the document was removed between the two reads and the conversation is
        // just as missing from the bundle.
        when(conversationMemoryStore.loadConversationMemorySnapshot("conv-3")).thenReturn(null);
        when(userConversationStore.getAllForUser(USER_ID)).thenReturn(List.of());
        when(auditStore.getEntriesByUserId(eq(USER_ID), anyInt(), anyInt())).thenReturn(List.of());

        UserDataExport export = service.exportUserData(USER_ID);

        assertEquals(1, export.conversations().size(), "the conversations that did load are still exported");
        assertEquals(List.of("conv-2", "conv-3"), export.failedConversationIds(),
                "a bundle that lost conversations has to name them, or nobody can tell it is short");
        assertFalse(export.conversationsTruncated(),
                "the cap did not bite — overloading its marker would make the two omissions indistinguishable");
        assertEquals(3, export.totalConversations());

        // The ledger entry is the evidence this export happened; "1 of 3 exported"
        // with nothing to explain the other two is a discrepancy nobody can account
        // for afterwards.
        ArgumentCaptor<AuditEntry> entryCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLedgerService).submit(entryCaptor.capture());
        assertEquals(2, entryCaptor.getValue().output().get("conversationsFailed"),
                "the compliance record has to say how many conversations the bundle lost");
    }

    /**
     * Finding r7. The read path is not atomic with {@code restrictProcessing}: T1
     * reads "not restricted" from the store, T2 (an admin applying an Art. 18
     * restriction) writes the row and publishes {@code true}, and T1 then
     * overwrites it with the stale {@code false}. The node keeps processing a
     * restricted user for up to RESTRICTION_CACHE_TTL — 30 seconds of processing
     * someone whose processing must be halted, which is a legal control, not a
     * cache-freshness preference.
     * <p>
     * The interleaving is driven deterministically: the store lookup itself applies
     * the restriction, exactly as a concurrent admin call would between the read
     * and the write that follows it.
     */
    @Test
    void isProcessingRestricted_doesNotOverwriteARestrictionAppliedDuringTheRead() throws Exception {
        when(userMemoryStore.getByKey(eq(USER_ID), anyString())).thenAnswer(invocation -> {
            // Stands in for the admin call landing between this read and its cache
            // write. restrictProcessing publishes "true" through the same cache.
            service.restrictProcessing(USER_ID);
            return Optional.empty();
        });

        boolean firstAnswer = service.isProcessingRestricted(USER_ID);

        assertTrue(firstAnswer, "the administrative write is newer than a read that started before it");
        // And the published verdict must survive: the second call is a cache hit, so
        // a stale false written here would keep answering for the whole TTL.
        reset(userMemoryStore);
        assertTrue(service.isProcessingRestricted(USER_ID),
                "a restriction masked in the cache is a restriction not enforced");
        verify(userMemoryStore, never()).getByKey(anyString(), anyString());
    }

    /**
     * Finding G1/f1-01. {@code isProcessingRestricted} is itself a cache writer, so
     * two turns for the same user can both miss and both publish. T2 misses, reads
     * "not restricted" and publishes {@code false}; T1 misses, reads {@code true}
     * from the store — a DPO applied the Art. 18 restriction on another node, and
     * nothing invalidates this node's cache — and must return its own fresh
     * {@code true}, not the sibling's stale {@code false}.
     * <p>
     * A plain {@code putIfAbsent} is refused here and hands T1 back the
     * {@code false}, so T1 processes a turn its own store read said must be blocked
     * and the node keeps answering {@code false} for the rest of the TTL. That is a
     * fail-open on a legal control.
     * <p>
     * The interleaving is driven deterministically: T1's store lookup runs the
     * whole sibling read inline, exactly where a concurrent one would land.
     */
    @Test
    void isProcessingRestricted_prefersItsOwnFreshRestrictionOverAConcurrentReadersStaleFalse()
            throws Exception {
        var restrictedEntry = new UserMemoryEntry(
                "entry-id", USER_ID, "_gdpr_processing_restricted", "true",
                "gdpr", Property.Visibility.global,
                null, List.of(), null, false, 0,
                Instant.now(), Instant.now());
        var siblingRan = new AtomicBoolean(false);

        when(userMemoryStore.getByKey(eq(USER_ID), anyString())).thenAnswer(invocation -> {
            if (siblingRan.compareAndSet(false, true)) {
                // T2: a second in-flight turn for the same user. It misses the same
                // empty cache, reads "not restricted", and its publish lands first.
                assertFalse(service.isProcessingRestricted(USER_ID),
                        "precondition: the sibling really did observe and publish 'not restricted'");
                return Optional.of(restrictedEntry);
            }
            // The sibling's own store read.
            return Optional.empty();
        });

        assertTrue(service.isProcessingRestricted(USER_ID),
                "a reader must never return a sibling's stale false over the restriction it read itself");

        // And that verdict has to be what the node serves for the rest of the TTL —
        // otherwise every following turn is a cache hit on the stale false.
        reset(userMemoryStore);
        assertTrue(service.isProcessingRestricted(USER_ID),
                "a restriction masked in the cache is a restriction not enforced");
        verify(userMemoryStore, never()).getByKey(anyString(), anyString());
    }

    /**
     * Finding r14. {@code recordFailure} is called from inside the per-conversation
     * loops, so with a stack trace on every call a user with 5,000 conversations
     * produced 20,000 ERROR traces for one erasure request and buried the one line
     * that matters. Repeats are still reported, at WARN, without the identical
     * trace.
     */
    @Test
    void recordFailure_logsTheStackTraceOncePerStepAndStillRecordsRepeats() {
        var failedSteps = new ArrayList<String>();
        var boom = new RuntimeException("store down");

        assertTrue(GdprComplianceService.recordFailure(failedSteps, "attachments", boom, "pseudo"),
                "the first failure of a step carries the trace");
        assertFalse(GdprComplianceService.recordFailure(failedSteps, "attachments", boom, "pseudo"),
                "the 4,999 identical traces after it do not");
        assertTrue(GdprComplianceService.recordFailure(failedSteps, "checkpoints", boom, "pseudo"),
                "a different step is a different failure and carries its own trace");

        assertEquals(List.of("attachments", "checkpoints"), failedSteps,
                "each failing step is still named exactly once for the caller");
    }

    /**
     * Finding m1, attachment half. The attachment sweep isolates EACH conversation,
     * so the store refusing one must not cost the others their erasure — and the
     * step still has to be named in {@code failedSteps}, or a DPO files the Art. 17
     * request as fulfilled while one conversation's uploads are still on disk.
     */
    @Test
    void deleteUserData_oneFailingAttachmentDeleteDoesNotStopTheSweep() throws Exception {
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenReturn(List.of("conv-1", "conv-2"));
        when(attachmentStorageInstance.isResolvable()).thenReturn(true);
        when(attachmentStorageInstance.get()).thenReturn(attachmentStore);
        when(attachmentStore.deleteByConversation("conv-1"))
                .thenThrow(new RuntimeException("GridFS bucket unavailable"));
        when(attachmentStore.deleteByConversation("conv-2")).thenReturn(3L);

        GdprDeletionResult result = newService(attachmentStorageInstance).deleteUserData(USER_ID);

        verify(attachmentStore).deleteByConversation("conv-2");
        assertEquals(3, result.attachmentsDeleted(),
                "only the conversation that actually failed may be missing from the count");
        assertFalse(result.complete());
        assertEquals(List.of("attachments"), result.failedSteps(),
                "one step name, however many conversations failed inside it");
    }

    /**
     * The outer guard of the same step: a CDI {@code Instance} that cannot even be
     * resolved throws before the loop is entered, and that has to be reported as an
     * attachment failure rather than aborting the whole cascade — every later step
     * still has to run.
     */
    @Test
    void deleteUserData_unresolvableAttachmentStoreIsReportedNotFatal() throws Exception {
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of("conv-1"));
        when(attachmentStorageInstance.isResolvable())
                .thenThrow(new IllegalStateException("no attachment storage bean"));
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(1L);

        GdprDeletionResult result = newService(attachmentStorageInstance).deleteUserData(USER_ID);

        assertTrue(result.failedSteps().contains("attachments"), result.failedSteps().toString());
        assertEquals(1, result.conversationsDeleted(),
                "the cascade must carry on past a step that could not start");
        verify(auditStore).pseudonymizeByUserId(eq(USER_ID), anyString());
    }

    /**
     * Descriptors are what the conversation list in the UI reads, so one that
     * survives erasure keeps the user's conversation titles visible. Isolated per
     * conversation for the same reason as the attachment sweep.
     */
    @Test
    void deleteUserData_oneFailingDescriptorDeleteIsRecordedAndTheRestStillGo() throws Exception {
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenReturn(List.of("conv-1", "conv-2"));
        doThrow(new RuntimeException("descriptor store unavailable"))
                .when(conversationDescriptorStore).deleteAllDescriptor("conv-1");

        GdprDeletionResult result = service.deleteUserData(USER_ID);

        verify(conversationDescriptorStore).deleteAllDescriptor("conv-2");
        assertFalse(result.complete());
        assertTrue(result.failedSteps().contains("conversationDescriptors"),
                "an undeleted descriptor still shows the user's conversation titles: " + result.failedSteps());
    }

    /**
     * The intents are read before the rows are deleted precisely because the cache
     * is keyed by intent — once the rows are gone there is no way to work out which
     * keys to evict. A failure to read them therefore gets its own step name: the
     * mappings are still deleted, but the cache goes on serving them.
     */
    @Test
    void deleteUserData_reportsAFailureToReadTheMappedIntents() throws Exception {
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(userConversationStore.getAllForUser(USER_ID))
                .thenThrow(new RuntimeException("mapping store unavailable"));
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(2L);

        GdprDeletionResult result = service.deleteUserData(USER_ID);

        assertEquals(2, result.conversationMappingsDeleted(), "the rows themselves were still erased");
        assertFalse(result.complete());
        assertTrue(result.failedSteps().contains("conversationMappingIntents"),
                "an unevictable cache keeps serving erased mappings, and the response has to say so: "
                        + result.failedSteps());
    }

    /**
     * And the eviction itself. The cache has no TTL, so a failure here means
     * {@code readUserConversation} serves erased data for as long as the process
     * lives — the one outcome an Art. 17 confirmation must never hide.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void deleteUserData_reportsAFailedCacheEviction() throws Exception {
        ICache<String, UserConversation> cache = mock(ICache.class);
        doThrow(new IllegalStateException("cache closed")).when(cache).remove(anyString());
        var failingCacheFactory = mock(CacheFactory.class);
        when(failingCacheFactory.getCache(anyString())).thenReturn((ICache) cache);
        when(failingCacheFactory.getCache(anyString(), any())).thenReturn(mock(ICache.class));

        var mapping = new UserConversation();
        mapping.setIntent("support");
        when(userMemoryStore.countEntries(USER_ID)).thenReturn(0L);
        when(userConversationStore.getAllForUser(USER_ID)).thenReturn(List.of(mapping));
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(1L);

        var serviceWithFailingCache = new GdprComplianceService(
                userMemoryStore, conversationMemoryStore,
                userConversationStore, databaseLogs, auditStore,
                auditLedgerService, attachmentStorageInstance, hitlToolJournalStore,
                conversationDescriptorStore, checkpointStore,
                groupConversationStoreInstance, sharedArtifactStoreInstance, scheduleStore, failingCacheFactory);

        GdprDeletionResult result = serviceWithFailingCache.deleteUserData(USER_ID);

        assertFalse(result.complete());
        assertTrue(result.failedSteps().contains("conversationMappingCache"), result.failedSteps().toString());
    }

    /**
     * The Art. 18 restriction eviction is its own step, not part of the memory
     * delete.
     * <p>
     * It used to sit inside the memory step's try, between the delete and the
     * assignment that records how many entries it removed. An eviction that threw
     * there therefore reported {@code memoriesDeleted=0} and named
     * {@code userMemories} as the failed step — for a delete that had already
     * succeeded. The DPO re-runs an erasure whose memory half was done and cannot
     * tell from the response that the only thing left undone is a cache.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void deleteUserData_aFailedRestrictionEvictionDoesNotDisownTheMemoriesItDeleted() throws Exception {
        ICache<String, Boolean> restrictionCache = mock(ICache.class);
        doThrow(new IllegalStateException("cache closed")).when(restrictionCache).remove(anyString());
        var failingCacheFactory = mock(CacheFactory.class);
        when(failingCacheFactory.getCache(anyString())).thenReturn(mock(ICache.class));
        when(failingCacheFactory.getCache(anyString(), any())).thenReturn((ICache) restrictionCache);

        when(userMemoryStore.countEntries(USER_ID)).thenReturn(42L);
        when(conversationMemoryStore.deleteConversationsByUserId(USER_ID)).thenReturn(0L);
        when(userConversationStore.deleteAllForUser(USER_ID)).thenReturn(0L);
        when(databaseLogs.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);
        when(auditStore.pseudonymizeByUserId(eq(USER_ID), anyString())).thenReturn(0L);

        var serviceWithFailingCache = new GdprComplianceService(
                userMemoryStore, conversationMemoryStore,
                userConversationStore, databaseLogs, auditStore,
                auditLedgerService, attachmentStorageInstance, hitlToolJournalStore,
                conversationDescriptorStore, checkpointStore,
                groupConversationStoreInstance, sharedArtifactStoreInstance, scheduleStore,
                failingCacheFactory, 30L);

        GdprDeletionResult result = serviceWithFailingCache.deleteUserData(USER_ID);

        verify(userMemoryStore).deleteAllForUser(USER_ID);
        assertEquals(42, result.memoriesDeleted(),
                "the delete succeeded, so the response must not report zero memories erased");
        assertFalse(result.failedSteps().contains("userMemories"),
                "the memory store did not fail; naming it sends the DPO to re-run a step that ran: "
                        + result.failedSteps());
        assertTrue(result.failedSteps().contains("restrictionCache"),
                "a stale 'restricted' verdict left in the cache is still a failure, and has to be named as "
                        + "its own: " + result.failedSteps());
    }

    /**
     * Caffeine rejects a null key outright, so the restriction cache must not be
     * asked about one. A null user is "not restricted" — there is nothing to look
     * up and nothing to cache — rather than an exception on the hottest path in the
     * system.
     */
    @Test
    void isProcessingRestricted_nullUserIsNotRestrictedAndIsNeverLookedUp() throws Exception {
        assertFalse(service.isProcessingRestricted(null));

        verify(userMemoryStore, never()).getByKey(any(), anyString());
    }

    /**
     * The Art. 18 write refuses a null subject before it touches any store, and
     * says why.
     * <p>
     * Worth pinning because it is what makes {@code forgetRestriction}'s own null
     * guard unreachable: every mutating entry point derives a pseudonym first, and
     * that derivation rejects null. Were this to become lenient, the catch blocks
     * that call {@code forgetRestriction} would start handing Caffeine a null key,
     * and the resulting NPE would replace the failure they exist to report.
     */
    @Test
    void restrictProcessing_rejectsANullSubjectBeforeTouchingAnyStore() throws Exception {
        var thrown = assertThrows(RuntimeException.class, () -> service.restrictProcessing(null));

        assertEquals("userId must not be null when deriving a GDPR pseudonym", thrown.getMessage());
        verify(userMemoryStore, never()).upsert(any());
    }

    /**
     * The two halves of the restriction-cache default must stay equal.
     * <p>
     * A {@code @ConfigProperty} default has to be a compile-time String, and the
     * CDI-free constructor needs the number, so the value exists twice. Changing
     * one and not the other would silently give the annotated constructor a
     * different default from the plain one - the shipped default from Quarkus, and
     * something else everywhere the class is built directly, including these tests.
     */
    @Test
    void theTwoDefaultConstantsAgree() {
        assertEquals(GdprComplianceService.RESTRICTION_CACHE_TTL_DEFAULT,
                Long.toString(GdprComplianceService.RESTRICTION_CACHE_TTL_DEFAULT_SECONDS),
                "the @ConfigProperty default and the constructor default are the same number or the class "
                        + "has two different shipped defaults depending on how it was built");
    }

}
