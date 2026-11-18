/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.migration.IMigrationLogStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which grantees the backfill keeps: a grantee is a conversation or a group
 * conversation; absent from both is gone, and a failed lookup is never "gone".
 */
class AttachmentGrantBackfillTest {

    private static final String ID = "6aba70011a25f8be7920e001";

    private IConversationMemoryStore conversations;
    private IGroupConversationStore groups;
    private AttachmentGrantBackfill backfill;

    @BeforeEach
    void setUp() {
        conversations = mock(IConversationMemoryStore.class);
        groups = mock(IGroupConversationStore.class);
        // Absent by default: Mockito answers 0L for a Long, which reads as "exists".
        when(conversations.getRevision(ID)).thenReturn(null);
        backfill = new AttachmentGrantBackfill(mock(IAttachmentGrantMigration.class), mock(IMigrationLogStore.class), conversations, groups);
    }

    @Test
    void aConversationWithAStateExists() throws Exception {
        when(conversations.getConversationState(ID)).thenReturn(ConversationState.READY);
        assertTrue(backfill.granteeExists(ID));
    }

    @Test
    void aConversationStoredWithoutAStateStillExists() throws Exception {
        when(conversations.getConversationState(ID)).thenReturn(null);
        when(conversations.getRevision(ID)).thenReturn(0L);
        when(groups.read(ID)).thenThrow(new IResourceStore.ResourceNotFoundException("no group"));

        assertTrue(backfill.granteeExists(ID));
    }

    @Test
    void aGroupConversationExists() throws Exception {
        when(groups.read(ID)).thenReturn(new GroupConversation());
        assertTrue(backfill.granteeExists(ID));
    }

    @Test
    void absentFromBothIsGone() throws Exception {
        when(groups.read(ID)).thenThrow(new IResourceStore.ResourceNotFoundException("no group"));
        assertFalse(backfill.granteeExists(ID));
    }

    @Test
    void aFailedGroupLookupIsNotGone() throws Exception {
        when(groups.read(ID)).thenThrow(new IResourceStore.ResourceStoreException("unreachable"));
        assertThrows(IResourceStore.ResourceStoreException.class, () -> backfill.granteeExists(ID));
    }
}
