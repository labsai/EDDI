package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IDatabaseLogs;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    private GdprComplianceService service;

    @BeforeEach
    void setUp() {
        userMemoryStore = mock(IUserMemoryStore.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        userConversationStore = mock(IUserConversationStore.class);
        databaseLogs = mock(IDatabaseLogs.class);
        auditStore = mock(IAuditStore.class);

        service = new GdprComplianceService(
                userMemoryStore, conversationMemoryStore,
                userConversationStore, databaseLogs, auditStore);
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
    void exportUserData_handlesEmptyData() throws Exception {
        // Given — user has no data
        when(userMemoryStore.getAllEntries(USER_ID)).thenReturn(List.of());
        when(conversationMemoryStore.getConversationIdsByUserId(USER_ID))
                .thenReturn(List.of());
        when(userConversationStore.getAllForUser(USER_ID))
                .thenReturn(List.of());

        // When
        UserDataExport export = service.exportUserData(USER_ID);

        // Then
        assertEquals(USER_ID, export.userId());
        assertTrue(export.memories().isEmpty());
        assertTrue(export.conversations().isEmpty());
        assertTrue(export.managedConversations().isEmpty());
    }
}
