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

    /** What separates the member's name from the window in a timed-out reason. */
    static final String WINDOW_SEPARATOR = " within ";

    private HumanTurnSkipReasons() {
    }

    /**
     * The reason for a human turn that timed out: {@link #TIMED_OUT_PREFIX}, the
     * member's display name and, when there was one, {@code " within <window>"}.
     */
    public static String timedOut(String displayName, String window) {
        return TIMED_OUT_PREFIX + displayName + (window != null ? WINDOW_SEPARATOR + window : "");
    }

    /**
     * {@code reason} with the member's name replaced by {@code replacement} when it
     * is a timed-out reason ({@link #timedOut}); any other reason unchanged. The
     * window is kept only when it is recognisably the one the writer appended — the
     * last {@link #WINDOW_SEPARATOR} followed by a single token — so a display name
     * that itself contains the separator cannot leak through it.
     */
    public static String maskTimedOutName(String reason, String replacement) {
        if (reason == null || !reason.startsWith(TIMED_OUT_PREFIX)) {
            return reason;
        }
        String rest = reason.substring(TIMED_OUT_PREFIX.length());
        int at = rest.lastIndexOf(WINDOW_SEPARATOR);
        if (at >= 0) {
            String window = rest.substring(at + WINDOW_SEPARATOR.length());
            if (!window.isEmpty() && window.chars().noneMatch(Character::isWhitespace)) {
                return TIMED_OUT_PREFIX + replacement + WINDOW_SEPARATOR + window;
            }
        }
        return TIMED_OUT_PREFIX + replacement;
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
