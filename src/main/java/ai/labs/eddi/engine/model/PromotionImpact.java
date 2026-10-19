/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import java.util.List;

/**
 * What writing the next version of an existing agent — an import or a sync onto
 * it — would mean for the conversations running on it, before the operator
 * commits. The cousin of {@link DeploymentImpact}, which answers the same
 * question for a version that already exists; here the version does not exist
 * yet, so the answer is derived from the agent's current version.
 * <p>
 * It carries the answer for <em>both</em> declarations, because whether the
 * write is compatible is the operator's choice and the preview is read before
 * making it: a breaking version pins every running conversation, a compatible
 * one lets those on the same generation follow. Counts only — open
 * conversations are counted per deployed version, never loaded.
 *
 * @param currentVersion
 *            the agent version the new one would replace
 * @param currentGeneration
 *            that version's compatibility generation, {@code null} when it was
 *            stored before generations existed
 * @param generationIfCompatible
 *            the generation the new version would carry if declared compatible
 * @param generationIfBreaking
 *            the generation it would carry if not
 * @param startsNewChain
 *            true when {@code currentGeneration} is {@code null}: there is no
 *            chain to continue, so even a compatible save starts generation 1
 *            and the conversations already running stay on their version
 * @param activeConversations
 *            open conversations across every version of the agent (paused ones
 *            excluded, as for undeploy)
 * @param followingIfCompatible
 *            of those, the ones that would switch to the new version on their
 *            next turn once it is deployed if it is declared compatible. If it
 *            is not, none follow: all {@code activeConversations} stay
 * @param generations
 *            the open conversations grouped by their own compatibility
 *            generation, newest first, conversations without one last. Grouped
 *            by generation rather than by deployed version because a
 *            conversation follows the newest ready version of its own
 *            generation, whatever version it last ran on: many idle ones are
 *            stored on versions that are no longer deployed
 * @since 6.5.0
 */
public record PromotionImpact(Integer currentVersion, Integer currentGeneration, int generationIfCompatible, int generationIfBreaking,
        boolean startsNewChain, long activeConversations, long followingIfCompatible, List<GenerationCount> generations) {

    public PromotionImpact {
        generations = generations == null ? List.of() : List.copyOf(generations);
    }

    /**
     * @param generation
     *            a compatibility generation, {@code null} for conversations that
     *            predate generations
     * @param activeConversations
     *            the open conversations of that generation
     * @param followsIfCompatible
     *            whether they would follow the new version if it is declared
     *            compatible; they never do if it is not
     */
    public record GenerationCount(Integer generation, long activeConversations, boolean followsIfCompatible) {
    }
}
