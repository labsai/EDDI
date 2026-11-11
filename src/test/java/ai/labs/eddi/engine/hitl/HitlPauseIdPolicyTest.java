/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HitlPauseIdPolicyTest {

    private static HitlDecision decision(String decidedBy, String pauseId) {
        var decision = new HitlDecision();
        decision.setVerdict(HitlDecision.HitlVerdict.APPROVED);
        decision.setDecidedBy(decidedBy);
        decision.setPauseId(pauseId);
        return decision;
    }

    @Test
    void aPersonsDecisionWithoutPauseIdIsRefusedWhenRequired() {
        assertTrue(HitlPauseIdPolicy.refuses(decision("alice", null), true));
        assertTrue(HitlPauseIdPolicy.refuses(decision("alice", " "), true));
        assertTrue(HitlPauseIdPolicy.refuses(decision(null, null), true), "an unattributed decision is not the engine's");
    }

    @Test
    void namingThePauseOrNotRequiringItPasses() {
        assertFalse(HitlPauseIdPolicy.refuses(decision("alice", "p-1"), true));
        assertFalse(HitlPauseIdPolicy.refuses(decision("alice", null), false));
        assertFalse(HitlPauseIdPolicy.refuses(null, true));
    }

    @Test
    void engineDecisionsAreExempt() {
        assertFalse(HitlPauseIdPolicy.refuses(decision("system:timeout", null), true));
        assertFalse(HitlPauseIdPolicy.refuses(decision("system:group", null), true));
    }

    @Test
    void offByDefault() {
        assertFalse(HitlPauseIdPolicy.requiredDeploymentWide());
    }
}
