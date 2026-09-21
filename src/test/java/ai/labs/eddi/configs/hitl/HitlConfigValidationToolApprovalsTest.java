/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HitlConfigValidationToolApprovalsTest {

    private static ToolApprovalsConfig cfg() {
        var c = new ToolApprovalsConfig();
        c.setRequireApproval(List.of("mcp:*", "delete_*"));
        return c;
    }

    private static IllegalArgumentException expectReject(ToolApprovalsConfig cfg) {
        return assertThrows(IllegalArgumentException.class,
                () -> HitlConfigValidation.validateToolApprovals(cfg, "hitlConfig.toolApprovals"));
    }

    @Test
    void nullConfig_isNoOp() {
        assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(null, "x"));
    }

    @Test
    void fullyValidConfig_passes() {
        var c = cfg();
        c.setExempt(List.of("mcp:read_*"));
        c.setMaxPausesPerTurn(3);
        c.setMaxAutoApprovalsPerTurn(2);
        c.setOnNoProgress("WAIT_FOR_HUMAN");
        c.setInGroupTurns("REJECT");
        c.setPauseReason("needs sign-off");
        assertDoesNotThrow(() -> HitlConfigValidation.validateToolApprovals(c, "hitlConfig.toolApprovals"));
    }

    @Test
    void illegalPattern_rejectedWithIndex() {
        var c = new ToolApprovalsConfig();
        c.setRequireApproval(List.of("ok_*", "has space"));
        var ex = expectReject(c);
        assertTrue(ex.getMessage().contains("requireApproval[1]"), ex.getMessage());
    }

    @Test
    void unknownSourcePrefix_suggestsCorrection() {
        var c = new ToolApprovalsConfig();
        c.setRequireApproval(List.of("mpc:read_*"));
        var ex = expectReject(c);
        assertTrue(ex.getMessage().contains("mcp"), ex.getMessage());
    }

    @Test
    void samePatternInBothLists_rejected() {
        var c = new ToolApprovalsConfig();
        c.setRequireApproval(List.of("delete_*"));
        c.setExempt(List.of("delete_*"));
        var ex = expectReject(c);
        assertTrue(ex.getMessage().contains("both"), ex.getMessage());
    }

    @Test
    void duplicateWithinList_rejected() {
        var c = new ToolApprovalsConfig();
        c.setRequireApproval(List.of("mcp:*", "mcp:*"));
        var ex = expectReject(c);
        assertTrue(ex.getMessage().contains("duplicate"), ex.getMessage());
    }

    @Test
    void exemptWithoutRequire_rejected() {
        var c = new ToolApprovalsConfig();
        c.setExempt(List.of("mcp:read_*"));
        var ex = expectReject(c);
        assertTrue(ex.getMessage().contains("no effect"), ex.getMessage());
    }

    @Test
    void maxPausesPerTurnOutOfRange_rejected() {
        var c = cfg();
        c.setMaxPausesPerTurn(0);
        assertTrue(expectReject(c).getMessage().contains("maxPausesPerTurn"));
        c.setMaxPausesPerTurn(11);
        assertTrue(expectReject(c).getMessage().contains("maxPausesPerTurn"));
    }

    @Test
    void maxAutoApprovalsPerTurnOutOfRange_rejected() {
        var c = cfg();
        c.setMaxAutoApprovalsPerTurn(11);
        assertTrue(expectReject(c).getMessage().contains("maxAutoApprovalsPerTurn"));
    }

    @Test
    void invalidOnNoProgress_rejected() {
        var c = cfg();
        c.setOnNoProgress("MAYBE");
        assertTrue(expectReject(c).getMessage().contains("onNoProgress"));
    }

    @Test
    void inGroupTurnsInbox_rejectedAsReserved() {
        var c = cfg();
        c.setInGroupTurns("INBOX");
        assertTrue(expectReject(c).getMessage().contains("reserved"));
    }

    @Test
    void inGroupTurnsUnknown_rejected() {
        var c = cfg();
        c.setInGroupTurns("SOMETHING");
        assertTrue(expectReject(c).getMessage().contains("inGroupTurns"));
    }

    @Test
    void invalidApprovalTimeoutWithFinitePolicy_rejected() {
        var c = cfg();
        c.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
        c.setApprovalTimeout("30m"); // not ISO-8601
        assertThrows(IllegalArgumentException.class,
                () -> HitlConfigValidation.validateToolApprovals(c, "hitlConfig.toolApprovals"));
    }

    @Test
    void overlongPauseReason_rejected() {
        var c = cfg();
        c.setPauseReason("x".repeat(501));
        assertTrue(expectReject(c).getMessage().contains("pauseReason"));
    }

    @Test
    void overlongPendingMessage_rejected() {
        var c = cfg();
        c.setPendingMessage("x".repeat(501));
        assertTrue(expectReject(c).getMessage().contains("pendingMessage"));
    }
}
