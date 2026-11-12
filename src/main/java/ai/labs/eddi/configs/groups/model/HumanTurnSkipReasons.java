/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import java.util.List;

/**
 * The reasons a {@code SKIPPED} transcript entry carries when the turn skipped
 * was a human member's. The writers ({@code GroupHitlCoordinator},
 * {@code MemberTurnExecutor}) build their reason from these, and the GDPR
 * export ({@code ThirdPartyIdentifiers}) reads them back as evidence that the
 * speaker is a person — one definition, so a wording change cannot silently end
 * the match.
 */
public final class HumanTurnSkipReasons {

    /**
     * Start of the reason for a human turn that timed out; the member's display
     * name and the window follow.
     */
    public static final String TIMED_OUT_PREFIX = "No response from ";

    /** The reason for a human member asked for an automated turn. */
    public static final String AUTOMATED_TURN = "Human member does not take automated turns in this context — skipped";

    private static final List<String> PREFIXES = List.of(TIMED_OUT_PREFIX, AUTOMATED_TURN);

    private HumanTurnSkipReasons() {
    }

    /**
     * Whether {@code reason} is one a human member's skipped turn is written with.
     */
    public static boolean isHumanSkipReason(String reason) {
        if (reason == null) {
            return false;
        }
        for (String prefix : PREFIXES) {
            if (reason.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
