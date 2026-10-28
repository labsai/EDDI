/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.AlertGdprParticipant.ExportedAcknowledgement;
import ai.labs.eddi.engine.alerts.AlertGdprParticipant.ExportedRule;
import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.alerts.model.ErrorKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.alerts.AlertTestData.AGENT;
import static ai.labs.eddi.engine.alerts.AlertTestData.CONVERSATION;
import static ai.labs.eddi.engine.alerts.AlertTestData.NOW;
import static ai.labs.eddi.engine.alerts.AlertTestData.RULE_ID;
import static ai.labs.eddi.engine.alerts.AlertTestData.errorRule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AlertGdprParticipant — erasure and export of the people who run agents")
class AlertGdprParticipantTest {

    private IAlertEventStore events;
    private IAlertRuleStore rules;
    private AlertErasureRedactor redactor;
    private AlertGdprParticipant participant;

    @BeforeEach
    void setUp() {
        events = mock(IAlertEventStore.class);
        rules = mock(IAlertRuleStore.class);
        redactor = mock(AlertErasureRedactor.class);
        participant = new AlertGdprParticipant(events, rules, redactor);
    }

    private static AlertRule rule(String id, String createdBy, String updatedBy) {
        return errorRule(List.of(ErrorKind.TURN_ERROR)).withId(id).withAudit(createdBy, NOW, updatedBy, NOW.plusSeconds(60));
    }

    private static AlertEvent acknowledged(String id, String by) {
        return new AlertEvent(id, RULE_ID, "Broken turns", AlertSource.CONVERSATION_ERROR, AlertEvent.Kind.ALERT, AGENT, 2, CONVERSATION, NOW,
                "summary with details", new AlertMatch(List.of("errorKind: TURN_ERROR"), null, null, null, null, null, null, null, null),
                List.of(), AlertEvent.State.INBOX_ONLY, null, "k-" + id, 0, null, null, by, NOW.plusSeconds(300), 1);
    }

    @Test
    @DisplayName("erasure forgets the user as acknowledger and as rule author")
    void erase() {
        when(events.clearAcknowledger("erin")).thenReturn(3L);
        when(rules.clearAuthor("erin")).thenReturn(2L);
        when(redactor.redactOutputValues("erin")).thenReturn(4L);
        assertEquals(9L, participant.erase("erin"));
        verify(redactor).redactOutputValues("erin");
        verify(events).clearAcknowledger("erin");
        verify(rules).clearAuthor("erin");
    }

    @Test
    @DisplayName("the export lists the user's rules with their role and the events they acknowledged — nobody else's name, no content")
    @SuppressWarnings("unchecked")
    void export() {
        when(rules.findByAuthor("erin", AlertGdprParticipant.MAX_EXPORTED)).thenReturn(List.of(rule("r1", "erin", "frank"),
                rule("r2", "gina", "erin"), rule("r3", "erin", "erin")));
        when(events.findAcknowledgedBy("erin", AlertGdprParticipant.MAX_EXPORTED)).thenReturn(List.of(acknowledged("e1", "erin")));

        Map<String, Object> export = (Map<String, Object>) participant.export("erin");

        List<ExportedRule> exportedRules = (List<ExportedRule>) export.get("rules");
        assertEquals(List.of("author"), exportedRules.get(0).roles());
        assertEquals(List.of("lastEditor"), exportedRules.get(1).roles());
        assertEquals(List.of("author", "lastEditor"), exportedRules.get(2).roles());
        assertEquals("r1", exportedRules.get(0).id());
        assertEquals(NOW, exportedRules.get(0).createdAt());
        assertEquals(NOW.plusSeconds(60), exportedRules.get(0).updatedAt());

        List<ExportedAcknowledgement> acks = (List<ExportedAcknowledgement>) export.get("acknowledgedEvents");
        assertEquals(List.of(new ExportedAcknowledgement("e1", "Broken turns", "CONVERSATION_ERROR", AGENT, NOW, NOW.plusSeconds(300))), acks);

        assertEquals(List.of(), export.get("outputMatchValues"));

        String rendered = export.toString();
        assertFalse(rendered.contains("frank"), "another author is not named");
        assertFalse(rendered.contains("gina"), "another author is not named");
        assertFalse(rendered.contains("summary with details"), "no event content");
        assertFalse(rendered.contains(CONVERSATION), "no conversation id");
    }

    @Test
    @DisplayName("an end user's export lists the output-match values stored from their conversations — value, conversation, time")
    @SuppressWarnings("unchecked")
    void exportsOwnOutputValues() {
        AlertEvent withValue = new AlertEvent("e5", RULE_ID, "Escalations", AlertSource.OUTPUT_MATCH, AlertEvent.Kind.ALERT, AGENT, 2,
                CONVERSATION, NOW, "summary", AlertMatch.output("properties.note exists", "call me"), List.of(), AlertEvent.State.INBOX_ONLY, null,
                "k5", 0, null, null, null, null, 1);
        when(events.findOutputValuesBySubject("rater-1", AlertGdprParticipant.MAX_EXPORTED)).thenReturn(List.of(withValue));
        Map<String, Object> export = (Map<String, Object>) participant.export("rater-1");
        assertEquals(List.of(new AlertGdprParticipant.ExportedOutputValue("call me", CONVERSATION, NOW)), export.get("outputMatchValues"));
        assertFalse(export.toString().contains("Escalations"), "no rule internals");
    }

    @Test
    @DisplayName("a row the store returned for somebody else is not exported; nothing to export is null")
    void defensiveAndEmpty() {
        when(rules.findByAuthor("erin", AlertGdprParticipant.MAX_EXPORTED)).thenReturn(List.of(rule("r9", "frank", "frank")));
        when(events.findAcknowledgedBy("erin", AlertGdprParticipant.MAX_EXPORTED)).thenReturn(List.of(acknowledged("e9", "frank")));
        assertNull(participant.export("erin"));

        when(rules.findByAuthor(anyString(), anyInt())).thenReturn(List.of());
        when(events.findAcknowledgedBy(anyString(), anyInt())).thenReturn(List.of());
        assertNull(participant.export("nobody"));
        assertNull(participant.export(" "));
    }
}
