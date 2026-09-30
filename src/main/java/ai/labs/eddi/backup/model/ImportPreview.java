/*
 * Copyright EDDI contributors
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
 * @param sourceAgentId
 *            the agent's id in the source system
 * @param sourceAgentName
 *            the agent's human-readable name in the source system
 * @param targetAgentId
 *            the local agent being matched against, or null in create mode
 * @param targetAgentName
 *            the local agent's name, or null in create mode
 * @param resources
 *            one diff per resource
 * @param error
 *            why this preview could not be produced; null on success. Only a
 *            batch preview fills it — a single preview reports failure with an
 *            HTTP status. It exists so a failed row in a batch is a real field
 *            rather than an {@code "Error: ..."} prefix smuggled into the name
 * @param warnings
 *            what the operator should know before approving, that is not a
 *            failure — e.g. two source snippets sharing a name, of which only
 *            one can travel. Never null
 * @since 6.0.0
 */
public record ImportPreview(
        String sourceAgentId,
        String sourceAgentName,
        String targetAgentId,
        String targetAgentName,
        List<ResourceDiff> resources,
        String error,
        List<String> warnings) {

    public ImportPreview {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** A preview that was produced successfully — {@code error} is null. */
    public ImportPreview(String sourceAgentId,
            String sourceAgentName,
            String targetAgentId,
            String targetAgentName,
            List<ResourceDiff> resources) {
        this(sourceAgentId, sourceAgentName, targetAgentId, targetAgentName, resources, null, List.of());
    }

    /** A preview with an error and no warnings. */
    public ImportPreview(String sourceAgentId,
            String sourceAgentName,
            String targetAgentId,
            String targetAgentName,
            List<ResourceDiff> resources,
            String error) {
        this(sourceAgentId, sourceAgentName, targetAgentId, targetAgentName, resources, error, List.of());
    }

    /**
     * Diff for a single resource in the import/sync preview.
     *
     * @param sourceId
     *            resource ID from the source (ZIP or remote instance)
     * @param resourceType
     *            type identifier: "agent", "workflow", "langchain", "httpcalls",
     *            "behavior", "regulardictionary", "property", "output", "mcpcalls",
     *            "rag", "snippet", "schedule"
     * @param name
     *            human-readable name from DocumentDescriptor or snippet.name
     * @param action
     *            what will happen: CREATE, UPDATE, SKIP, CONFLICT or REMOVE. For a
     *            REMOVE row {@code sourceId} is the target's own id — there is no
     *            source resource — and it is what {@code selectedResources} names
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
        /**
         * The target's copy was changed on this instance since the last sync or import
         * wrote it, and the source differs too. Written only when named explicitly in
         * {@code selectedResources}: a sync of everything leaves it alone and reports
         * it, so a hotfix made here is never overwritten by accident.
         */
        CONFLICT,
        /**
         * The target has this and the source no longer does: a workflow step the source
         * removed, or a whole workflow. The step goes when its workflow is adopted; a
         * workflow is taken off the agent. Nothing is deleted from the store — earlier
         * versions still name it.
         */
        REMOVE
    }
}
