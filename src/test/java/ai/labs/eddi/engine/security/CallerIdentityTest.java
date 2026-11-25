/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code teamSubjects} component of {@link CallerIdentity}: a defensive,
 * never-null snapshot that existing construction sites do not have to know
 * about.
 */
class CallerIdentityTest {

    private static final String SALES = "team:acme%2Fsales";
    private static final String LEGAL = "team:acme%2Flegal";

    @Test
    @DisplayName("every shorter constructor yields an identity that belongs to no team")
    void shorterConstructorsHaveNoTeams() {
        assertEquals(List.of(), new CallerIdentity("tok", "alice", "https://eddi.example:443").teamSubjects());
        assertEquals(List.of(), new CallerIdentity("tok", "alice", "https://eddi.example:443", Map.of()).teamSubjects());
        assertEquals(List.of(), new CallerIdentity("tok", "alice", "https://eddi.example:443", Map.of(), true).teamSubjects());
    }

    @Test
    @DisplayName("the five-argument constructor equals the six-argument one with no teams")
    void fiveArgumentConstructorDelegatesWithAnEmptyList() {
        assertEquals(new CallerIdentity("tok", "alice", "o", Map.of("c", "v"), true, List.of()),
                new CallerIdentity("tok", "alice", "o", Map.of("c", "v"), true));
    }

    @Test
    @DisplayName("null teams become an empty list, never null")
    void nullBecomesEmpty() {
        assertEquals(List.of(), new CallerIdentity("tok", "alice", "o", Map.of(), false, null).teamSubjects());
    }

    @Test
    @DisplayName("the list is copied: changing the caller's list afterwards changes nothing")
    void defensiveCopy() {
        var teams = new ArrayList<>(List.of(SALES));
        var identity = new CallerIdentity("tok", "alice", "o", Map.of(), false, teams);

        teams.add(LEGAL);

        assertEquals(List.of(SALES), identity.teamSubjects(), "a retained list must not add a team after capture");
        assertThrows(UnsupportedOperationException.class, () -> identity.teamSubjects().add(LEGAL));
    }

    @Test
    @DisplayName("order is kept; null, blank and duplicate entries are dropped")
    void orderKeptJunkDropped() {
        var identity = new CallerIdentity("tok", "alice", "o", Map.of(), false, Arrays.asList(SALES, null, " ", LEGAL, SALES));
        assertEquals(List.of(SALES, LEGAL), identity.teamSubjects());
    }

    @Test
    @DisplayName("withoutTeamSubjects() drops the teams and keeps everything else")
    void withoutTeamSubjects() {
        var identity = new CallerIdentity("tok", "alice", "o", Map.of("c", "v"), true, List.of(SALES));
        var stripped = identity.withoutTeamSubjects();

        assertEquals(List.of(), stripped.teamSubjects());
        assertEquals(new CallerIdentity("tok", "alice", "o", Map.of("c", "v"), true), stripped);

        var teamless = new CallerIdentity("tok", "alice", "o");
        assertSame(teamless, teamless.withoutTeamSubjects(), "nothing to strip, nothing to copy");
    }

    @Test
    @DisplayName("toString names no team, no token and no credential")
    void toStringLeaksNothing() {
        var identity = new CallerIdentity("secret-token", "alice", "https://eddi.example:443", Map.of("acme", "secret-credential"), false,
                List.of(SALES, LEGAL));

        String text = identity.toString();

        assertFalse(text.contains("acme"), text);
        assertFalse(text.contains("sales"), text);
        assertFalse(text.contains("secret-token"), text);
        assertFalse(text.contains("secret-credential"), text);
        assertTrue(text.contains("teamSubjects=2"), text);
        assertTrue(text.contains("userId=alice"), text);
    }
}
