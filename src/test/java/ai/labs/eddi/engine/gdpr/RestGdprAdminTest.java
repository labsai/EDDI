/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestGdprAdmin}. Verifies input validation and
 * delegation to {@link GdprComplianceService}.
 *
 * @author ginccc
 * @since 6.0.0
 */
class RestGdprAdminTest {

    private GdprComplianceService gdprService;
    private RestGdprAdmin restAdmin;

    @BeforeEach
    void setUp() {
        gdprService = mock(GdprComplianceService.class);
        restAdmin = new RestGdprAdmin(gdprService);
    }

    // ==================== Input Validation ====================

    @Test
    void deleteUserData_rejectsNullUserId() {
        assertThrows(BadRequestException.class,
                () -> restAdmin.deleteUserData(null));
        verifyNoInteractions(gdprService);
    }

    @Test
    void deleteUserData_rejectsBlankUserId() {
        assertThrows(BadRequestException.class,
                () -> restAdmin.deleteUserData("   "));
        verifyNoInteractions(gdprService);
    }

    @Test
    void exportUserData_rejectsNullUserId() {
        assertThrows(BadRequestException.class,
                () -> restAdmin.exportUserData(null));
        verifyNoInteractions(gdprService);
    }

    @Test
    void exportUserData_rejectsBlankUserId() {
        assertThrows(BadRequestException.class,
                () -> restAdmin.exportUserData(""));
        verifyNoInteractions(gdprService);
    }

    // ==================== Delegation ====================

    @Test
    void deleteUserData_delegatesToService() {
        var expected = new GdprDeletionResult("user-1", 5, 3, 2, 10, 15,
                Instant.now());
        when(gdprService.deleteUserData("user-1")).thenReturn(expected);

        Response response = restAdmin.deleteUserData("user-1");

        assertEquals(200, response.getStatus());
        assertSame(expected, response.getEntity());
        verify(gdprService).deleteUserData("user-1");
    }

    /**
     * A cascade that lost a step must not answer 200. An admin who sees 200 with
     * {@code conversationsDeleted=0} cannot tell "the user had none" from "the
     * delete threw", and files the Art. 17 request as fulfilled either way.
     */
    @Test
    void deleteUserData_reports207WhenACascadeStepFailed() {
        var incomplete = new GdprDeletionResult("user-1", 5, 0, 2, 10, 15,
                0, 0, 0, 0, 0, 0, List.of("conversations"), Instant.now());
        when(gdprService.deleteUserData("user-1")).thenReturn(incomplete);

        Response response = restAdmin.deleteUserData("user-1");

        assertEquals(RestGdprAdmin.MULTI_STATUS, response.getStatus());
        assertSame(incomplete, response.getEntity());
        assertFalse(incomplete.complete());
        assertEquals(List.of("conversations"), incomplete.failedSteps());
    }

    @Test
    void exportUserData_delegatesToService() {
        var expected = new UserDataExport("user-1", Instant.now(),
                List.of(), List.of(), List.of(), List.of());
        when(gdprService.exportUserData("user-1")).thenReturn(expected);

        Response response = restAdmin.exportUserData("user-1");

        assertSame(expected, response.getEntity());
        verify(gdprService).exportUserData("user-1");
    }

    /**
     * Finding r4. A bundle the conversation cap truncated is an INCOMPLETE Art.
     * 15/20 response, and it used to be indistinguishable from a complete one: 200
     * OK, no marker in the payload, the warning in the server log only. The same
     * distinction the 207 above draws for erasure — and now literally the same
     * status, because 206 Partial Content is a range status and RFC 9110 wants a
     * {@code Content-Range} with it that this endpoint neither reads nor produces.
     */
    @Test
    void exportUserData_reports207WhenTheConversationCapTruncatedTheBundle() {
        var truncated = new UserDataExport("user-1", Instant.now(),
                List.of(), List.of(), List.of(), List.of(), List.of(), 1_200, true);
        when(gdprService.exportUserData("user-1")).thenReturn(truncated);

        Response response = restAdmin.exportUserData("user-1");

        assertEquals(RestGdprAdmin.MULTI_STATUS, response.getStatus());
        assertSame(truncated, response.getEntity());
        assertFalse(truncated.complete());
    }

