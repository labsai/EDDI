/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit.rest;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.internal.RestAgentEngine;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.engine.triggermanagement.rest.RestUserConversationStore;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.List;
import java.util.stream.Stream;

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
    private ResourceInfo resourceInfo;

    /** Stand-in for a resource method. */
    @SuppressWarnings("unused")
    public void deleteUserData() {
    }

    /**
     * Stand-in for a conversation endpoint, which every conversing role may call.
     */
    @SuppressWarnings("unused")
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user"})
    public void conversationTurn() {
    }

    /** Stand-in for an admin-only resource class whose methods override it. */
    @RolesAllowed("eddi-admin")
    public static class AdminOnlyResource {
        public void inherited() {
        }

        @PermitAll
        public void open() {
        }

        @RolesAllowed({"eddi-admin", "eddi-user"})
        public void forUsers() {
        }
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

        // A real recorder over a mocked ledger: what the filter decides to record is
        // read off the entries the ledger receives. The entry itself is
        // AdminActionRecorderTest's subject.
        filter = new AdminActionAuditFilter(new AdminActionRecorder(ledgerInstance, identityInstance, true));
        resourceInfo = mock(ResourceInfo.class);
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
    @DisplayName("the request's path parameters reach the recorder, so the person an action is about is pseudonymised")
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
    @ValueSource(strings = {"/agents/production/agent1", "/agents/conv-1", "/agents/managed/support/alice", "/v1/chat/completions",
            "/integrations/slack/events", "/connections/callback", "/a2a/agents/agent1"})
    @DisplayName("conversation and data-plane traffic is not recorded here — it is audited per turn")
    void dataPlaneIsNotRecorded(String path) throws Exception {
        when(resourceInfo.getResourceMethod()).thenReturn(AdminActionAuditFilterTest.class.getMethod("conversationTurn"));
        when(request.getMethod()).thenReturn("POST");
        when(uriInfo.getPath()).thenReturn(path);

        filter.filter(request, response);

        verify(ledger, never()).submit(any());
    }

    /**
     * The path rule alone let an admin-only endpoint under a data-plane root go
     * unrecorded: resetting a conversation's state and creating or deleting a
     * user's managed-conversation mapping are administrative by definition — their
     * {@code @RolesAllowed} does not admit {@code eddi-user} — and were on no
     * record. The rows are the real endpoints, so the roles come from where Quarkus
     * reads them: the interface, which the implementation method does not inherit
     * them from.
     */
    static Stream<Arguments> adminOnlyDataPlaneEndpoints() throws Exception {
        return Stream.of(
                Arguments.of("PATCH", "/agents/conv-1/state", RestAgentEngine.class.getMethod("resetState", String.class, String.class), false),
                Arguments.of("POST", "/userconversationstore/userconversations/support/alice", RestUserConversationStore.class
                        .getMethod("createUserConversation", String.class, String.class, UserConversation.class), true),
                Arguments.of("DELETE", "/userconversationstore/userconversations/support/alice",
                        RestUserConversationStore.class.getMethod("deleteUserConversation", String.class, String.class), true));
    }

    @ParameterizedTest
    @MethodSource("adminOnlyDataPlaneEndpoints")
    @DisplayName("an admin-only endpoint under a data-plane root is recorded — its roles make it administrative")
    void adminOnlyEndpointUnderDataPlaneIsRecorded(String method, String path, Method resourceMethod, boolean namesAUser) {
        when(resourceInfo.getResourceMethod()).thenReturn(resourceMethod);
        when(request.getMethod()).thenReturn(method);
        when(uriInfo.getPath()).thenReturn(path);
        if (namesAUser) {
            var parameters = new MultivaluedHashMap<String, String>();
            parameters.add("intent", "support");
            parameters.add("userId", "alice");
            when(uriInfo.getPathParameters()).thenReturn(parameters);
        }
        when(response.getStatus()).thenReturn(200);

        filter.filter(request, response);

        AuditEntry entry = recorded();
        assertEquals("admin-ann", entry.userId());
        assertEquals(method, entry.input().get("method"));
        assertEquals(resourceMethod.getDeclaringClass().getSimpleName() + "#" + resourceMethod.getName(), entry.input().get("resource"));
        String recordedPath = (String) entry.input().get("path");
        assertFalse(recordedPath.contains("alice"), recordedPath);
        if (namesAUser) {
            assertTrue(recordedPath.startsWith("/userconversationstore/userconversations/support/gdpr-erased:"), recordedPath);
        }
    }

    @Test
    @DisplayName("an endpoint under a data-plane root that admits eddi-user stays unrecorded")
    void userEndpointUnderDataPlaneIsNotRecorded() throws Exception {
        when(resourceInfo.getResourceMethod()).thenReturn(RestAgentEngine.class.getMethod("cancelConversation", String.class));
        when(request.getMethod()).thenReturn("POST");
        when(uriInfo.getPath()).thenReturn("/agents/conv-1/cancel");

        filter.filter(request, response);

        verify(ledger, never()).submit(any());
    }

    @Test
    @DisplayName("a method's own security annotation decides before its class's")
    void methodLevelSecurityDecidesBeforeClassLevel() throws Exception {
        assertTrue(AdminActionAuditFilter.adminOnly(AdminOnlyResource.class.getMethod("inherited")));
        assertFalse(AdminActionAuditFilter.adminOnly(AdminOnlyResource.class.getMethod("open")));
        assertFalse(AdminActionAuditFilter.adminOnly(AdminOnlyResource.class.getMethod("forUsers")));
        assertFalse(AdminActionAuditFilter.adminOnly(AdminActionAuditFilterTest.class.getMethod("deleteUserData")), "no roles declared");
    }

    @Test
    @DisplayName("switched off by eddi.audit.admin-actions.enabled=false, before any per-request work")
    @SuppressWarnings("unchecked")
    void canBeSwitchedOff() {
        var off = new AdminActionAuditFilter(new AdminActionRecorder(mock(Instance.class), mock(Instance.class), false));
        off.resourceInfo = resourceInfo;
        when(request.getMethod()).thenReturn("POST");
        when(uriInfo.getPath()).thenReturn("/agentstore/agents");

        off.filter(request, response);

        verify(ledger, never()).submit(any());
        verify(resourceInfo, never()).getResourceMethod();
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
        // M5: platform.* global-variable writes rely on this record.
        assertTrue(AdminActionAuditFilter.isAdministrative("/variablestore/variables/default/platform.operator"));
        assertFalse(AdminActionAuditFilter.isAdministrative("/Agents/x"));
        assertFalse(AdminActionAuditFilter.isAdministrative("/"));
        assertFalse(AdminActionAuditFilter.isAdministrative(null));
    }

    @Test
    @DisplayName("the failure WARN cannot be used to forge log records (CWE-117)")
    void failureLogLineIsSanitized() {
        // A failure on the filter's side of the hand-over: the recorder never throws.
        when(uriInfo.getPathParameters()).thenThrow(new IllegalStateException("lookup failed\r\nFORGED"));
        when(request.getMethod()).thenReturn("POST");
        when(uriInfo.getPath()).thenReturn("/agentstore/agents\r\n2026-01-01 INFO forged admin login");
        when(response.getStatus()).thenReturn(201);

        List<String> captured = AdminActionRecorderTest.logged(AdminActionAuditFilter.class, () -> filter.filter(request, response));

        assertTrue(captured.stream().anyMatch(value -> value.contains("Could not record administrative action")),
                "the line under test did not fire; captured: " + captured);
        for (String value : captured) {
            assertFalse(value.contains("\r") || value.contains("\n"), "a CR/LF reached the log: " + value);
        }
    }
}
