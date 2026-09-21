/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * I8 — the PostgreSQL twin of {@code MongoUserMemoryStoreVisibilityTest}: the
 * recall SQL keeps the user's own scope untouched and adds the team-owned
 * branch only when groups are supplied. The two backends must not drift.
 *
 * @author tests
 */
class PostgresUserMemoryStoreVisibilityTest {

    @Test
    @DisplayName("no groups: no team branch, balanced parentheses")
    void noGroups_userScopeOnly() {
        String sql = PostgresUserMemoryStore.buildVisibilityQuery(List.of());

        assertFalse(sql.contains("user_id IN"), sql);
        assertEquals(0, sql.chars().map(c -> c == '(' ? 1 : c == ')' ? -1 : 0).sum(), "unbalanced parens: " + sql);
    }

    @Test
    @DisplayName("with groups: the team branch binds one owner and one overlap placeholder per group")
    void withGroups_additiveTeamBranch() {
        String sql = PostgresUserMemoryStore.buildVisibilityQuery(List.of("g1", "g2"));

        assertTrue(sql.contains("user_id IN (?,?)"), sql);
        // user scope overlap + team scope overlap = two ??| ARRAY[?,?] blocks.
        assertEquals(2, sql.split("\\?\\?\\|", -1).length - 1, sql);
        assertEquals(0, sql.chars().map(c -> c == '(' ? 1 : c == ')' ? -1 : 0).sum(), "unbalanced parens: " + sql);
        // Total ordinary binds: userId + agentId + 2 gids (user scope) + 2 owners
        // + 2 gids (team scope) = 8. Each ??| escape contributes two literal '?'
        // characters that are NOT bind placeholders — subtract them.
        assertEquals(8, sql.chars().filter(c -> c == '?').count() - 2L * (sql.split("\\?\\?\\|", -1).length - 1),
                "bind placeholder count must match collectInto's bind order: " + sql);
    }
}
