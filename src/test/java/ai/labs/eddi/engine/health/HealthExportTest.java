/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthConfigPolicy.Resolved;
import ai.labs.eddi.engine.health.HealthPeriods.Window;
import ai.labs.eddi.engine.health.model.HealthEventCount;
import ai.labs.eddi.engine.health.model.HealthOutcome;
import ai.labs.eddi.engine.health.model.HealthOutcomeCount;
import ai.labs.eddi.engine.health.model.HealthSessionQuery;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import ai.labs.eddi.engine.health.report.HealthReports.Measure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("HealthExport and the business measures (H5)")
class HealthExportTest {

    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    static final Window WINDOW = new Window(TODAY.minusDays(1), TODAY);

    IHealthDailyStore daily;
    IHealthOutcomeStore outcomes;
    IHealthEventStore events;
    HealthConfigPolicy policy;
    HealthExport export;

    static HealthTurnAggregate aggregate(LocalDate day, String origin, long turns, double cost) {
        return new HealthTurnAggregate("agent", 1, origin, day, turns, 2, turns, 1, Map.of(), 0, 0, Map.of(), 0, cost, 100, 1000, turns, 0, 0, 0, 0,
                1, 0, 0, turns, List.of(), 4000, turns, 0);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        daily = mock(IHealthDailyStore.class);
        outcomes = mock(IHealthOutcomeStore.class);
        events = mock(IHealthEventStore.class);
        policy = mock(HealthConfigPolicy.class);
        Map<String, String> declared = new LinkedHashMap<>();
        declared.put("order.placed", "RESOLVED");
        declared.put("order.refunded", "NONE");
        when(policy.current("agent")).thenReturn(new Resolved(Map.of(), true, Map.of(), false, List.of("orderId"), declared, false));
        Instance<IHealthEventStore> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(events);
        export = new HealthExport(daily, outcomes, instance, policy, HealthSettings.defaults());
        when(daily.find(eq("agent"), any(), any())).thenReturn(List.of(aggregate(TODAY, "api", 5, 0.5), aggregate(TODAY, "schedule", 7, 9.0)));
        when(outcomes.counts(any(), eq(true), anyString())).thenReturn(List.of(new HealthOutcomeCount(TODAY, null, "RESOLVED", "external_event",
                true, false, 3, 6), new HealthOutcomeCount(TODAY, null, "ASSUMED_RESOLVED", "silence", true, false, 1, 2)));
        when(events.counts(any(), anyBoolean())).thenReturn(List.of(new HealthEventCount(TODAY, "order.placed", "external_event", 2, 99.5, 2),
                new HealthEventCount(TODAY, "order.shipped", "external_event", 4, 0, 0)));
    }

