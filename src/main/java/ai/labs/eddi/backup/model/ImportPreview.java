/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.model;

import java.util.List;

/**
 * Preview of what an import/sync operation would do. Supports three modes:
 * <ul>
 * <li><b>create</b> — all resources shown as CREATE (no target agent)</li>
 * <li><b>merge</b> — resources matched by originId, shown as
 * CREATE/UPDATE/SKIP</li>
 * <li><b>upgrade</b> — resources matched by structural position against a
 * target agent, with full content diffs for the UI</li>
 * </ul>
 * <p>
 * Returned by POST /backup/import/preview and POST /backup/import/sync/preview.
 *
 * @since 6.0.0
 */
public record ImportPreview(
        String sourceAgentId,
        String sourceAgentName,
        String targetAgentId,
        String targetAgentName,
        List<ResourceDiff> resources) {
    /**
     * Diff for a single resource in the import/sync preview.
     *
     * @param sourceId
     *            resource ID from the source (ZIP or remote instance)
     * @param resourceType
     *            type identifier: "agent", "workflow", "langchain", "httpcalls",
     *            "behavior", "regulardictionary", "property", "output", "mcpcalls",
     *            "rag", "snippet"
     * @param name
     *            human-readable name from DocumentDescriptor or snippet.name
     * @param action
     *            what will happen: CREATE, UPDATE, SKIP, or CONFLICT
     * @param targetId
     *            matched target resource ID (null if CREATE)
     * @param targetVersion
     *            matched target resource version (null if CREATE)
     * @param matchStrategy
     *            how the match was determined: "position", "type", "name",
     *            "originId", or null if CREATE
     * @param sourceContent
     *            JSON content from the source (populated for upgrade/sync previews)
     * @param targetContent
     *            JSON content from the target (null if CREATE or if content diffs
     *            are not requested)
     * @param workflowIndex
     *            position in the agent's workflow list (0-based); -1 for
     *            non-workflow resources (extensions, snippets)
     */
    public record ResourceDiff(
            String sourceId,
            String resourceType,
            String name,
            DiffAction action,
            String targetId,
            Integer targetVersion,
            String matchStrategy,
            String sourceContent,
            String targetContent,
            int workflowIndex) {
    }

    public enum DiffAction {
        /** Resource will be created (no match found in target). */
        CREATE,
        /** Resource will be updated (matched, content differs). */
        UPDATE,
        /** Resource will be skipped (matched, content identical). */
        SKIP,
        /** Match is ambiguous — user must resolve manually. */
        CONFLICT
    }
}