    /**
     * The conversation cap used to be the ONLY completeness check, while this
     * exporter has never covered group transcripts, shared artifacts, schedules or
     * HITL journal entries — categories the erasure half deletes as this user's
     * personal data. So a user with fewer conversations than the cap, and in the
     * limit a user whose data lives <em>only</em> in those four, received a 200
     * documented as "Complete export bundle": an Art. 20 portability answer
     * overstating itself to the one reader who cannot check it.
     */
    @Test
    void exportUserData_reports207WhileWholeCategoriesAreOmitted() {
        var untruncated = new UserDataExport("user-1", Instant.now(),
                List.of(), List.of(), List.of(), List.of(), List.of(), 0, false);
        when(gdprService.exportUserData("user-1")).thenReturn(untruncated);

        Response response = restAdmin.exportUserData("user-1");

        assertEquals(RestGdprAdmin.MULTI_STATUS, response.getStatus(),
                "nothing was truncated, but four whole categories are missing — that is not a complete bundle");
        assertFalse(untruncated.complete());
        assertEquals(List.of("groupConversations", "sharedArtifacts", "schedules", "journalEntries"),
                untruncated.omittedCategories(),
                "and the caller is told which categories, not merely that something is missing");
    }

    /**
     * Finding r15 again, for the export half. {@code complete()} and
     * {@code omittedCategories()} are derived methods — neither record components
     * nor bean getters — so without the annotation Jackson drops them from the REST
     * entity, {@code McpGdprTools} still puts them in the MCP payload, and the
     * status code becomes the only place the REST client is told about the gap.
     */
    @Test
    void exportUserData_restBodyCarriesCompletenessAndOmittedCategories() throws Exception {
        var export = new UserDataExport("user-1", Instant.now(),
                List.of(), List.of(), List.of(), List.of(), List.of(), 0, false);

        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(export);

        assertTrue(json.contains("\"complete\":false"),
                "a bundle whose incompleteness lives only in the status line gets filed as complete: " + json);
        assertTrue(json.contains("\"omittedCategories\":[\"groupConversations\""), json);
    }

    /**
     * Finding r15. {@code complete()} is a derived method, not a record component
     * and not a bean getter, so Jackson left it out of the REST entity while
     * {@code McpGdprTools} puts it into the MCP payload explicitly — the same
     * result in two different shapes, and a client written against the MCP JSON
     * reading null from the REST one.
     */
    @Test
    void deleteUserData_restBodyCarriesCompleteJustLikeTheMcpPayload() throws Exception {
        var incomplete = new GdprDeletionResult("user-1", 5, 0, 2, 10, 15,
                0, 0, 0, 0, 0, 0, List.of("conversations"), Instant.now());

        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(incomplete);

        assertTrue(json.contains("\"complete\":false"),
                "the REST and MCP surfaces must report the same result in the same shape: " + json);
        assertTrue(json.contains("\"failedSteps\":[\"conversations\"]"), json);
    }

    // ==================== Restriction endpoints ====================

    @Test
    void restrictProcessing_rejectsBlankUserId() {
        assertThrows(BadRequestException.class,
                () -> restAdmin.restrictProcessing("  "));
        verifyNoInteractions(gdprService);
    }

    @Test
    void restrictProcessing_delegatesToService() {
        restAdmin.restrictProcessing("user-1");
        verify(gdprService).restrictProcessing("user-1");
    }

    @Test
    void unrestrictProcessing_rejectsNullUserId() {
        assertThrows(BadRequestException.class,
                () -> restAdmin.unrestrictProcessing(null));
        verifyNoInteractions(gdprService);
    }

    @Test
    void unrestrictProcessing_delegatesToService() {
        restAdmin.unrestrictProcessing("user-1");
        verify(gdprService).unrestrictProcessing("user-1");
    }

    @Test
    void isProcessingRestricted_rejectsBlankUserId() {
        assertThrows(BadRequestException.class,
                () -> restAdmin.isProcessingRestricted(""));
        verifyNoInteractions(gdprService);
    }

    @Test
    void isProcessingRestricted_delegatesToService() {
        when(gdprService.isProcessingRestricted("user-1")).thenReturn(true);
        assertTrue(restAdmin.isProcessingRestricted("user-1"));
        verify(gdprService).isProcessingRestricted("user-1");
    }

    @Test
    void isProcessingRestricted_returnsFalse() {
        when(gdprService.isProcessingRestricted("user-1")).thenReturn(false);
        assertFalse(restAdmin.isProcessingRestricted("user-1"));
    }
}
