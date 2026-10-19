/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import java.util.List;
import java.util.Map;

/**
 * What a compatibility-generation upgrade did to an agent's conversations — or,
 * for a dry run, what it would do. The same shape for both, so the preview an
 * operator reads is the run they then confirm.
 *
 * @param agentId
 *            the agent
 * @param environment
 *            the environment whose deployed versions were checked
 * @param dryRun
 *            {@code true}: nothing was written
 * @param currentVersion
 *            the agent's latest version; its generation is the target
 * @param targetGeneration
 *            the generation conversations are moved onto
 * @param servingVersion
 *            the highest version of the target generation that is {@code READY}
 *            on the node that handled the request — the version moved
 *            conversations run on from their next turn
 * @param truncated
 *            {@code true} when the run stopped at its {@code limit} with more
 *            candidates left: call again with {@code after=<nextAfter>}
 * @param nextAfter
 *            the cursor to continue from when {@code truncated}, else
 *            {@code null}. Needed because conversations that are skipped
 *            without being changed (paused, in progress, busy) stay candidates,
 *            so a call that restarted from the beginning would meet them again
 * @param versions
 *            per source version (ascending; a version asked for with
 *            {@code fromVersion} appears even with nothing to move)
 * @param totals
 *            the same counts over all source versions
 * @since 6.6.0
 */
public record ConversationUpgradeReport(String agentId, String environment, boolean dryRun, int currentVersion, int targetGeneration,
        int servingVersion, boolean truncated, String nextAfter, List<VersionCounts> versions, VersionCounts totals) {

    /**
     * @param fromVersion
     *            the agent version the conversations are stored on ({@code null} in
     *            {@code totals})
     * @param matched
     *            conversations there with no generation or a lower one, whatever
     *            their state
     * @param eligible
     *            of those, the ones in a state a turn can run from — what a run
     *            attempts, and for a dry run what it <em>would</em> upgrade
     * @param upgraded
     *            conversations actually moved; always {@code 0} for a dry run
     * @param skipped
     *            conversations not moved, by reason (only reasons that occurred):
     *            {@code ended}, {@code awaitingHuman}, {@code inProgress},
     *            {@code unknownState}, and — real runs only — {@code busy} (its
     *            turn queue did not free in time), {@code changedConcurrently},
     *            {@code alreadyCurrent}, {@code notFound}, {@code failed}
     */
    public record VersionCounts(Integer fromVersion, long matched, long eligible, long upgraded, Map<String, Long> skipped) {
    }
}
