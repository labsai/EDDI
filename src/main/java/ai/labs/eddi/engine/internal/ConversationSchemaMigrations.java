/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;

import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Prepares a just-loaded {@link ConversationMemorySnapshot} for a single
 * (non-group) HITL resume by checking its {@code schemaVersion} against
 * {@link ConversationMemorySnapshot#CURRENT_SCHEMA_VERSION} (Wave 0, F6).
 * Mirrors {@code GroupConversationSchemaMigrations} for the group surface — see
 * its Javadoc for the full rationale (a pause can sit in storage for days, long
 * enough for a deploy to land in between).
 * <p>
 * Unlike the group side, this throws an unchecked {@link IllegalStateException}
 * rather than a checked type:
 * {@code ConversationHitlService.resumeConversation} already wraps its whole
 * pre-conversion load in a generic {@code catch (Exception e)} that restores
 * the pause and rethrows as a {@code ResourceStoreException} — reusing that
 * existing, already-hardened rollback path is safer than adding a second one.
 *
 * @author ginccc
 */
public final class ConversationSchemaMigrations {

    private ConversationSchemaMigrations() {
    }

    /**
     * Migration functions, keyed by the version they upgrade <em>from</em>. Empty
     * today for the same reason as the group side's registry: version {@code 1} is
     * the first that has ever existed. See
     * {@code GroupConversationSchemaMigrations#MIGRATIONS}'s Javadoc.
     */
    private static final Map<Integer, UnaryOperator<ConversationMemorySnapshot>> MIGRATIONS = Map.of();

    /**
     * @throws IllegalStateException
     *             if {@code snapshot}'s {@code schemaVersion} is newer than
     *             {@link ConversationMemorySnapshot#CURRENT_SCHEMA_VERSION}
     */
    public static ConversationMemorySnapshot prepareForResume(ConversationMemorySnapshot snapshot) {
        if (snapshot.getSchemaVersion() > ConversationMemorySnapshot.CURRENT_SCHEMA_VERSION) {
            throw new IllegalStateException(
                    "Cannot resume conversation %s: document written by a newer version (schema %d, this deployment understands up to %d)"
                            .formatted(snapshot.getConversationId(), snapshot.getSchemaVersion(),
                                    ConversationMemorySnapshot.CURRENT_SCHEMA_VERSION));
        }
        for (int v = snapshot.getSchemaVersion(); v < ConversationMemorySnapshot.CURRENT_SCHEMA_VERSION; v++) {
            snapshot = MIGRATIONS.getOrDefault(v, UnaryOperator.identity()).apply(snapshot);
        }
        snapshot.setSchemaVersion(ConversationMemorySnapshot.CURRENT_SCHEMA_VERSION);
        return snapshot;
    }
}
