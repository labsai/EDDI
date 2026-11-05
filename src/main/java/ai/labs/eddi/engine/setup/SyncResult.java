/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import java.util.List;
import java.util.Map;

/**
 * Result of {@code POST /administration/agents/{agentId}/setup-api/sync}.
 *
 * @param agentId
 *            the agent
 * @param previousVersion
 *            the agent version the sync started from
 * @param newVersion
 *            the agent version it wrote; equal to {@code previousVersion} when
 *            nothing changed or on a dry run
 * @param dryRun
 *            whether nothing was written
 * @param addedTools
 *            tools the new spec adds
 * @param changedTools
 *            tools whose generated call differs
 * @param removedTools
 *            tools the new spec no longer has
 * @param gateRulesAdded
 *            {@code requireApproval} patterns added to the agent's gate
 * @param unusableEndpoints
 *            operations skipped because a generated tool cannot send them
 * @param unmatchedEndpoints
 *            filter entries that matched nothing
 * @param warnings
 *            what sync did not do and why (for example a system prompt whose
 *            API summary it could not find)
 * @param deployment
 *            the deploy outcome, null when not deployed
 */
public record SyncResult(String agentId, int previousVersion, int newVersion, boolean dryRun, List<String> addedTools, List<String> changedTools,
        List<String> removedTools, List<String> gateRulesAdded, List<String> unusableEndpoints, List<String> unmatchedEndpoints,
        List<String> warnings, Map<String, Object> deployment) {
}
