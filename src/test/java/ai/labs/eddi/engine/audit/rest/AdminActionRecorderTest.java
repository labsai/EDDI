/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit.rest;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * An administrative-action record says who did what, to which path, with what
 * outcome — and holds no raw id of the person the action was about, whichever
 * surface (REST or MCP) the action came through.
 */
class AdminActionRecorderTest {

    private AuditLedgerService ledger;
    private Instance<AuditLedgerService> ledgerInstance;
    private SecurityIdentity identity;
    private AdminActionRecorder recorder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ledger = mock(AuditLedgerService.class);
        when(ledger.isEnabled()).thenReturn(true);
        when(ledger.pseudonymOf(anyString())).thenAnswer(invocation -> "gdpr-erased:k1:" + invocation.getArgument(0).hashCode());
        ledgerInstance = mock(Instance.class);
        when(ledgerInstance.isResolvable()).thenReturn(true);
        when(ledgerInstance.get()).thenReturn(ledger);

        identity = mock(SecurityIdentity.class);
        Principal principal = () -> "admin-ann";
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(principal);
        Instance<SecurityIdentity> identityInstance = mock(Instance.class);
        when(identityInstance.isResolvable()).thenReturn(true);
        when(identityInstance.get()).thenReturn(identity);

        recorder = new AdminActionRecorder(ledgerInstance, identityInstance, true);
    }

    private AuditEntry recorded() {
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(ledger).submit(entry.capture());
        return entry.getValue();
    }

    @Test
    @DisplayName("a REST action is recorded with actor, method, path, resource and status, outside any conversation")
    void recordsARestAction() {
        recorder.record("PUT", "/llmstore/llms/abc123", Map.of(), "RestLlmStore#updateLlm", 200);

        AuditEntry entry = recorded();
        assertEquals("admin-ann", entry.userId());
        assertEquals(AuditLedgerService.ADMIN_ACTION_TASK_ID, entry.taskId());
        assertEquals(AdminActionRecorder.TASK_TYPE, entry.taskType());
        assertEquals(Map.of("method", "PUT", "path", "/llmstore/llms/abc123", "resource", "RestLlmStore#updateLlm"), entry.input());
        assertEquals(Map.of("status", 200), entry.output());
        assertEquals(List.of("ADMIN_PUT"), entry.actions());
        assertNull(entry.conversationId(), "no conversation, so no chain position");
    }

    @Test
    @DisplayName("the id of the person an action is about is pseudonymised — no raw id enters the immutable ledger")
    void pseudonymisesTheSubjectOfTheAction() {
        recorder.record("DELETE", "/admin/gdpr/alice@example.com", Map.of("userId", List.of("alice@example.com")), "RestGdprAdmin#deleteUserData",
                200);

        String path = (String) recorded().input().get("path");
        assertFalse(path.contains("alice"), path);
        assertTrue(path.startsWith("/admin/gdpr/gdpr-erased:"), path);
    }

    @Test
    @DisplayName("a user id that is also a path word replaces that segment only, not the rest of the path")
    void pseudonymisesWholeSegmentsOnly() {
        recorder.record("DELETE", "/admin/gdpr/admin", Map.of("userId", List.of("admin")), "RestGdprAdmin#deleteUserData", 200);

        String path = (String) recorded().input().get("path");
        assertTrue(path.startsWith("/admin/gdpr/gdpr-erased:"), path);
        assertEquals(3, path.split("/").length - 1, path);
    }

    @Test
    @DisplayName("a REST user id containing a slash (sent as %2F) is pseudonymised whole")
    void restSubjectContainingASlashIsPseudonymisedWhole() {
        recorder.record("DELETE", "/admin/gdpr/tenant/alice", Map.of("userId", List.of("tenant/alice")), "RestGdprAdmin#deleteUserData", 200);

        String path = (String) recorded().input().get("path");
        assertEquals("/admin/gdpr/gdpr-erased:k1:" + "tenant/alice".hashCode(), path);
        assertFalse(path.contains("tenant") || path.contains("alice"), path);
    }

    /**
     * {@code delete_user_data} and {@code export_user_data} reach the GDPR service
     * without passing through REST; their record names the tool, and the subject
     * only by pseudonym.
     */
    @Test
    @DisplayName("an MCP tool call is recorded with the caller as actor and the subject pseudonymised")
    void recordsAnMcpToolCall() {
        recorder.recordToolCall("delete_user_data", "alice@example.com", "McpGdprTools#deleteUserData", 207);

        AuditEntry entry = recorded();
        assertEquals("admin-ann", entry.userId());
        assertEquals(AuditLedgerService.ADMIN_ACTION_TASK_ID, entry.taskId());
        assertEquals(AdminActionRecorder.MCP_METHOD, entry.input().get("method"));
        assertEquals("/mcp/delete_user_data/gdpr-erased:k1:" + "alice@example.com".hashCode(), entry.input().get("path"));
        assertEquals("McpGdprTools#deleteUserData", entry.input().get("resource"));
        assertEquals(207, entry.output().get("status"));
        assertEquals(List.of("ADMIN_MCP"), entry.actions());
    }

    @Test
    @DisplayName("a subject id containing a slash is pseudonymised whole, not segment by segment")
    void toolCallSubjectIsPseudonymisedWhole() {
        recorder.recordToolCall("export_user_data", "tenant/alice", "McpGdprTools#exportUserData", 200);

        String path = (String) recorded().input().get("path");
        assertFalse(path.contains("alice") || path.contains("tenant"), path);
        verify(ledger).pseudonymOf("tenant/alice");
    }

    @Test
    @DisplayName("a tool call refused before it named a subject is recorded without one")
    void toolCallWithoutSubject() {
        recorder.recordToolCall("delete_user_data", null, "McpGdprTools#deleteUserData", 400);

        assertEquals("/mcp/delete_user_data", recorded().input().get("path"));
        verify(ledger, never()).pseudonymOf(any());
    }

    @Test
    @DisplayName("an unauthenticated caller is recorded as anonymous")
    void anonymousCaller() {
        when(identity.isAnonymous()).thenReturn(true);

        recorder.record("POST", "/administration/production/deploy/agent1", Map.of(), "RestAgentAdministration#deployAgent", 202);

        assertEquals("anonymous", recorded().userId());
    }

    @Test
    @DisplayName("switched off by eddi.audit.admin-actions.enabled=false, for both surfaces")
    @SuppressWarnings("unchecked")
    void canBeSwitchedOff() {
        var off = new AdminActionRecorder(ledgerInstance, mock(Instance.class), false);

        off.record("POST", "/agentstore/agents", Map.of(), "RestAgentStore#createAgent", 201);
        off.recordToolCall("delete_user_data", "alice", "McpGdprTools#deleteUserData", 200);

        assertFalse(off.isEnabled());
        verifyNoInteractions(ledgerInstance);
    }

    @Test
    @DisplayName("nothing is written while the ledger itself is off")
    void ledgerOff() {
        when(ledger.isEnabled()).thenReturn(false);

        recorder.recordToolCall("delete_user_data", "alice", "McpGdprTools#deleteUserData", 200);

        verify(ledger, never()).submit(any());
    }

    @Test
    @DisplayName("a ledger failure never changes the outcome of the action")
    void ledgerFailureIsSwallowed() {
        doThrow(new IllegalStateException("ledger down")).when(ledger).submit(any());

        assertDoesNotThrow(() -> recorder.record("POST", "/agentstore/agents", Map.of(), "RestAgentStore#createAgent", 201));
        assertDoesNotThrow(() -> recorder.recordToolCall("delete_user_data", "alice", "McpGdprTools#deleteUserData", 200));
    }

    @Test
    @DisplayName("the failure WARN cannot be used to forge log records (CWE-117), and does not carry the raw path")
    void failureLogLineIsSanitized() {
        doThrow(new IllegalStateException("ledger down\r\nFORGED")).when(ledger).submit(any());

        List<String> captured = logged(AdminActionRecorder.class, () -> recorder.record("POST\r\n2026-01-01 INFO forged",
                "/admin/gdpr/alice@example.com", Map.of("userId", List.of("alice@example.com")), "RestGdprAdmin#deleteUserData", 201));

        assertTrue(captured.stream().anyMatch(value -> value.contains("Could not record administrative action")),
                "the line under test did not fire; captured: " + captured);
        for (String value : captured) {
            assertFalse(value.contains("\r") || value.contains("\n"), "a CR/LF reached the log: " + value);
            assertFalse(value.contains("alice"), "the raw subject reached the log: " + value);
        }
    }

    @Test
    void namesAPerson() {
        assertTrue(AdminActionRecorder.namesAPerson("userId"));
        assertTrue(AdminActionRecorder.namesAPerson("principalId"));
        assertFalse(AdminActionRecorder.namesAPerson("agentId"));
    }

    /**
     * Runs {@code action} and returns every message and parameter the JUL logger of
     * {@code source} published meanwhile.
     */
    static List<String> logged(Class<?> source, Runnable action) {
        List<String> captured = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(String.valueOf(record.getMessage()));
                if (record.getParameters() != null) {
                    for (Object parameter : record.getParameters()) {
                        captured.add(String.valueOf(parameter));
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        // logging.properties turns ai.labs.eddi OFF for unit tests; open it, or a
        // test reading this passes vacuously.
        Logger julLogger = Logger.getLogger(source.getName());
        Level previous = julLogger.getLevel();
        julLogger.setLevel(Level.ALL);
        julLogger.addHandler(handler);
        try {
            action.run();
        } finally {
            julLogger.removeHandler(handler);
            julLogger.setLevel(previous);
        }
        return captured;
    }
}
