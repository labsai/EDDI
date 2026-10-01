/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.directory;

import ai.labs.eddi.engine.security.spaces.Subjects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every {@link IUserDirectoryStore} must do, run against a real MongoDB
 * and a real PostgreSQL by the two subclasses. The rules that matter most —
 * only verified emails resolve, typed input is never a pattern — are exactly
 * the ones a mocked store cannot show.
 */
interface UserDirectoryStoreContract {

    /** A clean store per test. */
    IUserDirectoryStore store();

    private static DirectoryUser user(String principal, String username, String email, boolean verified, String name, List<String> teams,
                                      long lastSeen) {
        return new DirectoryUser(principal, "sub-" + principal, username, email, verified, name, teams, Instant.ofEpochSecond(1000),
                Instant.ofEpochSecond(lastSeen));
    }

    @Test
    @DisplayName("an upsert keeps the first-seen time and replaces everything else")
    default void upsertKeepsFirstSeen() {
        store().upsert(user("alice", "alice", "old@example.com", false, "Alice", List.of(), 2000));
        store().upsert(new DirectoryUser("alice", "sub-alice", "alice", "new@example.com", true, "Alice A", List.of(), Instant.ofEpochSecond(9999),
                Instant.ofEpochSecond(3000)));

        DirectoryUser stored = store().find("alice").orElseThrow();
        assertEquals("new@example.com", stored.email());
        assertTrue(stored.emailVerified());
        assertEquals("Alice A", stored.displayName());
        assertEquals(Instant.ofEpochSecond(1000), stored.firstSeen());
        assertEquals(Instant.ofEpochSecond(3000), stored.lastSeen());
    }

    @Test
    @DisplayName("email lookups are case-insensitive and verified-only")
    default void verifiedEmailOnly() {
        store().upsert(user("carol", "carol", "Carol@Example.com", true, "Carol", List.of(), 2000));
        store().upsert(user("mallory", "mallory", "ceo@example.com", false, "Mallory", List.of(), 2000));

        assertEquals(List.of("carol"), store().findByVerifiedEmail("carol@example.COM").stream().map(DirectoryUser::principal).toList());
        assertTrue(store().findByVerifiedEmail("ceo@example.com").isEmpty(), "an unverified address must never resolve to its claimant");
    }

    @Test
    @DisplayName("username lookups are case-insensitive; principal lookups are exact")
    default void usernameAndPrincipal() {
        store().upsert(user("f81d4fae", "Carol.T", null, false, "Carol", List.of(), 2000));

        assertEquals(1, store().findByUsername("carol.t").size());
        assertTrue(store().find("F81D4FAE").isEmpty(), "ownership compares principals exactly, so lookups must too");
        assertTrue(store().find("f81d4fae").isPresent());
    }

    @Test
    @DisplayName("search is a literal, case-insensitive prefix match on any name field")
    default void searchIsLiteralPrefix() {
        store().upsert(user("alice", "alice", "alice@example.com", true, "Alice Doe", List.of(), 2000));
        store().upsert(user("axb", "axb", "axb@example.com", true, "Axb", List.of(), 2000));
        store().upsert(user("a%b", "a%b", "pct@example.com", true, "Percent", List.of(), 2000));

        assertEquals(Set.of("alice"), principals(store().search("ALI", 10)));
        assertEquals(Set.of("alice"), principals(store().search("alice doe", 10)));
        assertEquals(Set.of(), principals(store().search("a.b", 10)), "a dot typed into the box is a dot, not 'any character'");
        assertEquals(Set.of("a%b"), principals(store().search("a%", 10)), "a percent sign typed into the box is a percent sign");
        assertEquals(Set.of(), principals(store().search("(", 10)), "an unbalanced bracket must not be a query error");
    }

    @Test
    @DisplayName("search returns the most recently seen first, and honours the limit")
    default void searchOrderAndLimit() {
        store().upsert(user("sam-old", "sam-old", null, false, "Sam", List.of(), 1000));
        store().upsert(user("sam-new", "sam-new", null, false, "Sam", List.of(), 5000));
        store().upsert(user("sam-mid", "sam-mid", null, false, "Sam", List.of(), 3000));

        assertEquals(List.of("sam-new", "sam-mid"), store().search("sam", 2).stream().map(DirectoryUser::principal).toList());
    }

    @Test
    @DisplayName("teams are searchable by prefix, and membership is queryable")
    default void teams() {
        String engineering = Subjects.team("engineering");
        String exec = Subjects.team("exec");
        store().upsert(user("alice", "alice", null, false, "Alice", List.of(engineering), 2000));
        store().upsert(user("bob", "bob", null, false, "Bob", List.of(engineering, exec), 3000));

        assertEquals(Set.of(engineering, exec), Set.copyOf(store().searchTeams("e", 10)));
        assertEquals(List.of(engineering), store().searchTeams("eng", 10));
        assertTrue(store().teamExists(exec));
        assertFalse(store().teamExists(Subjects.team("finance")));
        assertEquals(List.of("bob", "alice"), store().teamMembers(engineering, 10));
    }

    @Test
    @DisplayName("findAll returns exactly the known principals, and delete removes one")
    default void findAllAndDelete() {
        store().upsert(user("alice", "alice", null, false, "Alice", List.of(), 2000));
        store().upsert(user("bob", "bob", null, false, "Bob", List.of(), 2000));

        assertEquals(Set.of("alice", "bob"), store().findAll(List.of("alice", "bob", "ghost")).keySet());
        assertTrue(store().delete("alice"));
        assertFalse(store().delete("alice"));
        assertTrue(store().find("alice").isEmpty());
    }

    private static Set<String> principals(List<DirectoryUser> users) {
        return Set.copyOf(users.stream().map(DirectoryUser::principal).toList());
    }
}
