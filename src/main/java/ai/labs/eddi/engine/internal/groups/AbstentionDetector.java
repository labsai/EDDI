/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;

/**
 * Recognizes a member's decision to abstain from a round (I4).
 * <p>
 * <b>Exact-token equality, never containment.</b> A member who writes "I'll
 * pass on point one, but I disagree about the timeline" has made a substantive
 * contribution that happens to contain the word "pass"; treating that as an
 * abstention would silently delete their position from the record and — once
 * every other member also abstains — could end the phase early via I2's
 * convergence hook on the strength of an argument nobody read. The failure is
 * silent and the content is unrecoverable from the group transcript, which is
 * why the check is deliberately strict rather than forgiving.
 * <p>
 * Case-insensitive and whitespace-trimmed, because "PASS", "pass" and " Pass\n"
 * are the same intent expressed by models that differ in how literally they
 * follow a formatting instruction. Trailing sentence punctuation is accepted
 * for the same reason — "PASS." is the single most common near-miss, and a
 * model that adds a full stop has not said anything more than the model that
 * did not.
 *
 * @author ginccc
 */
public final class AbstentionDetector {

    /** The token members are instructed to reply with. */
    public static final String PASS_TOKEN = "PASS";

    /**
     * Appended to a phase's rendered input when {@code allowAbstention} is on.
     * Phrased as a permission rather than an option to prefer: an instruction that
     * makes abstaining sound encouraged produces members who abstain to be
     * agreeable, which is the sycophancy this and I2's judge template both exist to
     * avoid.
     */
    public static final String ABSTENTION_INSTRUCTION = "\n\nIf you have nothing new to add beyond what has already been said, reply with exactly PASS. "
            + "Only do so if you genuinely have nothing to add — do not pass merely to agree with others.";

    private AbstentionDetector() {
    }

    /**
     * Whether abstention is active for {@code phase} — the single rule both the
     * instruction site ({@code GroupContextBuilder}) and the detection sites
     * ({@code MemberTurnExecutor}) consult, so they cannot drift apart.
     * <p>
     * Task phases are excluded even when {@code allowAbstention} is set, because
     * {@code TaskForceEngine} builds its PLAN/EXECUTE/VERIFY inputs itself and
     * never routes through {@code buildPhaseInput} — the member is never told the
     * token exists. Detecting it anyway would be strictly harmful there: "PASS" is
     * a natural verdict word for a VERIFY turn, and an abstention's {@code null}
     * content sends {@code parseAndApplyVerification} down its
     * mark-everything-passed fallback, silently verifying tasks nobody checked. An
     * EXECUTE turn would likewise complete its task with no result. Detection
     * without the matching instruction is not a partial feature; it is a
     * data-integrity bug.
     */
    public static boolean isEnabledFor(DiscussionPhase phase) {
        if (phase == null || !phase.allowAbstention()) {
            return false;
        }
        return switch (phase.type()) {
            case PLAN, EXECUTE, VERIFY -> false;
            default -> true;
        };
    }

    /**
     * Whether {@code response} is an abstention.
     * <p>
     * Accepts only the bare token, optionally trailed by a single sentence
     * terminator. Anything else — including a token embedded in a sentence, or
     * "PASS" followed by an explanation — is a contribution.
     */
    public static boolean isAbstention(String response) {
        if (response == null) {
            return false;
        }
        String trimmed = response.trim();
        // Strip at most ONE trailing terminator, not a run of them: stripping
        // greedily would let "PASS......" through, and a model producing that is
        // not reliably signalling the same thing as one producing "PASS".
        if (trimmed.length() > 1) {
            char last = trimmed.charAt(trimmed.length() - 1);
            if (last == '.' || last == '!') {
                trimmed = trimmed.substring(0, trimmed.length() - 1).stripTrailing();
            }
        }
        return PASS_TOKEN.equalsIgnoreCase(trimmed);
    }

    /**
     * The dissent-round prompt (I4b). Bounded to three sentences because a minority
     * report is a flag for a human reader, not a second essay — and because an
     * unbounded prompt after every synthesis is what makes the feature too
     * expensive to leave on.
     */
    public static String buildDissentInput(String synthesis) {
        return """
                The panel has produced this synthesis:

                %s

                In 3 sentences or fewer, state where you still materially disagree with it.
                State a real, substantive disagreement if you have one — do not soften or \
                withhold it for the sake of consensus.
                If you have no material disagreement, reply with exactly PASS."""
                .formatted(synthesis != null && !synthesis.isBlank() ? synthesis : "(no synthesis was produced)");
    }
}
