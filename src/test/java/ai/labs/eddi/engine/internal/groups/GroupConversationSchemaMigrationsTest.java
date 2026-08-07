/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GroupConversationSchemaMigrations} (Wave 0, F6). Per the
 * plan's own testing instruction, fixture documents are constructed at
 * artificial version numbers — there is no real "older version" today, since
 * {@link GroupConversation#CURRENT_SCHEMA_VERSION} ({@code 1}) is the first
 * version that has ever existed.
 *
 * @author tests
 */
class GroupConversationSchemaMigrationsTest {

    private GroupConversation gc(int schemaVersion) {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setSchemaVersion(schemaVersion);
        return gc;
    }

    @Test
    void prepareForResume_currentVersion_noOpReturnsSameVersion() throws Exception {
        var gc = gc(GroupConversation.CURRENT_SCHEMA_VERSION);

        var result = GroupConversationSchemaMigrations.prepareForResume(gc);

        assertEquals(GroupConversation.CURRENT_SCHEMA_VERSION, result.getSchemaVersion());
    }

    @Test
    void prepareForResume_olderVersion_migratesForwardToCurrent() throws Exception {
        // A fixture "old" document — one artificial version below current. With no
        // registered migration for that hop, the identity fallback still bumps the
        // version, per the plan's "most bumps ship an identity migration since new
        // fields default via Jackson" design.
        var gc = gc(GroupConversation.CURRENT_SCHEMA_VERSION - 1);

        var result = GroupConversationSchemaMigrations.prepareForResume(gc);

        assertEquals(GroupConversation.CURRENT_SCHEMA_VERSION, result.getSchemaVersion(),
                "an older document must resume successfully, migrated forward to the current version");
    }

    @Test
    void prepareForResume_muchOlderVersion_walksEveryHopToCurrent() throws Exception {
        var gc = gc(GroupConversation.CURRENT_SCHEMA_VERSION - 3);

        var result = GroupConversationSchemaMigrations.prepareForResume(gc);

        assertEquals(GroupConversation.CURRENT_SCHEMA_VERSION, result.getSchemaVersion());
    }

    @Test
    void prepareForResume_newerVersion_refusesWithActionableMessage() {
        var gc = gc(GroupConversation.CURRENT_SCHEMA_VERSION + 1);

        var ex = assertThrows(GroupDiscussionException.class, () -> GroupConversationSchemaMigrations.prepareForResume(gc));

        assertTrue(ex.getMessage().contains("newer version"), () -> "unexpected message: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("gc-1"), "message should name the document for operator triage");
    }

    @Test
    void prepareForResume_newerVersion_doesNotMutateTheDocument() {
        var gc = gc(GroupConversation.CURRENT_SCHEMA_VERSION + 1);

        assertThrows(GroupDiscussionException.class, () -> GroupConversationSchemaMigrations.prepareForResume(gc));

        assertEquals(GroupConversation.CURRENT_SCHEMA_VERSION + 1, gc.getSchemaVersion(),
                "a refused document must be left exactly as it was — no partial migration applied");
    }
}
