/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthAlertState;
import ai.labs.eddi.engine.health.model.HealthProfile;
import ai.labs.eddi.engine.health.model.HealthProfile.Budgets;
import ai.labs.eddi.engine.health.model.HealthProfile.Limits;
import ai.labs.eddi.engine.health.model.HealthProfile.MeasureOverride;
import ai.labs.eddi.engine.health.model.HealthProfile.Slo;
import ai.labs.eddi.engine.health.model.HealthReportLink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The H4 stores — health profiles, HEALTH alert states, owner report links —
 * against a real database, run by each datastore's {@code *IT}. Every store
 * must be over an empty collection/table.
 */
public interface HealthAdminStoresContract {

    IHealthProfileStore profiles();

    IHealthAlertStateStore alertStates();

    IHealthReportLinkStore reportLinks();

    Instant AT = Instant.parse("2026-11-10T12:00:00Z");

    static HealthProfile profile(String agentId, String author) {
        return new HealthProfile(agentId, "sales", List.of("agentFailureRate"), Map.of("resolutionRate", new Limits(0.5, 0.2)),
                new Budgets(0.5, null, 2000.0), new Slo(0.995, 3000L, 20),
                List.of(new MeasureOverride(null, "business", "IGNORED", "CRM outage", "2026-11-14")), null, AT.toString(), author);
    }

    @Test
    @DisplayName("profiles: write, read back as written, batch read, replace, clear the author, delete")
    default void profilesRoundTrip() {
        assertTrue(profiles().read("a").isEmpty());
        profiles().write(profile("a", "alice"));
        profiles().write(profile("b", "bob"));
        HealthProfile read = profiles().read("a").orElseThrow();
        assertEquals("sales", read.preset());
        assertEquals(List.of("agentFailureRate"), read.critical());
        assertEquals(new Limits(0.5, 0.2), read.thresholds().get("resolutionRate"));
        assertEquals(new Budgets(0.5, null, 2000.0), read.budgets());
        assertEquals(new Slo(0.995, 3000L, 20), read.slo());
        assertEquals("CRM outage", read.overrides().get(0).reason());
        assertEquals(Boolean.TRUE, read.stored());
        assertEquals("alice", read.updatedBy());
        assertEquals(List.of("a", "b"), profiles().readAll(List.of("a", "b", "c")).keySet().stream().sorted().toList());
        assertTrue(profiles().readAll(List.of()).isEmpty());

        profiles().write(new HealthProfile("a", "task", null, null, null, null, null, null, AT.toString(), "carol"));
        assertEquals("task", profiles().read("a").orElseThrow().preset());
        assertNull(profiles().read("a").orElseThrow().critical(), "a replace drops what the new body leaves out");

        assertEquals(List.of("b"), profiles().listByAuthor("bob").stream().map(HealthProfile::agentId).toList());
        assertTrue(profiles().listByAuthor("nobody").isEmpty());
        assertEquals(1, profiles().clearAuthor("bob"));
        assertNull(profiles().read("b").orElseThrow().updatedBy());
        assertEquals("sales", profiles().read("b").orElseThrow().preset(), "the profile stays");
        assertTrue(profiles().listByAuthor("bob").isEmpty());
        assertEquals(0, profiles().clearAuthor("bob"), "nothing left to blank");

        assertTrue(profiles().delete("a"));
        assertFalse(profiles().delete("a"));
        assertTrue(profiles().read("a").isEmpty());
    }

