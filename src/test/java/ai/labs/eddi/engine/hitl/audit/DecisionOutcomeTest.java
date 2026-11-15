/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.audit;

import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("DecisionOutcome.of — the single outcome mapping")
class DecisionOutcomeTest {

    @Test
    void humanDecisions() {
        assertEquals(DecisionOutcome.APPROVED, DecisionOutcome.of(HitlVerdict.APPROVED, "alice", false, false));
        assertEquals(DecisionOutcome.APPROVED_EDITED, DecisionOutcome.of(HitlVerdict.APPROVED, "alice", true, false));
        assertEquals(DecisionOutcome.REJECTED, DecisionOutcome.of(HitlVerdict.REJECTED, "alice", false, false));
        assertEquals(DecisionOutcome.REJECTED, DecisionOutcome.of(HitlVerdict.REJECTED, "alice", true, false),
                "an edit flag on a rejection changes nothing");
        assertEquals(DecisionOutcome.APPROVED, DecisionOutcome.of(HitlVerdict.APPROVED, null, false, false), "no decider is a human");
        assertEquals(DecisionOutcome.APPROVED, DecisionOutcome.of(HitlVerdict.APPROVED, "slack:U1", false, false));
    }

    @Test
    void systemDecisions() {
        assertEquals(DecisionOutcome.AUTO_APPROVED, DecisionOutcome.of(HitlVerdict.APPROVED, "system:timeout", false, false));
        assertEquals(DecisionOutcome.AUTO_APPROVED, DecisionOutcome.of(HitlVerdict.APPROVED, "system:timeout", true, false),
                "the system never edits");
        assertEquals(DecisionOutcome.AUTO_REJECTED, DecisionOutcome.of(HitlVerdict.REJECTED, "system:no-progress", false, false));
    }

    @Test
    void cancellationWinsOverEverything() {
        assertEquals(DecisionOutcome.ABORTED, DecisionOutcome.of(HitlVerdict.APPROVED, "alice", true, true));
        assertEquals(DecisionOutcome.ABORTED, DecisionOutcome.of(null, "system:timeout", false, true));
        assertEquals(DecisionOutcome.ABORTED, DecisionOutcome.of(HitlVerdict.REJECTED, null, false, true));
    }

    @Test
    void channelMapping() {
        assertEquals(HitlChannel.SLACK, HitlChannel.of("slack:U1", HitlChannel.MANAGER), "a decider prefix wins over the header");
        assertEquals(HitlChannel.MCP, HitlChannel.of("mcp:alice", null));
        assertEquals(HitlChannel.TIMEOUT, HitlChannel.of("system:timeout", null));
        assertEquals(HitlChannel.SYSTEM, HitlChannel.of("system:no-progress", null));
        assertEquals(HitlChannel.MANAGER, HitlChannel.of("alice", HitlChannel.MANAGER));
        assertEquals(HitlChannel.REST, HitlChannel.of("alice", null));
        assertEquals(HitlChannel.MANAGER, HitlChannel.fromClientHeader(" Manager "));
        assertEquals(HitlChannel.REST, HitlChannel.fromClientHeader("curl"));
        assertEquals(HitlChannel.REST, HitlChannel.fromClientHeader(null));
    }
}
