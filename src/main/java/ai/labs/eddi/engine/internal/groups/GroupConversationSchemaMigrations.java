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
     * Empty today — {@link GroupConversation#CURRENT_SCHEMA_VERSION} is {@code 1},
     * the first version that has ever existed, so there is nothing yet to migrate
     * from. Every future Wave item that adds a resume-relevant field bumps
     * {@code CURRENT_SCHEMA_VERSION} and registers its own entry here. A version
     * hop with no registered entry defaults to identity (see
     * {@link #prepareForResume}) — the documented, common case for a bump whose new
     * fields default correctly via Jackson without any transformation.
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