    @Test
    @DisplayName("alert states: per rule and agent, replaced, and purged when not evaluated")
    default void alertStatesRoundTrip() {
        alertStates().write(List.of(new HealthAlertState("r1", "a", "WATCH", List.of("burn"), AT),
                new HealthAlertState("r1", "b", "HEALTHY", List.of(), AT.minusSeconds(86_400 * 40L)),
                new HealthAlertState("r2", "a", "NEEDS_ATTENTION", List.of("trend:resolutionRate"), AT)));
        Map<String, HealthAlertState> r1 = alertStates().read("r1", List.of("a", "b", "c"));
        assertEquals(2, r1.size());
        assertEquals("WATCH", r1.get("a").status());
        assertEquals(List.of("burn"), r1.get("a").active());
        assertEquals(AT, r1.get("a").evaluatedAt());
        assertEquals(List.of(), r1.get("b").active());
        assertEquals("NEEDS_ATTENTION", alertStates().read("r2", List.of("a")).get("a").status());

        alertStates().write(List.of(new HealthAlertState("r1", "a", "HEALTHY", List.of(), AT.plusSeconds(60))));
        assertEquals("HEALTHY", alertStates().read("r1", List.of("a")).get("a").status());

        assertEquals(1, alertStates().deleteEvaluatedBefore(AT.minusSeconds(86_400 * 30L)));
        assertTrue(alertStates().read("r1", List.of("b")).isEmpty());
        assertTrue(alertStates().read("r1", List.of()).isEmpty());

        assertEquals(2, alertStates().deleteByAgent("a"), "every rule's state of a deleted agent");
        assertTrue(alertStates().read("r1", List.of("a")).isEmpty());
        assertTrue(alertStates().read("r2", List.of("a")).isEmpty());
    }

    @Test
    @DisplayName("report links: found by token hash only, listed by agent newest first, revoked per agent, expired purged")
    default void reportLinksRoundTrip() {
        HealthReportLink older = new HealthReportLink("r-1", "a", "a".repeat(64), List.of("reliability", "adoption"), "30d", AT.minusSeconds(60),
                "alice", AT.plusSeconds(3600));
        HealthReportLink newer = new HealthReportLink("r-2", "a", "b".repeat(64), List.of("effectiveness"), "7d", AT, "bob", AT.minusSeconds(10));
        HealthReportLink other = new HealthReportLink("r-3", "x", "c".repeat(64), List.of(), "90d", AT, "alice", AT.plusSeconds(3600),
                List.of("team:ops", "team:sales"), true);
        reportLinks().create(older);
        reportLinks().create(newer);
        reportLinks().create(other);

        Optional<HealthReportLink> found = reportLinks().findByTokenHash("a".repeat(64));
        assertEquals(older, found.orElseThrow());
        assertTrue(reportLinks().findByTokenHash("d".repeat(64)).isEmpty());
        assertEquals(List.of("r-2", "r-1"), reportLinks().listByAgent("a").stream().map(HealthReportLink::id).toList());
        HealthReportLink snapshot = reportLinks().findByTokenHash("c".repeat(64)).orElseThrow();
        assertEquals(List.of("team:ops", "team:sales"), snapshot.creatorTeams(), "the creator's team snapshot survives");
        assertTrue(snapshot.creatorAdmin());
        assertEquals(List.of(), older.creatorTeams());
        assertEquals(List.of("r-3", "r-1"), reportLinks().listByCreator("alice").stream().map(HealthReportLink::id).toList());

        assertFalse(reportLinks().delete("x", "r-1"), "revoking needs the link's own agent");
        assertTrue(reportLinks().delete("a", "r-1"));
        assertTrue(reportLinks().findByTokenHash("a".repeat(64)).isEmpty());

        assertEquals(2, reportLinks().clearCreator("alice") + reportLinks().clearCreator("bob"));
        assertNull(reportLinks().listByAgent("x").get(0).createdBy());

        assertEquals(1, reportLinks().deleteExpiredBefore(AT));
        assertTrue(reportLinks().listByAgent("a").isEmpty());
        assertEquals(1, reportLinks().listByAgent("x").size());

        assertEquals(1, reportLinks().deleteByAgent("x"), "revoke all");
        assertTrue(reportLinks().listByAgent("x").isEmpty());
        assertEquals(0, reportLinks().deleteByAgent("x"));
    }
}