    private static String write(HealthExport.Body body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.write(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("daily CSV: a header in the fixed order, every day of the window, the selected origins only, declared event columns")
    void dailyCsv() throws Exception {
        String csv = write(export.daily("agent", WINDOW, null, List.of("api"), "csv"));
        String[] lines = csv.split("\r\n");
        assertEquals(3, lines.length, csv);
        List<String> header = List.of(lines[0].split(","));
        assertEquals(HealthExport.DAILY_COLUMNS, header.subList(0, HealthExport.DAILY_COLUMNS.size()));
        assertEquals(List.of("event:order.placed", "event:order.placed:value", "event:order.refunded", "event:order.refunded:value"),
                header.subList(HealthExport.DAILY_COLUMNS.size(), header.size()));
        assertTrue(lines[1].startsWith(WINDOW.from() + ",0,0,"), "an empty day is zeros");
        String[] today = lines[2].split(",");
        assertEquals("5", today[header.indexOf("turns")], "the schedule origin is not selected");
        assertEquals("0.5", today[header.indexOf("costUsd")]);
        assertEquals("4", today[header.indexOf("resolved")]);
        assertEquals("3", today[header.indexOf("resolvedConfirmed")]);
        assertEquals("1", today[header.indexOf("resolvedSilence")]);
        assertEquals("2", today[header.indexOf("event:order.placed")]);
        assertEquals("99.5", today[header.indexOf("event:order.placed:value")]);
        assertFalse(csv.contains("order.shipped"), "an undeclared type is not exported");
        assertFalse(csv.toLowerCase().contains("user"), "no user column");
    }

    @Test
    @DisplayName("daily JSON carries the same rows and its columns")
    void dailyJson() throws Exception {
        JsonNode json = new ObjectMapper().readTree(write(export.daily("agent", WINDOW, null, List.of("api"), "json")));
        assertEquals("daily", json.get("kind").asText());
        assertEquals(2, json.get("items").size());
        assertEquals(5, json.get("items").get(1).get("turns").asInt());
        assertEquals(2, json.get("items").get(1).get("event:order.placed").asInt());
        assertEquals("event:order.placed", json.get("columns").get(HealthExport.DAILY_COLUMNS.size()).asText());
    }

    @Test
    @DisplayName("sessions: the drill-down rows with refs as stored, no user id, paged and capped")
    void sessions() throws Exception {
        Instant t = Instant.parse("2026-11-10T10:00:00Z");
        HealthOutcome s = new HealthOutcome("c1", 0, "agent", 2, "secret-user", "api", true, t, t.plusSeconds(60), 3, 0.2, 0, "RESOLVED",
                "external_event", false, TODAY, null, 0, List.of(), t, List.of("orderId=A-1", "ticketId#ab"));
        HealthOutcome formula = new HealthOutcome("=cmd()", 4, "agent", 2, "u", "api", true, t, t, 1, 0, 0, null, null, false, TODAY, null, 0,
                List.of(), t, List.of());
        when(outcomes.sessions(any(HealthSessionQuery.class))).thenReturn(List.of(s, formula));
        String csv = write(export.sessions("agent", WINDOW, Set.of(2), List.of("api"), "csv"));
        String[] lines = csv.split("\r\n");
        assertEquals(String.join(",", HealthExport.SESSION_COLUMNS), lines[0]);
        assertTrue(lines[1].startsWith("c1,0,"));
        assertTrue(lines[1].endsWith(",orderId=A-1 ticketId#ab"));
        assertTrue(lines[2].startsWith("'=cmd()"), "a formula-looking cell is defused");
        assertFalse(csv.contains("secret-user"));

        JsonNode json = new ObjectMapper().readTree(write(export.sessions("agent", WINDOW, Set.of(2), List.of("api"), "json")));
        assertEquals(2, json.get("items").get(0).get("refs").size());
        assertFalse(json.get("truncated").asBoolean());
        assertNull(json.get("items").get(0).get("userId"));
    }

    @Test
    @DisplayName("sessions past the cap are cut and say so")
    void sessionsCapped() throws Exception {
        Instant t = Instant.parse("2026-11-10T10:00:00Z");
        List<HealthOutcome> page = new ArrayList<>();
        for (int i = 0; i <= HealthExport.PAGE; i++) {
            page.add(new HealthOutcome("c" + i, 0, "agent", 2, "u", "api", true, t, t, 1, 0, 0, null, null, false, TODAY, null, 0, List.of(), t));
        }
        when(outcomes.sessions(any(HealthSessionQuery.class))).thenReturn(page);
        JsonNode json = new ObjectMapper().readTree(write(export.sessions("agent", WINDOW, Set.of(2), List.of("api"), "json")));
        assertEquals(HealthExport.MAX_SESSIONS, json.get("items").size());
        assertTrue(json.get("truncated").asBoolean());
    }

    @Test
    @DisplayName("csv cells are quoted per RFC 4180")
    void csvCells() {
        assertEquals("\"a,b\"", HealthExport.csvCell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", HealthExport.csvCell("say \"hi\""));
        assertEquals("", HealthExport.csvCell(null));
        assertEquals("-1", HealthExport.csvCell(-1), "a negative number is a number");
        assertEquals("'-x", HealthExport.csvCell("-x"));
    }

    @Test
    @DisplayName("business measures: one per declared type — matched counts, split by evidence, value sum, baseline scaled and tested")
    void businessMeasures() {
        Map<String, String> declared = new LinkedHashMap<>();
        declared.put("order.placed", "RESOLVED");
        declared.put("order.refunded", "NONE");
        List<HealthEventCount> now = List.of(new HealthEventCount(null, "order.placed", "external_event", 30, 300.0, 30),
                new HealthEventCount(null, "order.placed", "conversation_id_event", 10, 0, 0),
                new HealthEventCount(null, "undeclared", "external_event", 99, 0, 0));
        List<HealthEventCount> before = List.of(new HealthEventCount(null, "order.placed", "external_event", 10, 0, 0));
        List<Measure> measures = HealthBusinessMeasures.measures(declared, now, before, 0.5, null);
        assertEquals(List.of("event:order.placed", "event:order.refunded"), measures.stream().map(Measure::id).toList());
        Measure placed = measures.get(0);
        assertEquals(40.0, placed.value());
        assertEquals(5.0, placed.baseline(), "scaled to the exposure");
        assertEquals(35.0, placed.delta());
        assertTrue(placed.significant());
        assertEquals("order.placed → RESOLVED", placed.label());
        assertEquals(30.0, placed.breakdown().get(0).value());
        assertEquals(10.0, placed.breakdown().get(1).value());
        assertEquals(300.0, placed.breakdown().get(2).value());
        assertEquals("INFORMATIONAL", placed.kind());
        Measure refunded = measures.get(1);
        assertEquals(0.0, refunded.value());
        assertEquals(HealthBusinessMeasures.HINT_NONE, refunded.hint());
        assertNull(refunded.breakdown().get(2).value(), "no value sum without values");
        Measure unavailable = HealthBusinessMeasures.measures(declared, null, null, 1.0, null).get(0);
        assertNull(unavailable.value());
        assertEquals(HealthBusinessMeasures.HINT_UNAVAILABLE, unavailable.hint());
        assertTrue(HealthBusinessMeasures.measures(null, now, before, 1, null).isEmpty());
    }
}
