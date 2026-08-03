/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AbstentionDetector} (I4).
 * <p>
 * The substring negatives are the important ones. A member who writes "I'll
 * pass on point one, but I disagree about the timeline" has stated a position;
 * reading that as an abstention deletes it from the group record silently, and
 * — once every member's contribution is misread the same way — can end the
 * phase early through I2's convergence hook on the strength of arguments nobody
 * read. The failure leaves no trace, so the detector is strict by design and
 * these tests pin exactly how strict.
 *
 * @author tests
 */
class AbstentionDetectorTest {

    @Test
    void bareToken_isAbstention() {
        assertTrue(AbstentionDetector.isAbstention("PASS"));
    }

    @Test
    void caseAndWhitespaceVariants_areAbstentions() {
        assertTrue(AbstentionDetector.isAbstention("pass"));
        assertTrue(AbstentionDetector.isAbstention("Pass"));
        assertTrue(AbstentionDetector.isAbstention("  PASS  "));
        assertTrue(AbstentionDetector.isAbstention("\n PASS \n"));
    }

    @Test
    void singleTrailingTerminator_isAccepted() {
        // The most common near-miss: a model that adds a full stop has not said
        // anything more than one that did not.
        assertTrue(AbstentionDetector.isAbstention("PASS."));
        assertTrue(AbstentionDetector.isAbstention("pass!"));
        assertTrue(AbstentionDetector.isAbstention("  PASS.  "));
    }

    @Test
    void runOfTerminators_isNotAnAbstention() {
        // Stripping greedily would accept this, but a model producing "PASS......"
        // is not reliably signalling the same thing as one producing "PASS".
        assertFalse(AbstentionDetector.isAbstention("PASS..."));
    }

    @Test
    void tokenInsideASentence_isNotAnAbstention() {
        assertFalse(AbstentionDetector.isAbstention("I'll PASS on point one"),
                "a contribution containing the word must never be deleted as an abstention");
        assertFalse(AbstentionDetector.isAbstention("I pass on the first question, but disagree on the second"));
        assertFalse(AbstentionDetector.isAbstention("My answer is: PASS the proposal as written"));
    }

    @Test
    void tokenFollowedByExplanation_isNotAnAbstention() {
        // The member said PASS and then said something. The something is a position.
        assertFalse(AbstentionDetector.isAbstention("PASS - I have nothing to add on this round"));
        assertFalse(AbstentionDetector.isAbstention("PASS\n\nAlthough I would note the budget risk."));
    }

    @Test
    void emptyOrNull_isNotAnAbstention() {
        // An empty response is a failed turn, not a considered decision to abstain —
        // conflating them would hide agent failures as deliberate silence.
        assertFalse(AbstentionDetector.isAbstention(null));
        assertFalse(AbstentionDetector.isAbstention(""));
        assertFalse(AbstentionDetector.isAbstention("   "));
    }

    @Test
    void otherWords_areNotAbstentions() {
        assertFalse(AbstentionDetector.isAbstention("PASSED"));
        assertFalse(AbstentionDetector.isAbstention("BYPASS"));
        assertFalse(AbstentionDetector.isAbstention("NO COMMENT"));
        assertFalse(AbstentionDetector.isAbstention("."));
    }

    @Test
    void abstentionInstruction_discouragesAgreeablePassing() {
        // Without this qualifier the instruction reads as "passing is fine", and
        // models abstain to be agreeable — the same sycophancy the OPINION template
        // directive and I2's judge prompt both guard against.
        assertTrue(AbstentionDetector.ABSTENTION_INSTRUCTION.contains("PASS"));
        assertTrue(AbstentionDetector.ABSTENTION_INSTRUCTION.contains("do not pass merely to agree"));
    }

    // =================================================================
    // isEnabledFor — the one rule the instruction and detection sites share
    // =================================================================

    private DiscussionPhase phase(PhaseType type, boolean allowAbstention) {
        return new DiscussionPhase("P", type, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                false, null, 1, false, null, allowAbstention);
    }

    @Test
    void isEnabledFor_discussionPhaseWithFlag_isEnabled() {
        assertTrue(AbstentionDetector.isEnabledFor(phase(PhaseType.OPINION, true)));
        assertTrue(AbstentionDetector.isEnabledFor(phase(PhaseType.CRITIQUE, true)));
        assertTrue(AbstentionDetector.isEnabledFor(phase(PhaseType.SYNTHESIS, true)));
    }

    @Test
    void isEnabledFor_withoutTheFlag_isDisabled() {
        assertFalse(AbstentionDetector.isEnabledFor(phase(PhaseType.OPINION, false)));
    }

    @Test
    void isEnabledFor_taskPhases_areNeverEnabled() {
        // TaskForceEngine builds its own inputs and never routes through
        // buildPhaseInput, so the member is never told PASS exists. Detecting it
        // anyway is actively harmful: "PASS" is a natural verdict word for a VERIFY
        // turn, and an abstention's null content sends parseAndApplyVerification
        // down its mark-everything-passed fallback — silently verifying tasks
        // nobody checked. An EXECUTE turn would complete its task with no result.
        assertFalse(AbstentionDetector.isEnabledFor(phase(PhaseType.PLAN, true)));
        assertFalse(AbstentionDetector.isEnabledFor(phase(PhaseType.EXECUTE, true)));
        assertFalse(AbstentionDetector.isEnabledFor(phase(PhaseType.VERIFY, true)),
                "detection without the matching instruction is a data-integrity bug, not a partial feature");
    }

    @Test
    void isEnabledFor_nullPhase_isDisabled() {
        assertFalse(AbstentionDetector.isEnabledFor(null));
    }

    @Test
    void dissentInput_carriesTheSynthesisAndBothOptions() {
        String input = AbstentionDetector.buildDissentInput("We recommend option B.");

        assertTrue(input.contains("We recommend option B."));
        assertTrue(input.contains("PASS"), "the dissenter needs the same opt-out token");
        assertTrue(input.contains("3 sentences"), "a minority report is a flag, not a second essay");
        assertTrue(input.contains("do not soften"),
                "asking for dissent without discouraging softening produces polite non-dissent");
    }

    @Test
    void dissentInput_missingSynthesis_stillRenders() {
        // A synthesis phase that errored leaves no text; the prompt must not render
        // the literal "null" and invite a model to critique it.
        String input = AbstentionDetector.buildDissentInput(null);

        assertFalse(input.contains("null"));
        assertTrue(input.contains("(no synthesis was produced)"));
    }
}
