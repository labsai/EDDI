/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.configs.groups.ISharedArtifactStore;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.SharedArtifact;
import ai.labs.eddi.configs.groups.mongo.GroupConversationStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.audit.AuditHmac;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.IConversationCheckpointStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.runtime.IDatabaseLogs;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Group discussions in the GDPR cascade (security review 2026-10-17, M4 and
 * L9): erasure reaches the blobs a discussion owns, the export carries the
 * discussions and their shared artifacts, and the database-log pseudonym is
 * keyed.
 */
class GdprGroupDataTest {

    private static final String USER_ID = "alice@example.com";

    private IUserMemoryStore userMemoryStore;
    private IConversationMemoryStore conversationMemoryStore;
    private IUserConversationStore userConversationStore;
    private IDatabaseLogs databaseLogs;
    private IAuditStore auditStore;
    private AuditLedgerService auditLedgerService;
    private IAttachmentStore attachmentStore;
    private GroupConversationStore groupConversationStore;
    private ISharedArtifactStore sharedArtifactStore;
    private GdprComplianceService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userMemoryStore = mock(IUserMemoryStore.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        userConversationStore = mock(IUserConversationStore.class);
        databaseLogs = mock(IDatabaseLogs.class);
        auditStore = mock(IAuditStore.class);
        auditLedgerService = mock(AuditLedgerService.class);
        attachmentStore = mock(IAttachmentStore.class);
        groupConversationStore = mock(GroupConversationStore.class);
        sharedArtifactStore = mock(ISharedArtifactStore.class);

        Instance<IAttachmentStore> attachments = mock(Instance.class);
        when(attachments.isResolvable()).thenReturn(true);
        when(attachments.get()).thenReturn(attachmentStore);
        Instance<GroupConversationStore> groups = mock(Instance.class);
        when(groups.isResolvable()).thenReturn(true);
        when(groups.get()).thenReturn(groupConversationStore);
        Instance<ISharedArtifactStore> artifacts = mock(Instance.class);
        when(artifacts.isResolvable()).thenReturn(true);
        when(artifacts.get()).thenReturn(sharedArtifactStore);

        service = new GdprComplianceService(userMemoryStore, conversationMemoryStore, userConversationStore, databaseLogs, auditStore,
                auditLedgerService, attachments, mock(IHitlToolJournalStore.class), mock(IConversationDescriptorStore.class),
                mock(IConversationCheckpointStore.class), groups, artifacts, mock(IScheduleStore.class), new CacheFactory());
    }

    private static GroupConversation discussion(String id, String... memberConversationIds) {
        var gc = new GroupConversation();
        gc.setId(id);
        gc.setGroupId("group-1");
        gc.setUserId(USER_ID);
        gc.setState(GroupConversationState.COMPLETED);
        gc.setOriginalQuestion("What should we ship?");
        for (int i = 0; i < memberConversationIds.length; i++) {
            gc.getMemberConversationIds().put("agent-" + i, memberConversationIds[i]);
        }
        return gc;
    }

    private void groupConversations(GroupConversation... gcs) throws Exception {
        when(groupConversationStore.listAllForUser(eq(USER_ID), eq(0), anyInt()))
                .thenReturn(new GroupConversationStore.UserPage(List.of(gcs), gcs.length));
    }

    // ==================== erasure ====================

    @Test
    void erasure_deletesTheBlobsOfTheUsersDiscussionsAndTheirMemberConversations_beforeTheDocumentsGo() throws Exception {
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of("conv-1"));
        groupConversations(discussion("gc-1", "member-1", "member-2"));
        when(attachmentStore.deleteByConversation("gc-1")).thenReturn(3L);

        GdprDeletionResult result = service.deleteUserData(USER_ID);

