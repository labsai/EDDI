/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.configs.groups.ISharedArtifactStore;
import ai.labs.eddi.configs.groups.mongo.GroupConversationStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.connections.grants.ConnectionGrant;
import ai.labs.eddi.connections.grants.IConnectionGrantStore;
import ai.labs.eddi.connections.grants.InMemoryConnectionGrantStore;
import ai.labs.eddi.connections.oauth.IOAuthStateStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * H9a / H9b: the erasure cascade reaches OAuth connection grants (erase and
 * export), stops the user's in-flight work before deleting anything, and
 * re-sweeps user memories written while it ran.
 */
class GdprErasureInFlightAndGrantsTest {

    private static final String USER_ID = "erased-user";

    private IUserMemoryStore userMemoryStore;
    private InMemoryConnectionGrantStore grantStore;
    private Instance<IConnectionGrantStore> grantStoreInstance;
    private final List<UserErasureParticipant> participants = new ArrayList<>();
    private IOAuthStateStore stateStore;
    private AuditLedgerService auditLedger;
    private GdprComplianceService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userMemoryStore = mock(IUserMemoryStore.class);
        grantStore = new InMemoryConnectionGrantStore();
        grantStoreInstance = mock(Instance.class);
        when(grantStoreInstance.isResolvable()).thenReturn(true);
        when(grantStoreInstance.get()).thenReturn(grantStore);
        Instance<IAttachmentStore> attachments = mock(Instance.class);
        Instance<GroupConversationStore> groups = mock(Instance.class);
        Instance<ISharedArtifactStore> artifacts = mock(Instance.class);
        stateStore = mock(IOAuthStateStore.class);
        Instance<IOAuthStateStore> stateStoreInstance = mock(Instance.class);
        when(stateStoreInstance.isResolvable()).thenReturn(true);
        when(stateStoreInstance.get()).thenReturn(stateStore);
        auditLedger = mock(AuditLedgerService.class);
        service = new GdprComplianceService(userMemoryStore, mock(IConversationMemoryStore.class), mock(IUserConversationStore.class),
                mock(IDatabaseLogs.class), mock(IAuditStore.class), auditLedger, attachments,
                mock(IHitlToolJournalStore.class), mock(IConversationDescriptorStore.class), mock(IConversationCheckpointStore.class),
                groups, artifacts, mock(IScheduleStore.class), grantStoreInstance, stateStoreInstance, participants, new CacheFactory(), 0L);
    }

    private ConnectionGrant grant(String tenant, String connection, String principal) {
        var grant = new ConnectionGrant();
        grant.setTenantId(tenant);
        grant.setConnectionName(connection);
        grant.setPrincipal(principal);
        grant.setEncryptedAccessToken("ACCESS-CIPHERTEXT");
        grant.setEncryptedRefreshToken("REFRESH-CIPHERTEXT");
        grant.setAccessTokenIv("iv-a");
        grant.setRefreshTokenIv("iv-r");
        grant.setScopes(List.of("mail.read"));
        grant.setExpiresAt(Instant.parse("2026-10-01T00:00:00Z"));
        grantStore.upsert(grant);
        return grant;
    }

    @Test
    void deleteUserData_deletesTheUsersGrantsInEveryTenantAndNobodyElses() {
        grant("default", "gmail", USER_ID);
        grant("tenant-b", "jira", USER_ID);
        grant("default", "gmail", "someone-else");

        var result = service.deleteUserData(USER_ID);

        assertTrue(result.complete(), result.failedSteps().toString());
        assertEquals(2, result.connectionGrantsDeleted());
        assertTrue(grantStore.findAllByPrincipal(USER_ID).isEmpty(), "a live refresh token must not outlive the erasure");
        assertEquals(1, grantStore.findAllByPrincipal("someone-else").size());
    }

    @Test
    void deleteUserData_reportsAFailedGrantDeleteAsAnIncompleteErasure() {
        var failing = mock(IConnectionGrantStore.class);
        when(failing.deleteAllByPrincipal(USER_ID)).thenThrow(new IllegalStateException("store down"));
        when(grantStoreInstance.get()).thenReturn(failing);

        var result = service.deleteUserData(USER_ID);

        assertFalse(result.complete());
        assertTrue(result.failedSteps().contains("connectionGrants"));
    }

    @Test
    void exportUserData_listsLinkedAccountsWithoutTokenMaterial() throws Exception {
        grant("default", "gmail", USER_ID);
        grant("default", "gmail", "someone-else");

        var export = service.exportUserData(USER_ID);

        assertEquals(1, export.connectionGrants().size());
        var entry = export.connectionGrants().getFirst();
        assertEquals("gmail", entry.connectionName());
        assertEquals("default", entry.tenantId());
        assertEquals("ACTIVE", entry.status());
        assertEquals(List.of("mail.read"), entry.scopes());
        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(export);
        assertFalse(json.contains("CIPHERTEXT"), json);
        assertFalse(json.contains("iv-a"), json);
    }

    @Test
    void deleteUserData_stopsInFlightWorkBeforeDeletingAnything() throws Exception {
        var participant = mock(UserErasureParticipant.class);
        when(participant.stopInFlightWork(USER_ID)).thenReturn(1);
        participants.add(participant);

        var result = service.deleteUserData(USER_ID);

        assertTrue(result.complete(), result.failedSteps().toString());
        InOrder order = inOrder(participant, userMemoryStore);
        order.verify(participant).stopInFlightWork(USER_ID);
        order.verify(userMemoryStore).countEntries(USER_ID); // step 1, the first store the cascade touches
        order.verify(userMemoryStore, times(2)).deleteAllForUser(USER_ID);
    }

    @Test
    void deleteUserData_aParticipantThatThrowsIsAFailedStepAndTheCascadeStillRuns() throws Exception {
        var participant = mock(UserErasureParticipant.class);
        when(participant.erasureStepName()).thenReturn("runningGroupDiscussions");
        when(participant.stopInFlightWork(USER_ID)).thenThrow(new IllegalStateException("boom"));
        participants.add(participant);

        var result = service.deleteUserData(USER_ID);

        assertEquals(List.of("runningGroupDiscussions"), result.failedSteps());
        verify(userMemoryStore, atLeastOnce()).deleteAllForUser(USER_ID);
    }

    /**
     * Step 0 only signals: a tool call already running, or a turn on another
     * replica, can still write a memory after step 1. The cascade deletes user
     * memories again once everything else is gone.
     */
    @Test
    void deleteUserData_resweepsMemoriesWrittenWhileTheCascadeRan() throws Exception {
        grant("default", "gmail", USER_ID);
        InOrder order = inOrder(userMemoryStore);

        service.deleteUserData(USER_ID);

        order.verify(userMemoryStore, times(2)).deleteAllForUser(USER_ID);
        assertTrue(grantStore.findAllByPrincipal(USER_ID).isEmpty());
    }

    /**
     * A pending authorization flow carries the principal its grant will be bound
     * to; a callback completing after the erasure would mint a fresh grant. The
     * states go before the grants.
     */
    @Test
    void deleteUserData_invalidatesPendingOAuthFlowsBeforeDeletingGrants() {
        var grants = mock(IConnectionGrantStore.class);
        when(grantStoreInstance.get()).thenReturn(grants);

        var result = service.deleteUserData(USER_ID);

        assertTrue(result.complete(), result.failedSteps().toString());
        InOrder order = inOrder(stateStore, grants);
        order.verify(stateStore).deleteByPrincipal(USER_ID);
        order.verify(grants).deleteAllByPrincipal(USER_ID);
    }

    @Test
    void deleteUserData_aFailedStateDeleteIsAFailedStep() {
        when(stateStore.deleteByPrincipal(USER_ID)).thenThrow(new IllegalStateException("store down"));

        var result = service.deleteUserData(USER_ID);

        assertTrue(result.failedSteps().contains("oauthStates"), result.failedSteps().toString());
    }

    /** Late audit entries of cancelled work must not land with the raw id. */
    @Test
    void deleteUserData_tellsTheAuditLedgerBeforeStoppingInFlightWork() {
        var participant = mock(UserErasureParticipant.class);
        participants.add(participant);

        service.deleteUserData(USER_ID);

        InOrder order = inOrder(auditLedger, participant);
        order.verify(auditLedger).markUserErased(USER_ID);
        order.verify(participant).stopInFlightWork(USER_ID);
    }

    /**
     * __service__ owns every tenant's service-bound grants; erasing it as if it
     * were a user would disconnect every agent using a service connection.
     */
    @Test
    void theServicePrincipalCannotBeErasedOrExported() {
        grant("default", "gmail", "__service__");
        grant("tenant-b", "jira", "__service__");

        assertThrows(IllegalArgumentException.class, () -> service.deleteUserData("__service__"));
        assertThrows(IllegalArgumentException.class, () -> service.exportUserData("__service__"));
        assertEquals(2, grantStore.findAllByPrincipal("__service__").size());
    }
}
