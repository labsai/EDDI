/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The values of {@link LedgerEntry#review()} (email channel v3 plan §7): where
 * the review of a held mail stands, and which reviewable checks held it.
 * <ul>
 * <li>{@code held:<reasons>} — waiting for a reviewer
 * ({@code State.HELD});</li>
 * <li>{@code released:<decision>:<reasons>} — a reviewer chose {@code runOnce}
 * or {@code runAndAllow}; the row is {@code RECEIVED} for the next poll, and on
 * that run exactly {@code <reasons>} are skipped;</li>
 * <li>{@code discarded:<reasons>}, {@code expired:<reasons>} — refused.</li>
 * </ul>
 * {@code <reasons>} are {@link #NOT_ALLOWLISTED} and {@link #UNAUTHENTICATED},
 * joined by {@code +}, in that order.
 */
public final class EmailReviewState {

    /** The sender is not on a non-empty allowlist. */
    public static final String NOT_ALLOWLISTED = "not_allowlisted";
    /** The sender did not authenticate and the integration requires it. */
    public static final String UNAUTHENTICATED = "unauthenticated";

    public static final String HELD = "held:";
    public static final String RELEASED = "released:";
    public static final String DISCARDED = "discarded:";
    public static final String EXPIRED = "expired:";

    /** The decisions a reviewer can take. */
    public static final String DISCARD = "discard";
    public static final String RUN_ONCE = "runOnce";
    public static final String RUN_AND_ALLOW = "runAndAllow";
    public static final Set<String> DECISIONS = Set.of(DISCARD, RUN_ONCE, RUN_AND_ALLOW);

    /**
     * The stored verdict of a sender whose From DKIM and DMARC alignment passed.
     */
    public static final String ALIGNED_PASS = "aligned-pass";

    /** {@code lastError} of a discarded mail. */
    public static final String REVIEW_DISCARDED = "review_discarded";
    /** {@code lastError} of a held mail nobody decided in time. */
    public static final String REVIEW_EXPIRED = "review_expired";
    /** {@code lastError} of a mail refused because the review queue is full. */
    public static final String REVIEW_FULL = "review_full";
    /**
     * {@code lastError} of a mail refused because its sender domain filled its
     * share.
     */
    public static final String REVIEW_FULL_DOMAIN = "review_full:sender_domain";

    private EmailReviewState() {
    }

    /** {@code a+b} of the given reasons, in canonical order. */
    public static String join(List<String> reasons) {
        List<String> ordered = new ArrayList<>();
        for (String reason : List.of(NOT_ALLOWLISTED, UNAUTHENTICATED)) {
            if (reasons.contains(reason)) {
                ordered.add(reason);
            }
        }
        return String.join("+", ordered);
    }

    public static String held(List<String> reasons) {
        return HELD + join(reasons);
    }

    public static String released(String decision, List<String> reasons) {
        return RELEASED + decision + ":" + join(reasons);
    }

    public static boolean isHeld(String review) {
        return review != null && review.startsWith(HELD);
    }

    public static boolean isReleased(String review) {
        return review != null && review.startsWith(RELEASED);
    }

    /** The reasons a review value names; empty for null or an unknown value. */
    public static List<String> reasons(String review) {
        if (review == null) {
            return List.of();
        }
        int colon = review.lastIndexOf(':');
        String tail = colon < 0 ? "" : review.substring(colon + 1);
        List<String> reasons = new ArrayList<>();
        for (String part : tail.split("\\+")) {
            String reason = part.trim().toLowerCase(Locale.ROOT);
            if ((NOT_ALLOWLISTED.equals(reason) || UNAUTHENTICATED.equals(reason)) && !reasons.contains(reason)) {
                reasons.add(reason);
            }
        }
        return List.copyOf(reasons);
    }

    /**
     * The decision of a released value ({@code runOnce} / {@code runAndAllow}), or
     * null.
     */
    public static String decision(String review) {
        if (!isReleased(review)) {
            return null;
        }
        String rest = review.substring(RELEASED.length());
        int colon = rest.indexOf(':');
        return colon < 0 ? rest : rest.substring(0, colon);
    }

    /** Whether the stored verdict is an aligned DKIM pass. */
    public static boolean alignedPass(String authVerdict) {
        return ALIGNED_PASS.equals(authVerdict);
    }

    /**
     * Whether {@code row} is a mail a reviewer released without an aligned pass: it
     * runs anonymously, with read-only tools, and never causes outgoing mail (plan
     * §7.3, §16).
     */
    public static boolean anonymousRelease(LedgerEntry row) {
        return row != null && isReleased(row.review()) && !alignedPass(row.authVerdict());
    }
}
