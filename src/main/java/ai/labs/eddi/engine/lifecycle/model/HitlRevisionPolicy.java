/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.model;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.HitlRejectionAction;

/**
 * The agent's {@code hitlConfig.onRejection} policy, carried onto the live
 * memory for one turn (never persisted, re-read from the pinned agent version
 * each turn like the tool-approval config). {@code null} on memory means the
 * default, {@link HitlRejectionAction#SKIP}.
 *
 * @param onRejection
 *            what a rejected rule pause does
 * @param maxRevisionsPerTurn
 *            how many revisions one turn may take
 * @since 6.6.0
 */
public record HitlRevisionPolicy(HitlRejectionAction onRejection, int maxRevisionsPerTurn) {

    /** Whether a rejection with a note may revise the answer. */
    public boolean revise() {
        return onRejection == HitlRejectionAction.REVISE && maxRevisionsPerTurn > 0;
    }

    /**
     * The policy of an agent's HITL config, or {@code null} when it is the default
     * (SKIP) — so nothing is captured for an agent that did not opt in.
     */
    public static HitlRevisionPolicy of(AgentConfiguration.HitlConfig hitlConfig) {
        if (hitlConfig == null || hitlConfig.getOnRejection() != HitlRejectionAction.REVISE) {
            return null;
        }
        return new HitlRevisionPolicy(HitlRejectionAction.REVISE, hitlConfig.effectiveMaxRevisionsPerTurn());
    }
}
