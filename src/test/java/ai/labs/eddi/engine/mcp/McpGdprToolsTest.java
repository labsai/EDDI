/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.gdpr.GdprDeletionResult;
import ai.labs.eddi.engine.gdpr.UserDataExport;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link McpGdprTools}. Verifies role checks, confirmation gate,
 * input validation, and error handling.
 *
 * @author ginccc
 * @since 6.0.0
 */
class McpGdprToolsTest {

    private GdprComplianceService gdprService;
    private IJsonSerialization jsonSerialization;
    private McpGdprTools tools;

    @BeforeEach
    void setUp() throws Exception {
        gdprService = mock(GdprComplianceService.class);
        jsonSerialization = mock(IJsonSerialization.class);
        var identity = mock(SecurityIdentity.class);
        lenient().when(identity.isAnonymous()).thenReturn(true);
        lenient().when(jsonSerialization.serialize(any())).thenReturn("{}");

        tools = new McpGdprTools(gdprService, jsonSerialization, identity,
                false);
    }

    // ==================== delete_user_data ====================

    @Test
    void deleteUserData_rejectsBlankUserId() {
        String result = tools.deleteUserData("", "CONFIRM");

        assertTrue(result.contains("error"));
        verifyNoInteractions(gdprService);
    }

    @Test
    void deleteUserData_rejectsNullUserId() {
        String result = tools.deleteUserData(null, "CONFIRM");

        assertTrue(result.contains("error"));
        verifyNoInteractions(gdprService);
    }

    @Test
    void deleteUserData_rejectsMissingConfirmation() {
        String result = tools.deleteUserData("user-1", "yes");

        assertTrue(result.contains("error"));
        assertTrue(result.contains("CONFIRM"));
        verifyNoInteractions(gdprService);
    }

    @Test
    void deleteUserData_rejectsNullConfirmation() {
        String result = tools.deleteUserData("user-1", null);

        assertTrue(result.contains("error"));
        verifyNoInteractions(gdprService);
    }

    @Test
    void deleteUserData_success() throws Exception {
        var deletionResult = new GdprDeletionResult("user-1", 5, 3, 2, 10,
                15, Instant.now());
        when(gdprService.deleteUserData("user-1")).thenReturn(deletionResult);
        when(jsonSerialization.serialize(any())).thenReturn(
                "{\"status\":\"completed\"}");

        String result = tools.deleteUserData("user-1", "CONFIRM");

        assertNotNull(result);
        assertTrue(result.contains("completed"));
        verify(gdprService).deleteUserData("user-1");
    }

    /**
     * The MCP half of the "erasure filed as fulfilled while the data is still
     * there" defect. {@code status} used to be the literal {@code "completed"}
     * whatever the cascade actually did, so an admin — or an LLM agent acting on
     * this tool's answer — closed an Art. 17 request for a run that lost a step.
     * The REST surface answers 207 for the same run.
     */
    @Test
    void deleteUserData_reportsPartialWhenACascadeStepFailed() throws Exception {
        var partial = new GdprDeletionResult("user-1", 5, 0, 2, 10, 15,
                0, 0, 0, 0, 0, 0, List.of("conversations", "attachments"), Instant.EPOCH);
        when(gdprService.deleteUserData("user-1")).thenReturn(partial);

        tools.deleteUserData("user-1", "CONFIRM");

        Map<String, Object> body = capturedResponseBody();
        assertEquals("partially_completed", body.get("status"),
                "a cascade that lost a step must not be reported as completed");
        assertEquals(Boolean.FALSE, body.get("complete"));
        assertEquals(List.of("conversations", "attachments"), body.get("failedSteps"));
    }

