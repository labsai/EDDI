/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal.handoff;

import ai.labs.eddi.configs.groups.model.SharedTaskList.Bet;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.SourceKind;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.Trigger;

import java.util.List;

/**
 * One request to file a task into another team's workspace.
 * <p>
 * <b>The source identity is the engine's, never the model's.</b>
 * {@code sourceKind}/{@code sourceId} are derived from the conversation's own
 * agent id or from the membership-checked live discussion's group id; no tool
 * argument or client field ever reaches them. {@code targetGroupId} is resolved
 * server-side from an alias the source's own config declared.
 *
 * @param sourceKind
 *            standalone agent or group
 * @param sourceId
 *            that agent's or group's config id
 * @param sourceVersion
 *            the agent's version when known, else {@code null}
 * @param sourceConversationId
 *            the conversation or group conversation that is filing
 * @param sourceMemberAgentId
 *            the member agent that called the tool, inside a group
 * @param userId
 *            the acting user, for the audit entry only
 * @param targetGroupId
 *            the receiving team's group config id
 * @param subject
 *            task subject (untrusted text)
 * @param description
 *            task description (untrusted text)
 * @param priority
 *            0..10; {@code null} lets the service fall back to the default
 * @param trigger
 *            tool call or decision rule
 * @param decisionRef
 *            {@code "VOTE@<phase>"} for a decision rule, else {@code null}
 * @param artifactId
 *            resolved shared-artifact id, when the filer referenced one
 * @param reference
 *            free-text reference the filer gave
 * @param hop
 *            how many team boundaries the source's own work has already crossed
 *            (0 for fresh work)
 * @param lineage
 *            source keys the source's own work passed through
 * @param dedupeKey
 *            explicit idempotency key (decision rules); {@code null} derives
 *            one from source, target and subject
 * @param maxDescriptionChars
 *            the source policy's description cap; non-positive uses the hard
 *            cap
 * @param runQuota
 *            the per-run cap of the source (conversation or discussion), or
 *            {@code null} when the trigger has none
 * @param bet
 *            optional bet the filed task becomes (hypothesis, metric, review
 *            date); {@code null} files an ordinary task
 */
public record HandoffRequest(SourceKind sourceKind, String sourceId, Integer sourceVersion, String sourceConversationId,
        String sourceMemberAgentId, String userId, String targetGroupId, String subject, String description, Integer priority, Trigger trigger,
        String decisionRef, String artifactId, String reference, int hop, List<String> lineage, String dedupeKey, int maxDescriptionChars,
        RunQuota runQuota, Bet bet) {

    public HandoffRequest {
        lineage = lineage == null ? List.of() : List.copyOf(lineage);
    }

    /** The form that predates {@code bet}: an ordinary task. */
    public HandoffRequest(SourceKind sourceKind, String sourceId, Integer sourceVersion, String sourceConversationId,
            String sourceMemberAgentId, String userId, String targetGroupId, String subject, String description, Integer priority,
            Trigger trigger, String decisionRef, String artifactId, String reference, int hop, List<String> lineage, String dedupeKey,
            int maxDescriptionChars, RunQuota runQuota) {
        this(sourceKind, sourceId, sourceVersion, sourceConversationId, sourceMemberAgentId, userId, targetGroupId, subject, description,
                priority, trigger, decisionRef, artifactId, reference, hop, lineage, dedupeKey, maxDescriptionChars, runQuota, null);
    }

    /** The {@code accept.from[].source} spelling of the source. */
    public String sourceKey() {
        return sourceKind == null ? null : sourceKind.key(sourceId);
    }

    /**
     * A per-run cap, atomic under contention. The service reserves one slot before
     * it does anything that can succeed and gives it back on any outcome that did
     * not file.
     */
    public interface RunQuota {

        /** Takes one slot; {@code false} when the cap is reached. */
        boolean tryReserve();

        /** Gives back a slot taken by {@link #tryReserve()}. */
        void release();
    }
}
