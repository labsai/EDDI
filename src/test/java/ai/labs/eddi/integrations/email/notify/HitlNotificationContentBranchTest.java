/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.notify;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.integrations.email.notify.HitlNotificationContent.Pause;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deadline wording, the agent label fallbacks and the one-line sanitising
 * of the HITL notification mail.
 */
class HitlNotificationContentBranchTest {

    private static final Instant PAUSED = Instant.parse("2026-11-05T10:00:00Z");

    private static Pause pause(String name, String id, HitlTimeoutPolicy policy, String timeout, Instant pausedAt, String reason) {
        return new Pause(name, id, null, reason, pausedAt, policy, timeout, "conv-1", null);
    }

    private static String text(Pause pause) {
        return HitlNotificationContent.render(pause, null).text();
    }

    @Test
    void eachTimeoutPolicyNamesWhatHappensAtTheDeadline() {
        assertTrue(text(pause("A", null, HitlTimeoutPolicy.AUTO_APPROVE, "PT1H", PAUSED, null))
                .contains("Deadline:      2026-11-05 11:00 UTC, then it is approved automatically"));
        assertTrue(text(pause("A", null, HitlTimeoutPolicy.AUTO_REJECT, "PT30M", PAUSED, null))
                .contains("Deadline:      2026-11-05 10:30 UTC, then it is rejected automatically"));
        assertTrue(text(pause("A", null, HitlTimeoutPolicy.ABORT, "P1D", PAUSED, null))
                .contains("Deadline:      2026-11-06 10:00 UTC, then the conversation is cancelled"));
    }

    @Test
    void withoutAUsableDeadlineItWaits() {
        String waits = "Deadline:      none — it waits until someone decides";
        assertTrue(text(pause("A", null, null, "PT1H", PAUSED, null)).contains(waits));
        assertTrue(text(pause("A", null, HitlTimeoutPolicy.WAIT_INDEFINITELY, "PT1H", PAUSED, null)).contains(waits));
        assertTrue(text(pause("A", null, HitlTimeoutPolicy.AUTO_REJECT, null, PAUSED, null)).contains(waits));
        assertTrue(text(pause("A", null, HitlTimeoutPolicy.AUTO_REJECT, "PT1H", null, null)).contains(waits));
        assertTrue(text(pause("A", null, HitlTimeoutPolicy.AUTO_REJECT, "one hour", PAUSED, null)).contains(waits));
    }

    @Test
    void aTimeoutPastTheEndOfTimeIsStatedAsADuration() {
        String text = text(pause("A", null, HitlTimeoutPolicy.AUTO_REJECT, "PT2562047788015215H", PAUSED, null));
        assertTrue(text.contains("Deadline:      after PT2562047788015215H, then it is rejected automatically"), text);
    }

    @Test
    void theAgentIsLabelledByNameThenIdThenGenerically() {
        assertEquals("Support", HitlNotificationContent.agentLabel(pause(" Support\n", "a-1", null, null, null, null)));
        assertEquals("agent a-1", HitlNotificationContent.agentLabel(pause("  ", "a-1", null, null, null, null)));
        assertEquals("an agent", HitlNotificationContent.agentLabel(pause(null, null, null, null, null, null)));
        assertEquals("[EDDI] Approval needed: an agent", HitlNotificationContent.render(pause(null, "", null, null, null, null), null).subject());
    }

    @Test
    void aSparsePauseOmitsReasonAndStart() {
        String text = text(pause("A", null, null, null, null, " \n "));
        assertFalse(text.contains("Reason:"), text);
        assertFalse(text.contains("Waiting since:"), text);
        assertTrue(text.contains("(/manage/approvals, sign-in required)"), "without a public URL the page is named, not linked");
    }

    @Test
    void oneLineCollapsesControlCharactersAndCaps() {
        assertEquals("", HitlNotificationContent.oneLine(null, 10));
        assertEquals("a b c", HitlNotificationContent.oneLine("\t a\r\n b \u0007 c  ", 10));
        assertEquals("abcde…", HitlNotificationContent.oneLine("abcdefgh", 5));
        assertEquals("ab", HitlNotificationContent.oneLine("ab", 2 + 1));
    }
}