        // The discussion's own blobs are keyed by its id, which no 1:1 lookup returns —
        // before M4 an erasure reported success and left them behind.
        verify(attachmentStore).deleteByConversation("conv-1");
        verify(attachmentStore).deleteByConversation("member-1");
        verify(attachmentStore).deleteByConversation("member-2");
        var order = inOrder(attachmentStore, groupConversationStore);
        order.verify(attachmentStore).deleteByConversation("gc-1");
        order.verify(groupConversationStore).deleteAllForUser(USER_ID);
        assertEquals(3L, result.attachmentsDeleted());
        assertTrue(result.complete(), () -> "failed steps: " + result.failedSteps());
    }

    @Test
    void erasure_groupListingFails_isReportedAndTheRestOfTheCascadeRuns() throws Exception {
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of("conv-1"));
        when(groupConversationStore.listAllForUser(eq(USER_ID), anyInt(), anyInt()))
                .thenThrow(new IResourceStore.ResourceStoreException("down"));

        GdprDeletionResult result = service.deleteUserData(USER_ID);

        assertTrue(result.failedSteps().contains("groupConversationAttachments"));
        verify(attachmentStore).deleteByConversation("conv-1");
        verify(groupConversationStore).deleteAllForUser(USER_ID);
    }

    @Test
    void erasure_pagesThroughEveryDiscussion() throws Exception {
        int page = IResourceStorage.MAX_RESULT_LIMIT;
        var first = new ArrayList<GroupConversation>();
        for (int i = 0; i < page; i++) {
            first.add(discussion("gc-a" + i));
        }
        when(groupConversationStore.listAllForUser(USER_ID, 0, page)).thenReturn(new GroupConversationStore.UserPage(first, page));
        when(groupConversationStore.listAllForUser(USER_ID, page, page))
                .thenReturn(new GroupConversationStore.UserPage(List.of(discussion("gc-last")), 1));
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of());

        service.deleteUserData(USER_ID);

        verify(attachmentStore).deleteByConversation("gc-a0");
        verify(attachmentStore).deleteByConversation("gc-last");
    }

    // ==================== export ====================

    @Test
    void export_carriesGroupDiscussionsSharedArtifactsAndTheirBlobInventory() throws Exception {
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of());
        groupConversations(discussion("gc-1", "member-1"));
        var artifact = new SharedArtifact();
        artifact.setId("art-1");
        artifact.setGroupConversationId("gc-1");
        artifact.setOwnerUserId(USER_ID);
        artifact.setName("plan");
        artifact.setContent("the plan");
        when(sharedArtifactStore.listAllForUser(eq(USER_ID), anyInt())).thenReturn(List.of(artifact));
        when(attachmentStore.listByConversation("gc-1"))
                .thenReturn(List.of(new IAttachmentStore.Attachment("ref-1", "brief.pdf", "application/pdf", 10, "gc-1")));

        UserDataExport export = service.exportUserData(USER_ID);

        assertEquals(1, export.groupConversations().size());
        var gc = export.groupConversations().getFirst();
        assertEquals("gc-1", gc.groupConversationId());
        assertEquals("What should we ship?", gc.originalQuestion());
        assertEquals("COMPLETED", gc.state());
        assertEquals(1, export.sharedArtifacts().size());
        assertEquals("the plan", export.sharedArtifacts().getFirst().content());
        assertEquals(1, export.attachments().size());
        assertEquals("gc-1", export.attachments().getFirst().conversationId());
        verify(attachmentStore).listByConversation("member-1");
        assertFalse(export.groupConversationsTruncated());
        assertFalse(export.sharedArtifactsTruncated());
        assertEquals(List.of("schedules", "journalEntries"), export.omittedCategories());
    }

    @Test
    void export_overTheCaps_isMarkedTruncatedAndIncomplete() throws Exception {
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of());
        var many = new ArrayList<GroupConversation>();
        for (int i = 0; i <= GdprComplianceService.GROUP_CONVERSATION_EXPORT_LIMIT; i++) {
            many.add(discussion("gc-" + i));
        }
        groupConversations(many.toArray(GroupConversation[]::new));
        var artifacts = new ArrayList<SharedArtifact>();
        for (int i = 0; i <= GdprComplianceService.SHARED_ARTIFACT_EXPORT_LIMIT; i++) {
            var a = new SharedArtifact();
            a.setId("art-" + i);
            artifacts.add(a);
        }
        when(sharedArtifactStore.listAllForUser(eq(USER_ID), anyInt())).thenReturn(artifacts);

        UserDataExport export = service.exportUserData(USER_ID);

        assertEquals(GdprComplianceService.GROUP_CONVERSATION_EXPORT_LIMIT, export.groupConversations().size());
        assertTrue(export.groupConversationsTruncated());
        assertEquals(GdprComplianceService.SHARED_ARTIFACT_EXPORT_LIMIT, export.sharedArtifacts().size());
        assertTrue(export.sharedArtifactsTruncated());
        assertFalse(export.complete());
    }

    @Test
    void export_atExactlyTheCap_isNotTruncated() throws Exception {
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of());
        var exact = new ArrayList<GroupConversation>();
        for (int i = 0; i < GdprComplianceService.GROUP_CONVERSATION_EXPORT_LIMIT; i++) {
            exact.add(discussion("gc-" + i));
        }
        groupConversations(exact.toArray(GroupConversation[]::new));

        UserDataExport export = service.exportUserData(USER_ID);

        assertEquals(GdprComplianceService.GROUP_CONVERSATION_EXPORT_LIMIT, export.groupConversations().size());
        assertFalse(export.groupConversationsTruncated());
    }

    @Test
    void export_storeFailure_namesTheMissingCategory() throws Exception {
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of());
        when(groupConversationStore.listAllForUser(eq(USER_ID), anyInt(), anyInt()))
                .thenThrow(new IResourceStore.ResourceStoreException("down"));
        when(sharedArtifactStore.listAllForUser(eq(USER_ID), anyInt())).thenThrow(new IResourceStore.ResourceStoreException("down"));

        UserDataExport export = service.exportUserData(USER_ID);

        assertEquals(List.of("groupConversations", "sharedArtifacts"), export.failedCategories());
        assertFalse(export.complete());
    }

    // ==================== pseudonym (L9) ====================

    @Test
    void erasure_writesTheKeyedPseudonymIntoTheDatabaseLogs_andKeepsTheLedgerFallbackVerifiable() {
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of());
        String keyed = AuditHmac.keyedPseudonymFor(USER_ID, new byte[32]);
        when(auditLedgerService.pseudonymOf(USER_ID)).thenReturn(keyed);
        service.pseudonymizer = new GdprPseudonymizer(auditLedgerService, null);

        service.deleteUserData(USER_ID);

        verify(databaseLogs).pseudonymizeByUserId(USER_ID, keyed);
        verify(databaseLogs, never()).pseudonymizeByUserId(USER_ID, AuditHmac.pseudonymFor(USER_ID));
        // The ledger's blanket fallback keeps the token v3/v4 signatures cover.
        verify(auditStore).pseudonymizeByUserId(USER_ID, AuditHmac.pseudonymFor(USER_ID));
        verify(auditLedgerService).submit(argThat(entry -> keyed.equals(entry.userId())));
    }

    @Test
    void export_complianceRecordCarriesTheNewCounts() {
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID)).thenReturn(List.of());

        service.exportUserData(USER_ID);

        verify(auditLedgerService).submit(argThat(entry -> {
            Map<String, Object> output = entry.output();
            return output.containsKey("groupConversationsExported") && output.containsKey("sharedArtifactsExported")
                    && output.containsKey("failedCategories");
        }));
    }
}
