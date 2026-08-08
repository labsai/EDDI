/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;

import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Prepares a just-loaded {@link GroupConversation} for resume by checking its
 * {@code schemaVersion} against
 * {@link GroupConversation#CURRENT_SCHEMA_VERSION} (Wave 0, F6). A paused
 * discussion (`AWAITING_APPROVAL`) can sit in storage for days — long enough
 * for a deploy to land in between, changing the shape resume-time logic depends
 * on.
 *
 * @author ginccc
 */
public final class GroupConversationSchemaMigrations {

    private GroupConversationSchemaMigrations() {
    }

    /**
     * Migration functions, keyed by the version they upgrade <em>from</em> (so
     * entry {@code N} takes a version-{@code N} document to version {@code N+1}).
     * Empty today: every bump so far ({@code 1→2→3}) was additive — the new fields
     * default correctly via Jackson, so those hops ride the identity fallback below
     * rather than a registered entry. That fallback path has therefore already been
     * exercised; do not read the empty map as "no version has ever changed". Every
     * future Wave item that adds a resume-relevant field bumps
     * {@link GroupConversation#CURRENT_SCHEMA_VERSION} and registers an entry here
     * only when the hop actually needs a transformation. Documents stored without a
     * {@code schemaVersion} key deserialize claiming
     * {@link GroupConversation#LEGACY_SCHEMA_VERSION} and enter the ladder at the
     * bottom.
     */
    private static final Map<Integer, UnaryOperator<GroupConversation>> MIGRATIONS = Map.of();

    /**
     * @throws GroupDiscussionException
     *             if {@code gc}'s {@code schemaVersion} is newer than
     *             {@link GroupConversation#CURRENT_SCHEMA_VERSION} — this
     *             deployment predates the document and must not guess at a shape it
     *             does not understand
     */
    public static GroupConversation prepareForResume(GroupConversation gc) throws GroupDiscussionException {
        if (gc.getSchemaVersion() > GroupConversation.CURRENT_SCHEMA_VERSION) {
            throw new GroupDiscussionException(
                    "Cannot resume group conversation %s: document written by a newer version (schema %d, this deployment understands up to %d)"
                            .formatted(gc.getId(), gc.getSchemaVersion(), GroupConversation.CURRENT_SCHEMA_VERSION));
        }
        for (int v = gc.getSchemaVersion(); v < GroupConversation.CURRENT_SCHEMA_VERSION; v++) {
            gc = MIGRATIONS.getOrDefault(v, UnaryOperator.identity()).apply(gc);
        }
        gc.setSchemaVersion(GroupConversation.CURRENT_SCHEMA_VERSION);
        return gc;
    }
}
