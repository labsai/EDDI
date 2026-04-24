/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;

import java.util.List;
import java.util.Map;

/**
 * Abstraction over how agent configuration data is read for import/sync.
 * Implementations can read from a ZIP archive, a remote EDDI REST API, or the
 * local database — the matching, preview, and upgrade logic operates
 * identically regardless of source.
 *
 * @since 6.0.0
 */
public interface IResourceSource extends AutoCloseable {

    /** Agent config + metadata from the source. */
    AgentSourceData readAgent();

    /** All workflow configs for the agent, in order. */
    List<WorkflowSourceData> readWorkflows();

    /** All snippets referenced by this agent. */
    List<SnippetSourceData> readSnippets();

    /**
     * Cleans up any temporary resources (e.g., unzipped directories). Default no-op
     * — override when cleanup is needed.
     */
    @Override
    default void close() throws Exception {
        // no-op by default
    }

    // ==================== Data Records ====================

    /**
     * Agent-level data from the source.
     *
     * @param sourceId
     *            the agent's resource ID in the source system
     * @param name
     *            human-readable name (from DocumentDescriptor)
     * @param config
     *            the full agent configuration
     */
    record AgentSourceData(
            String sourceId,
            String name,
            AgentConfiguration config) {
    }

    /**
     * Workflow-level data from the source, including all its extensions.
     *
     * @param sourceId
     *            the workflow's resource ID in the source system
     * @param name
     *            human-readable name (from DocumentDescriptor)
     * @param positionIndex
     *            index in the agent's workflow list (0-based)
     * @param config
     *            the full workflow configuration
     * @param extensions
     *            map of step type URI → extension data. Key is the
     *            WorkflowStep.type string (e.g., "ai.labs.llm"). Each type appears
     *            at most once per workflow, making the match deterministic.
     */
    record WorkflowSourceData(
            String sourceId,
            String name,
            int positionIndex,
            WorkflowConfiguration config,
            Map<String, ExtensionSourceData> extensions) {
    }

    /**
     * A single workflow extension (e.g., one LLM config, one httpCalls config).
     *
     * @param sourceId
     *            the extension's resource ID in the source system
     * @param name
     *            human-readable name (from DocumentDescriptor)
     * @param type
     *            the file extension type (e.g., "langchain", "httpcalls")
     * @param stepType
     *            the WorkflowStep.type URI (e.g., "ai.labs.llm")
     * @param contentJson
     *            the full serialized config JSON
     */
    record ExtensionSourceData(
            String sourceId,
            String name,
            String type,
            String stepType,
            String contentJson) {
    }

    /**
     * A prompt snippet from the source.
     *
     * @param sourceId
     *            the snippet's resource ID in the source system
     * @param name
     *            the snippet's logical name (natural key for dedup)
     * @param snippet
     *            the full snippet object
     */
    record SnippetSourceData(
            String sourceId,
            String name,
            PromptSnippet snippet) {
    }
}
