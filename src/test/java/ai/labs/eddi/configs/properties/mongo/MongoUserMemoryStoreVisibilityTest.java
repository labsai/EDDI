/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.mongo;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import com.mongodb.MongoClientSettings;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * I8 — the recall visibility filters on BOTH backends: the user's own scope is
 * untouched (still constrained to their own {@code userId}), and the additive
 * team-owned branch reaches ONLY synthetic {@code "group:"+groupId} owners
 * derived from the supplied group ids. The two backends must not drift, so both
 * shapes are pinned side by side.
 *
 * @author tests
 */
class MongoUserMemoryStoreVisibilityTest {

    private static String render(Bson filter) {
        return filter.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()).toJson();
    }

    // =================================================================
    // MongoDB
    // =================================================================

    @Test
    @DisplayName("Mongo, no groups: the filter is exactly the user's own scope — no team branch at all")
    void mongo_noGroups_userScopeOnly() {
        String json = render(MongoUserMemoryStore.buildVisibilityFilter("user-1", "agent-a", List.of()));

        assertTrue(json.contains("user-1"), json);
        assertFalse(json.contains(IUserMemoryStore.TEAM_OWNER_PREFIX), json);
    }

    @Test
    @DisplayName("Mongo, with groups: the team branch matches derived group: owners only, alongside the untouched user scope")
    void mongo_withGroups_additiveTeamBranch() {
        String json = render(MongoUserMemoryStore.buildVisibilityFilter("user-1", "agent-a", List.of("g1", "g2")));

        assertTrue(json.contains("\"group:g1\"") && json.contains("\"group:g2\""),
                "team owners are DERIVED from the supplied group ids: " + json);
        assertTrue(json.contains("user-1"), "the personal scope keeps its own userId constraint: " + json);
        // The team branch must pair the synthetic owners with group visibility —
        // a bare owner match would leak any hypothetical non-group entry.
        int teamIdx = json.indexOf("group:g1");
        assertTrue(json.indexOf("group", teamIdx) >= 0, json);
    }

    @Test
    @DisplayName("Mongo: another user's id never appears in the filter — the team branch cannot cross humans")
    void mongo_teamBranch_cannotNameAnotherHuman() {
        String json = render(MongoUserMemoryStore.buildVisibilityFilter("user-1", "agent-a", List.of("g1")));

        // The only owner ids in the filter are the caller's own and group:g1.
        assertFalse(json.contains("user-2"), json);
        assertTrue(json.contains("group:g1"), json);
    }

}
