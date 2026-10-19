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
 *            open conversations across every deployed version of the agent
 * @param followingIfCompatible
 *            of those, the ones that would switch to the new version on their
 *            next turn once it is deployed if it is declared compatible. If it
 *            is not, none follow: all {@code activeConversations} stay
 * @param deployedVersions
 *            every version of the agent deployed in any environment, highest
 *            first. Conversations are counted per version, not per environment
 * @since 6.5.0
 */
public record PromotionImpact(Integer currentVersion, Integer currentGeneration, int generationIfCompatible, int generationIfBreaking,
        boolean startsNewChain, long activeConversations, long followingIfCompatible, List<DeployedVersion> deployedVersions) {

    public PromotionImpact {
        deployedVersions = deployedVersions == null ? List.of() : List.copyOf(deployedVersions);
    }

    /**
     * @param version
     *            a version of the agent deployed in some environment
     * @param compatibilityGeneration
     *            its generation, {@code null} when it has none
     * @param activeConversations
     *            its open conversations (paused ones excluded, as for undeploy)
     * @param followsIfCompatible
     *            whether those conversations would follow the new version if it is
     *            declared compatible; they never do if it is not
     */
    public record DeployedVersion(Integer version, Integer compatibilityGeneration, long activeConversations,
            boolean followsIfCompatible) {
    }
}
