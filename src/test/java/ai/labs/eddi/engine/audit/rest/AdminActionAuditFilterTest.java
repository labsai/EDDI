/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit.rest;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Administrative REST actions are recorded in the audit ledger — who did what,
 * to which path, with what outcome — and nothing else is: no reads, no
 * conversation traffic, no request bodies, no raw ids of the people an action
 * is about.
 */
class AdminActionAuditFilterTest {

    private AuditLedgerService ledger;
    private SecurityIdentity identity;
    private AdminActionAuditFilter filter;
    private ContainerRequestContext request;
    private ContainerResponseContext response;
    private UriInfo uriInfo;

    /** Stand-in for a resource method. */
    @SuppressWarnings("unused")
    public void deleteUserData() {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        ledger = mock(AuditLedgerService.class);
        when(ledger.isEnabled()).thenReturn(true);
        when(ledger.pseudonymOf(anyString())).thenAnswer(invocation -> "gdpr-erased:k1:" + invocation.getArgument(0).hashCode());
        Instance<AuditLedgerService> ledgerInstance = mock(Instance.class);
        when(ledgerInstance.isResolvable()).thenReturn(true);
        when(ledgerInstance.get()).thenReturn(ledger);

        identity = mock(SecurityIdentity.class);
        Principal principal = () -> "admin-ann";
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(principal);
        Instance<SecurityIdentity> identityInstance = mock(Instance.class);
        when(identityInstance.isResolvable()).thenReturn(true);
        when(identityInstance.get()).thenReturn(identity);

        filter = new AdminActionAuditFilter(ledgerInstance, identityInstance, true);
        ResourceInfo resourceInfo = mock(ResourceInfo.class);
        Method method = AdminActionAuditFilterTest.class.getMethod("deleteUserData");
        when(resourceInfo.getResourceMethod()).thenReturn(method);
        filter.resourceInfo = resourceInfo;

        request = mock(ContainerRequestContext.class);
        response = mock(ContainerResponseContext.class);
        uriInfo = mock(UriInfo.class);
        when(request.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPathParameters()).thenReturn(new MultivaluedHashMap<>());
    }

    private AuditEntry recorded() {
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(ledger).submit(entry.capture());
        return entry.getValue();
    }

    @Test
    @DisplayName("a configuration change is recorded with actor, method, path, resource and status — never the body")
    void recordsAConfigurationChange() {
        when(request.getMethod()).thenReturn("PUT");
        when(uriInfo.getPath()).thenReturn("/llmstore/llms/abc123");
        when(response.getStatus()).thenReturn(200);

        filter.filter(request, response);

        AuditEntry entry = recorded();
        assertEquals("admin-ann", entry.userId());
        assertEquals(AuditLedgerService.ADMIN_ACTION_TASK_ID, entry.taskId());
        assertEquals("PUT", entry.input().get("method"));
        assertEquals("/llmstore/llms/abc123", entry.input().get("path"));
        assertEquals("AdminActionAuditFilterTest#deleteUserData", entry.input().get("resource"));
        assertEquals(200, entry.output().get("status"));
        assertNull(entry.conversationId(), "no conversation, so no chain position");
        verify(request, never()).getEntityStream();
    }

    @Test
    @DisplayName("a refused attempt is on record too")
    void recordsARefusedAttempt() {
        when(request.getMethod()).thenReturn("DELETE");
        when(uriInfo.getPath()).thenReturn("/secretstore/secrets/default/openai");
        when(response.getStatus()).thenReturn(403);

        filter.filter(request, response);

        assertEquals(403, recorded().output().get("status"));
    }

    @Test
    @DisplayName("the id of the person an action is about is pseudonymised — no raw id enters the immutable ledger")
    void pseudonymisesTheSubjectOfTheAction() {
        when(request.getMethod()).thenReturn("DELETE");
        when(uriInfo.getPath()).thenReturn("/admin/gdpr/alice@example.com");
        var parameters = new MultivaluedHashMap<String, String>();
        parameters.add("userId", "alice@example.com");
        when(uriInfo.getPathParameters()).thenReturn(parameters);
        when(response.getStatus()).thenReturn(200);

        filter.filter(request, response);

        String path = (String) recorded().input().get("path");
        assertFalse(path.contains("alice"), path);
        assertTrue(path.startsWith("/admin/gdpr/gdpr-erased:"), path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
    @DisplayName("reads are not recorded")
    void readsAreNotRecorded(String method) {
        when(request.getMethod()).thenReturn(method);
        when(uriInfo.getPath()).thenReturn("/agentstore/agents/abc");

        filter.filter(request, response);

        verify(ledger, never()).submit(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/agents/production/agent1", "/agents/conv-1", "/v1/chat/completions", "/userconversationstore/x/say",
            "/integrations/slack/events", "/connections/abc/callback", "/a2a/agent1"})
    @DisplayName("conversation and data-plane traffic is not recorded here — it is audited per turn")
    void dataPlaneIsNotRecorded(String path) {
        when(request.getMethod()).thenReturn("POST");
        when(uriInfo.getPath()).thenReturn(path);

        filter.filter(request, response);

        verify(ledger, never()).submit(any());
    }

    @Test
    @DisplayName("an unauthenticated caller is recorded as anonymous")
    void anonymousCaller() {
        when(identity.isAnonymous()).thenReturn(true);
        when(request.getMethod()).thenReturn("POST");
        when(uriInfo.getPath()).thenReturn("/administration/production/deploy/agent1");
        when(response.getStatus()).thenReturn(202);

        filter.filter(request, response);

        assertEquals("anonymous", recorded().userId());
    }

    @Test
    @DisplayName("switched off by eddi.audit.admin-actions.enabled=false")
    @SuppressWarnings("unchecked")
    void canBeSwitchedOff() {
        var off = new AdminActionAuditFilter(mock(Instance.class), mock(Instance.class), false);
        when(request.getMethod()).thenReturn("POST");
        when(uriInfo.getPath()).thenReturn("/agentstore/agents");

        off.filter(request, response);

        verify(ledger, never()).submit(any());
    }

    @Test
    @DisplayName("a ledger failure never changes the outcome of the action")
    void ledgerFailureIsSwallowed() {
        doThrow(new IllegalStateException("ledger down")).when(ledger).submit(any());
        when(request.getMethod()).thenReturn("POST");
        when(uriInfo.getPath()).thenReturn("/agentstore/agents");
        when(response.getStatus()).thenReturn(201);

        assertDoesNotThrow(() -> filter.filter(request, response));
    }

    @Test
    void classification() {
        assertTrue(AdminActionAuditFilter.isAdministrative("/backup/import"));
        assertTrue(AdminActionAuditFilter.isAdministrative("admin/gdpr/x"));
        assertTrue(AdminActionAuditFilter.isAdministrative("/conversationstore/conversations/abc"));
        assertFalse(AdminActionAuditFilter.isAdministrative("/Agents/x"));
        assertFalse(AdminActionAuditFilter.isAdministrative("/"));
        assertFalse(AdminActionAuditFilter.isAdministrative(null));
        assertTrue(AdminActionAuditFilter.namesAPerson("userId"));
        assertTrue(AdminActionAuditFilter.namesAPerson("principalId"));
        assertFalse(AdminActionAuditFilter.namesAPerson("agentId"));
    }
}
