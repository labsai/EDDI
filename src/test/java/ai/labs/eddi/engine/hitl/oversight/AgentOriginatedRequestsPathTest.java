/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision-path matchers: which paths record a human's HITL decision, and
 * that deciding it is linear in the path however the caller shapes it.
 */
class AgentOriginatedRequestsPathTest {

    @ParameterizedTest
    @ValueSource(strings = {"agents/c1/resume", "/agents/c1/resume/", "agents/c1/resume/stream", "agents/a/b/resume",
            "groups/g1/conversations/c1/approve", "groups/g1/conversations/c1/approve/stream", "groups/g1/conversations/c1/human-input",
            "groups/g/x/conversations/c/y/approve", "agents/c1;v=1/resume;x=2"})
    void decisionPaths(String path) {
        assertTrue(AgentOriginatedRequests.isDecisionPath(path), path);
        assertTrue(AgentOriginatedRequests.containsDecisionPath("https://eddi.example/" + path + "?x=1"), path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"agents/c1", "agents/resume", "agents/c1/cancel", "agents/c1/resume/other", "agents/c1/stream",
            "groups/g1/conversations/c1/cancel", "groups/g1/conversations/approve", "groups/conversations/c1/approve",
            "groups/g1/conversations/c1/human-input/stream", "agentstore/agents/a1", "hitl/samples/s1", ""})
    void otherPaths(String path) {
        assertFalse(AgentOriginatedRequests.isDecisionPath(path), path);
    }

    @Test
    void anchoredFormNeedsTheResourceFirst() {
        assertFalse(AgentOriginatedRequests.isDecisionPath("x/agents/c1/resume"));
        assertTrue(AgentOriginatedRequests.containsDecisionPath("/x/agents/c1/resume"));
        assertFalse(AgentOriginatedRequests.containsDecisionPath("/x/agents/c1?next=/agents/c1/resume"));
        // the path ends at the first ? or #, even when the query holds another one
        assertTrue(AgentOriginatedRequests.containsDecisionPath("/agents/c1/resume?a=1#frag"));
        assertTrue(AgentOriginatedRequests.containsDecisionPath("/agents/c1/resume?next=a#b?c"));
        assertFalse(AgentOriginatedRequests.isDecisionPath(null));
        assertFalse(AgentOriginatedRequests.containsDecisionPath(null));
    }

    /**
     * The input a backtracking pattern with two open-ended identifier groups is
     * polynomial on: many repetitions of {@code a/conversations/} that never end in
     * a decision segment.
     */
    @Test
    void repeatedSegmentsAreDecidedInLinearTime() {
        String repeated = "groups/a/conversations/" + "a/conversations/".repeat(200_000) + "x";
        String agents = "agents/" + "a/resume/".repeat(200_000) + "x";
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertFalse(AgentOriginatedRequests.isDecisionPath(repeated));
            assertFalse(AgentOriginatedRequests.isDecisionPath(agents));
            assertTrue(AgentOriginatedRequests.containsDecisionPath(agents));
            assertFalse(AgentOriginatedRequests.containsDecisionPath(repeated));
        });
    }
}
