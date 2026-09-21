/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConversationSchemaMigrations} (Wave 0, F6). Mirrors
 * {@code GroupConversationSchemaMigrationsTest} for the single-conversation
 * surface — see its class Javadoc for why fixtures use artificial version
 * numbers.
 *
 * @author tests
 */
class ConversationSchemaMigrationsTest {

    private ConversationMemorySnapshot snapshot(int schemaVersion) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId("conv-1");
        snapshot.setSchemaVersion(schemaVersion);
        return snapshot;
    }

    /**
     * Pins the legacy sentinel under deserialization. Today {@code LEGACY} and
     * {@code CURRENT} are both {@code 1}, so this cannot fail for the group side's
     * reason yet — it exists so the first bump of {@code CURRENT} to {@code 2}
     * fails THIS test if someone re-couples the field initialiser to
     * {@code CURRENT}, instead of silently re-creating the zero-iteration migration
     * bug the group side shipped.
     */
    @Test
    void documentWithoutVersionKey_claimsLegacyVersion() throws Exception {
        var legacy = new ObjectMapper().readValue("{}", ConversationMemorySnapshot.class);

        assertEquals(ConversationMemorySnapshot.LEGACY_SCHEMA_VERSION, legacy.getSchemaVersion());
        assertEquals(1, ConversationMemorySnapshot.LEGACY_SCHEMA_VERSION,
                "the legacy floor is the first version that ever existed — it never moves, even when CURRENT bumps");
    }

    @Test
    void prepareForResume_currentVersion_noOp() {
        var snapshot = snapshot(ConversationMemorySnapshot.CURRENT_SCHEMA_VERSION);

        var result = ConversationSchemaMigrations.prepareForResume(snapshot);

        assertEquals(ConversationMemorySnapshot.CURRENT_SCHEMA_VERSION, result.getSchemaVersion());
    }

    @Test
    void prepareForResume_olderVersion_migratesForwardToCurrent() {
        var snapshot = snapshot(ConversationMemorySnapshot.CURRENT_SCHEMA_VERSION - 1);

        var result = ConversationSchemaMigrations.prepareForResume(snapshot);

        assertEquals(ConversationMemorySnapshot.CURRENT_SCHEMA_VERSION, result.getSchemaVersion(),
                "an older document must resume successfully, migrated forward to the current version");
    }

    @Test
    void prepareForResume_newerVersion_throwsWithActionableMessage() {
        var snapshot = snapshot(ConversationMemorySnapshot.CURRENT_SCHEMA_VERSION + 1);

        var ex = assertThrows(IllegalStateException.class, () -> ConversationSchemaMigrations.prepareForResume(snapshot));

        assertTrue(ex.getMessage().contains("newer version"), () -> "unexpected message: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("conv-1"), "message should name the conversation for operator triage");
    }

    @Test
    void prepareForResume_newerVersion_doesNotMutateTheDocument() {
        var snapshot = snapshot(ConversationMemorySnapshot.CURRENT_SCHEMA_VERSION + 1);

        assertThrows(IllegalStateException.class, () -> ConversationSchemaMigrations.prepareForResume(snapshot));

        assertEquals(ConversationMemorySnapshot.CURRENT_SCHEMA_VERSION + 1, snapshot.getSchemaVersion(),
                "a refused document must be left exactly as it was — no partial migration applied");
    }
}
