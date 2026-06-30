/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GroupApprovalRequest")
class GroupApprovalRequestTest {

    private GroupApprovalRequest request;

    @BeforeEach
    void setUp() {
        request = new GroupApprovalRequest();
    }

    @Nested
    @DisplayName("default constructor")
    class DefaultConstructor {

        @Test
        @DisplayName("decision is null by default")
        void decisionIsNull() {
            assertNull(request.getDecision());
        }

        @Test
        @DisplayName("taskApprovals is null by default")
        void taskApprovalsIsNull() {
            assertNull(request.getTaskApprovals());
        }
    }

    @Nested
    @DisplayName("decision field")
    class DecisionField {

        @Test
        @DisplayName("setDecision/getDecision round-trips")
        void decisionRoundTrip() {
            var decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setNote("looks good");
            decision.setDecidedBy("reviewer");

            request.setDecision(decision);

            assertEquals(HitlVerdict.APPROVED, request.getDecision().getVerdict());
            assertEquals("looks good", request.getDecision().getNote());
            assertEquals("reviewer", request.getDecision().getDecidedBy());
        }

        @Test
        @DisplayName("setDecision with null is valid")
        void setDecisionNull() {
            request.setDecision(new HitlDecision());
            request.setDecision(null);

            assertNull(request.getDecision());
        }
    }

    @Nested
    @DisplayName("taskApprovals field")
    class TaskApprovalsField {

        @Test
        @DisplayName("setTaskApprovals/getTaskApprovals round-trips with populated map")
        void taskApprovalsRoundTrip() {
            Map<String, String> approvals = new HashMap<>();
            approvals.put("task-1", "APPROVED");
            approvals.put("task-2", "REJECTED");

            request.setTaskApprovals(approvals);

            assertEquals(2, request.getTaskApprovals().size());
            assertEquals("APPROVED", request.getTaskApprovals().get("task-1"));
            assertEquals("REJECTED", request.getTaskApprovals().get("task-2"));
        }

        @Test
        @DisplayName("empty map is valid")
        void emptyMapIsValid() {
            request.setTaskApprovals(Map.of());

            assertNotNull(request.getTaskApprovals());
            assertTrue(request.getTaskApprovals().isEmpty());
        }

        @Test
        @DisplayName("null map is valid")
        void nullMapIsValid() {
            request.setTaskApprovals(null);

            assertNull(request.getTaskApprovals());
        }

        @Test
        @DisplayName("setTaskApprovals overwrites previous value")
        void setOverwritesPrevious() {
            request.setTaskApprovals(Map.of("task-1", "APPROVED"));
            request.setTaskApprovals(Map.of("task-2", "REJECTED"));

            assertEquals(1, request.getTaskApprovals().size());
            assertNull(request.getTaskApprovals().get("task-1"));
            assertEquals("REJECTED", request.getTaskApprovals().get("task-2"));
        }
    }
}
