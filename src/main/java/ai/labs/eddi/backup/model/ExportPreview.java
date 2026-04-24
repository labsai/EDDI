/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.model;

import java.util.List;

/**
 * Preview of an agent's resource tree for selective export. Returned by POST
 * /backup/export/{agentId}/preview.
 * <p>
 * The UI displays this as a tree with checkboxes — the agent and workflow
 * skeletons are always included (non-deselectable), while extensions and
 * snippets can be individually selected/deselected.
 *
 * @since 6.0.0
 */
public record ExportPreview(
        String agentId,
        String agentName,
        int agentVersion,
        List<ExportableResource> resources) {
    /**
     * A single resource in the agent's tree.
     *
     * @param resourceId
     *            the resource's ID
     * @param resourceVersion
     *            the resource's current version
     * @param resourceType
     *            type: "agent", "workflow", "langchain", "httpcalls", etc.
     * @param name
     *            human-readable name from DocumentDescriptor
     * @param parentWorkflowId
     *            for extensions: the workflow they belong to. Null for agent-level,
     *            workflow-level, and snippet resources.
     * @param workflowIndex
     *            position in the agent's workflow list (0-based); -1 for the agent
     *            itself and for snippets
     * @param required
     *            if true, this resource cannot be deselected (agent + workflow
     *            skeletons)
     */
    public record ExportableResource(
            String resourceId,
            Integer resourceVersion,
            String resourceType,
            String name,
            String parentWorkflowId,
            int workflowIndex,
            boolean required) {
    }
}