    /**
     * The six counters the cascade has always computed and written to the audit
     * ledger but never returned here, so an MCP caller could not tell whether
     * attachments, journal entries, checkpoints, group transcripts, artifacts or
     * schedules had been touched.
     */
    @Test
    void deleteUserData_reportsEveryCounterTheCascadeComputes() throws Exception {
        var full = new GdprDeletionResult("user-1", 1, 2, 3, 4, 5,
                6, 7, 8, 9, 10, 11, List.of(), Instant.EPOCH);
        when(gdprService.deleteUserData("user-1")).thenReturn(full);

        tools.deleteUserData("user-1", "CONFIRM");

        Map<String, Object> body = capturedResponseBody();
        assertEquals("completed", body.get("status"));
        assertEquals(Boolean.TRUE, body.get("complete"));
        assertEquals(6L, body.get("attachmentsDeleted"));
        assertEquals(7L, body.get("journalEntriesDeleted"));
        assertEquals(8L, body.get("checkpointsDeleted"));
        assertEquals(9L, body.get("groupConversationsDeleted"));
        assertEquals(10L, body.get("sharedArtifactsDeleted"));
        assertEquals(11L, body.get("schedulesDeleted"));
    }

    /**
     * The MCP payload is hand-built, one {@code put} per component, while REST
     * returns the record itself. A component added to {@link GdprDeletionResult}
     * therefore appears in the REST JSON and silently does not appear here — the
     * same erasure reported in two shapes, which is precisely the defect
     * {@code complete} was added to fix, one component later. The six counters this
     * PR restored were missing for exactly that reason.
     * <p>
     * Compared as key sets, not value by value: the point is that nothing can be
     * added to the record without this test noticing, which a per-field assertion
     * cannot do.
     */
    @Test
    void deleteUserData_mcpPayloadCarriesEveryKeyTheRestBodyDoes() throws Exception {
        var full = new GdprDeletionResult("user-1", 1, 2, 3, 4, 5,
                6, 7, 8, 9, 10, 11, List.of(), Instant.EPOCH);
        when(gdprService.deleteUserData("user-1")).thenReturn(full);

        // Sorted, so a mismatch reads as a diff rather than two shuffled lists.
        var expected = new TreeSet<String>();
        for (RecordComponent component : GdprDeletionResult.class.getRecordComponents()) {
            expected.add(component.getName());
        }
        // Derived rather than a component, and on the REST body too — @JsonProperty
        // on GdprDeletionResult.complete() is what puts it there.
        expected.add("complete");
        // MCP-only: the human-readable verdict REST expresses as 200 vs 207.
        expected.add("status");

        tools.deleteUserData("user-1", "CONFIRM");

        assertEquals(expected, new TreeSet<>(capturedResponseBody().keySet()),
                "the MCP and REST surfaces must report the same erasure in the same shape; a key here and "
                        + "not there is a client reading null from one of them");
    }

    /** The map the tool handed to the serializer, i.e. the response it produced. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedResponseBody() throws Exception {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(jsonSerialization).serialize(captor.capture());
        return (Map<String, Object>) captor.getValue();
    }

    @Test
    void deleteUserData_handlesServiceException() {
        when(gdprService.deleteUserData("user-1"))
                .thenThrow(new RuntimeException("DB down"));

        String result = tools.deleteUserData("user-1", "CONFIRM");

        assertTrue(result.contains("error"));
        // Must NOT expose exception details
        assertFalse(result.contains("DB down"));
    }

    // ==================== export_user_data ====================

    @Test
    void exportUserData_rejectsBlankUserId() {
        String result = tools.exportUserData("  ");

        assertTrue(result.contains("error"));
        verifyNoInteractions(gdprService);
    }

    @Test
    void exportUserData_rejectsNullUserId() {
        String result = tools.exportUserData(null);

        assertTrue(result.contains("error"));
        verifyNoInteractions(gdprService);
    }

    @Test
    void exportUserData_success() throws Exception {
        var export = new UserDataExport("user-1", Instant.now(),
                List.of(), List.of(), List.of(), List.of());
        when(gdprService.exportUserData("user-1")).thenReturn(export);
        when(jsonSerialization.serialize(any())).thenReturn(
                "{\"userId\":\"user-1\"}");

        String result = tools.exportUserData("user-1");

        assertNotNull(result);
        assertTrue(result.contains("user-1"));
        verify(gdprService).exportUserData("user-1");
    }

    @Test
    void exportUserData_handlesServiceException() {
        when(gdprService.exportUserData("user-1"))
                .thenThrow(new RuntimeException("Timeout"));

        String result = tools.exportUserData("user-1");

        assertTrue(result.contains("error"));
        // Must NOT expose exception details
        assertFalse(result.contains("Timeout"));
    }
}
