/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
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
