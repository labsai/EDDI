/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import java.util.List;

/**
 * What deploying {@code version} of an agent means for the conversations
 * already running on its other deployed versions — the preview a deploy dialog
 * shows before the operator commits.
 *
 * @param agentId
 *            the agent
 * @param version
 *            the version about to be (or just) deployed
 * @param compatibilityGeneration
 *            that version's compatibility generation, {@code null} for a
 *            version stored before generations existed
 * @param deployedVersions
 *            every other version of the agent deployed in the environment,
 *            highest first
 * @since 6.5.0
 */
public record DeploymentImpact(String agentId, Integer version, Integer compatibilityGeneration, List<VersionImpact> deployedVersions) {

    /**
     * @param version
     *            a version of the agent that is deployed now
     * @param compatibilityGeneration
     *            its generation, {@code null} when it has none
     * @param activeConversations
     *            its open conversations (paused ones excluded, as for undeploy)
     * @param outcome
     *            what those conversations do once {@code version} is deployed
     */
    public record VersionImpact(Integer version, Integer compatibilityGeneration, long activeConversations, Outcome outcome) {
    }

    public enum Outcome {
        /**
         * Same generation and older: the conversations move to the new version on their
         * next turn.
         */
        FOLLOW,
        /**
         * Another generation (a breaking change), no generation at all, or a newer
         * version: the conversations stay where they are. They keep running as long as
         * their version stays deployed; undeploying it with
         * {@code endAllActiveConversations} ends them.
         */
        STAY
    }
}
