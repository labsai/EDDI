/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.samples;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.HitlReviewConfig;
import ai.labs.eddi.engine.hitl.oversight.OversightConfigLookup;
import ai.labs.eddi.engine.hitl.oversight.OversightLedger;
import ai.labs.eddi.engine.hitl.oversight.OversightMetrics;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyService;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.AGENT;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.NOW;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.sample;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AuditSampleService — verdicts")
class AuditSampleServiceTest {

    private IAuditSampleStore store;
    private AutonomyService autonomy;
    private OversightLedger ledger;
    private AuditSampleService service;

    @BeforeEach
    void setUp() {
        store = mock(IAuditSampleStore.class);
        autonomy = mock(AutonomyService.class);
        ledger = mock(OversightLedger.class);
        OversightConfigLookup lookup = mock(OversightConfigLookup.class);
        var review = new HitlReviewConfig();
        review.setReasonCodes(Map.of("WRONG_RECIPIENT", "Sent to the wrong person", "OTHER", "Other"));
        var hitl = new AgentConfiguration.HitlConfig();
        hitl.setReview(review);
        when(lookup.hitlConfig(eq(AGENT), any())).thenReturn(hitl);
        when(store.review(any(), anyLong())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));
        service = new AuditSampleService(store, lookup, autonomy, ledger, mock(OversightMetrics.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String code(WebApplicationException e) {
        return String.valueOf(((Map<?, ?>) e.getResponse().getEntity()).get("error"));
    }

    @Test
    @DisplayName("OK: the reviewer is the caller, compare-and-set on the version read, audited; no suspension check")
    void ok() {
        AuditSample reviewed = service.review(sample("s1"), new AuditSampleService.Verdict("ok", "OTHER", " fine ", 1L), "erin");

        assertEquals(AuditSample.Status.OK, reviewed.status());
        assertEquals("erin", reviewed.reviewedBy());
        assertNull(reviewed.reasonCode(), "an OK verdict carries no reason code");
        assertEquals("fine", reviewed.note());
        verify(store).review(any(), eq(1L));
        verify(ledger).write(eq("hitl.sample.review"), eq("conv-1"), eq(AGENT), eq(3), eq("erin"), anyMap());
        verify(autonomy, never()).onSampleFlagged(any());
    }

    @Test
    @DisplayName("FLAGGED needs a reason code from the agent's vocabulary, and is handed to earned autonomy")
    void flagged() {
        var missing = assertThrows(WebApplicationException.class,
                () -> service.review(sample("s1"), new AuditSampleService.Verdict("FLAGGED", null, null, 1L), "erin"));
        assertEquals("UNKNOWN_REASON_CODE", code(missing));
        var unknown = assertThrows(WebApplicationException.class,
                () -> service.review(sample("s1"), new AuditSampleService.Verdict("FLAGGED", "FALSE_CLAIM", null, 1L), "erin"));
        assertEquals("UNKNOWN_REASON_CODE", code(unknown), "the agent's own vocabulary replaces the built-in one");

        service.review(sample("s1"), new AuditSampleService.Verdict("FLAGGED", "WRONG_RECIPIENT", null, 1L), "erin");

        var flagged = ArgumentCaptor.forClass(AuditSample.class);
        verify(autonomy).onSampleFlagged(flagged.capture());
        assertEquals("WRONG_RECIPIENT", flagged.getValue().reasonCode());
    }

    @Test
    @DisplayName("a sample past its review deadline answers 409 and never reaches the store or earned autonomy; one due exactly now is reviewable")
    void pastDeadline() {
        AuditSample open = sample("s1");
        AuditSample late = new AuditSample(open.id(), open.agentId(), open.agentVersion(), open.conversationId(), open.userId(), open.callId(),
                open.toolName(), open.source(), open.endpoint(), open.category(), open.rulePattern(), open.exemptPattern(),
                open.argumentsRedacted(), open.argsDigest(), open.resultRedacted(), open.sampledAt(), NOW.minusSeconds(1), open.status(),
                open.reasonCode(), open.note(), open.reviewedBy(), open.reviewedAt(), open.version());

        var e = assertThrows(WebApplicationException.class,
                () -> service.review(late, new AuditSampleService.Verdict("FLAGGED", "WRONG_RECIPIENT", null, 1L), "erin"));

        assertEquals(409, e.getResponse().getStatus());
        assertEquals("SAMPLE_ALREADY_REVIEWED", code(e));
        verify(store, never()).review(any(), anyLong());
        verify(autonomy, never()).onSampleFlagged(any());
    }

    @Test
    @DisplayName("a sample reviewed meanwhile answers 409 SAMPLE_ALREADY_REVIEWED")
    void conflict() {
        doReturn(Optional.empty()).when(store).review(any(), anyLong());

        var e = assertThrows(WebApplicationException.class,
                () -> service.review(sample("s1"), new AuditSampleService.Verdict("OK", null, null, 1L), "erin"));

        assertEquals(409, e.getResponse().getStatus());
        assertEquals("SAMPLE_ALREADY_REVIEWED", code(e));
        verify(ledger, never()).write(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("verdict and expectedVersion are required; notes are capped")
    void validation() {
        assertEquals("INVALID_PARAMETER", code(assertThrows(WebApplicationException.class,
                () -> service.review(sample("s1"), new AuditSampleService.Verdict("MAYBE", null, null, 1L), "erin"))));
        assertEquals("INVALID_PARAMETER", code(assertThrows(WebApplicationException.class,
                () -> service.review(sample("s1"), new AuditSampleService.Verdict("OK", null, null, null), "erin"))));
        assertEquals("NOTE_TOO_LONG", code(assertThrows(WebApplicationException.class,
                () -> service.review(sample("s1"), new AuditSampleService.Verdict("OK", null, "x".repeat(2001), 1L), "erin"))));
    }
}
