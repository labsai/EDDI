/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GroupConversationSchemaMigrations} (Wave 0, F6). Fixture
 * documents are constructed at explicit version numbers; the key-less fixtures
 * go through Jackson because the field-initialiser semantics under
 * deserialization are exactly what is under test — every pre-F6 production
 * document has no {@code schemaVersion} key.
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

    /**
     * The case that is every document in production: written before F6 existed, so
     * the stored JSON has no {@code schemaVersion} key at all. Jackson runs the
     * no-arg constructor and leaves the field initialiser standing — so the
     * initialiser IS the version such a document claims. It must be the legacy
     * floor ({@code 1}), not {@code CURRENT_SCHEMA_VERSION}: a key-less document
     * claiming current makes {@code prepareForResume}'s ladder run zero iterations
     * on exactly the documents it exists for.
     */
    @Test
    void documentWithoutVersionKey_claimsLegacyVersionNotCurrent() throws Exception {
        var legacy = new ObjectMapper().readValue("{}", GroupConversation.class);

        assertEquals(1, legacy.getSchemaVersion(),
                "a stored document with no schemaVersion key predates F6 and must claim the legacy floor, "
                        + "so the migration ladder walks every hop instead of zero");
    }

    @Test
    void documentWithoutVersionKey_isMigratedForwardOnResume() throws Exception {
        var legacy = new ObjectMapper().readValue("{}", GroupConversation.class);

        var result = GroupConversationSchemaMigrations.prepareForResume(legacy);

        assertEquals(GroupConversation.CURRENT_SCHEMA_VERSION, result.getSchemaVersion());
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
