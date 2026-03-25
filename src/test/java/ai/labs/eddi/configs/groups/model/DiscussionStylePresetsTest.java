package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DiscussionStylePresets} — verifies that each style
 * preset expands into the correct sequence of phases with proper configuration.
 */
class DiscussionStylePresetsTest {

    // --- ROUND_TABLE ---

    @Test
    void roundTable_singleRound_producesOpinionAndSynthesis() {
        List<DiscussionPhase> phases = DiscussionStylePresets.expand(DiscussionStyle.ROUND_TABLE, 1);

        assertEquals(2, phases.size());

        // Phase 0: Initial opinion (no context)
        var opinion = phases.get(0);
        assertEquals(PhaseType.OPINION, opinion.type());
        assertEquals(ContextScope.NONE, opinion.contextScope());
        assertEquals("ALL", opinion.participants());
        assertEquals(1, opinion.repeats());

        // Phase 1: Synthesis
        var synthesis = phases.get(1);
        assertEquals(PhaseType.SYNTHESIS, synthesis.type());
        assertEquals("MODERATOR", synthesis.participants());
    }

    @Test
    void roundTable_multipleRounds_producesOpinionDiscussionAndSynthesis() {
        List<DiscussionPhase> phases = DiscussionStylePresets.expand(DiscussionStyle.ROUND_TABLE, 3);

        assertEquals(3, phases.size());

        // Phase 0: Independent opinion
        assertEquals(PhaseType.OPINION, phases.get(0).type());
        assertEquals(ContextScope.NONE, phases.get(0).contextScope());
        assertEquals(1, phases.get(0).repeats());

        // Phase 1: Discussion (with context, repeats=2)
        assertEquals(PhaseType.OPINION, phases.get(1).type());
        assertEquals(ContextScope.FULL, phases.get(1).contextScope());
        assertEquals(2, phases.get(1).repeats());

        // Phase 2: Synthesis
        assertEquals(PhaseType.SYNTHESIS, phases.get(2).type());
    }

    // --- PEER_REVIEW ---

    @Test
    void peerReview_produces4Phases() {
        List<DiscussionPhase> phases = DiscussionStylePresets.expand(DiscussionStyle.PEER_REVIEW, 2);

        assertEquals(4, phases.size());

        // Phase 0: Independent opinions (parallel, no context)
        assertEquals(PhaseType.OPINION, phases.get(0).type());
        assertEquals(TurnOrder.PARALLEL, phases.get(0).turnOrder());
        assertEquals(ContextScope.NONE, phases.get(0).contextScope());
        assertFalse(phases.get(0).targetEachPeer());

        // Phase 1: Critique (sequential, each→each peer)
        assertEquals(PhaseType.CRITIQUE, phases.get(1).type());
        assertEquals(TurnOrder.SEQUENTIAL, phases.get(1).turnOrder());
        assertTrue(phases.get(1).targetEachPeer());

        // Phase 2: Revision (parallel, own feedback only)
        assertEquals(PhaseType.REVISION, phases.get(2).type());
        assertEquals(TurnOrder.PARALLEL, phases.get(2).turnOrder());
        assertEquals(ContextScope.OWN_FEEDBACK, phases.get(2).contextScope());

        // Phase 3: Synthesis
        assertEquals(PhaseType.SYNTHESIS, phases.get(3).type());
        assertEquals("MODERATOR", phases.get(3).participants());
    }

    // --- DEVIL_ADVOCATE ---

    @Test
    void devilAdvocate_produces4Phases() {
        List<DiscussionPhase> phases = DiscussionStylePresets.expand(DiscussionStyle.DEVIL_ADVOCATE, 1);

        assertEquals(4, phases.size());

        // Phase 0: Independent opinions
        assertEquals(PhaseType.OPINION, phases.get(0).type());
        assertEquals(ContextScope.NONE, phases.get(0).contextScope());

        // Phase 1: Challenge (only devil's advocate)
        assertEquals(PhaseType.CHALLENGE, phases.get(1).type());
        assertEquals("ROLE:DEVIL_ADVOCATE", phases.get(1).participants());

        // Phase 2: Defense (all agents)
        assertEquals(PhaseType.DEFENSE, phases.get(2).type());
        assertEquals("ALL", phases.get(2).participants());

        // Phase 3: Synthesis
        assertEquals(PhaseType.SYNTHESIS, phases.get(3).type());
    }

    // --- DELPHI ---

    @Test
    void delphi_producesAnonymousRoundsAndSynthesis() {
        List<DiscussionPhase> phases = DiscussionStylePresets.expand(DiscussionStyle.DELPHI, 3);

        assertEquals(4, phases.size()); // 1 independent + 2 anonymous + synthesis

        // Phase 0: Independent (no context, parallel)
        assertEquals(PhaseType.OPINION, phases.get(0).type());
        assertEquals(ContextScope.NONE, phases.get(0).contextScope());
        assertEquals(TurnOrder.PARALLEL, phases.get(0).turnOrder());

        // Phases 1-2: Anonymous rounds
        for (int i = 1; i <= 2; i++) {
            assertEquals(PhaseType.OPINION, phases.get(i).type());
            assertEquals(ContextScope.ANONYMOUS, phases.get(i).contextScope());
            assertEquals(TurnOrder.PARALLEL, phases.get(i).turnOrder());
        }

        // Last: Synthesis
        assertEquals(PhaseType.SYNTHESIS, phases.getLast().type());
    }

    // --- DEBATE ---

    @Test
    void debate_produces5Phases() {
        List<DiscussionPhase> phases = DiscussionStylePresets.expand(DiscussionStyle.DEBATE, 2);

        assertEquals(5, phases.size());

        // Phase 0: Pro opening (no context)
        assertEquals(PhaseType.ARGUE, phases.get(0).type());
        assertEquals("ROLE:PRO", phases.get(0).participants());
        assertEquals(ContextScope.NONE, phases.get(0).contextScope());

        // Phase 1: Con opening (sees pro)
        assertEquals(PhaseType.ARGUE, phases.get(1).type());
        assertEquals("ROLE:CON", phases.get(1).participants());
        assertEquals(ContextScope.FULL, phases.get(1).contextScope());

        // Phase 2: Pro rebuttal
        assertEquals(PhaseType.REBUTTAL, phases.get(2).type());
        assertEquals("ROLE:PRO", phases.get(2).participants());

        // Phase 3: Con rebuttal
        assertEquals(PhaseType.REBUTTAL, phases.get(3).type());
        assertEquals("ROLE:CON", phases.get(3).participants());

        // Phase 4: Judgment
        assertEquals(PhaseType.SYNTHESIS, phases.get(4).type());
        assertEquals("MODERATOR", phases.get(4).participants());
    }

    // --- CUSTOM / null ---

    @Test
    void custom_returnsEmptyList() {
        List<DiscussionPhase> phases = DiscussionStylePresets.expand(DiscussionStyle.CUSTOM, 2);
        assertTrue(phases.isEmpty());
    }

    @Test
    void null_returnsEmptyList() {
        List<DiscussionPhase> phases = DiscussionStylePresets.expand(null, 2);
        assertTrue(phases.isEmpty());
    }

    // --- Default templates ---

    @Test
    void defaultTemplate_returnsNonNullForAllPhaseTypes() {
        for (PhaseType type : PhaseType.values()) {
            String template = DiscussionStylePresets.defaultTemplate(type);
            assertNotNull(template, "Template should not be null for " + type);
            assertFalse(template.isBlank(), "Template should not be blank for " + type);
        }
    }

    @Test
    void defaultTemplate_synthesisContainsTranscriptVariable() {
        String template = DiscussionStylePresets.defaultTemplate(PhaseType.SYNTHESIS);
        assertTrue(template.contains("${transcript}"), "Synthesis template should reference transcript variable");
    }

    @Test
    void defaultTemplate_critiqueContainsTargetVariables() {
        String template = DiscussionStylePresets.defaultTemplate(PhaseType.CRITIQUE);
        assertTrue(template.contains("${targetName}"), "Critique template should reference targetName");
        assertTrue(template.contains("${targetResponse}"), "Critique template should reference targetResponse");
    }

    // --- DiscussionPhase convenience constructor ---

    @Test
    void discussionPhase_convenienceConstructor_appliesDefaults() {
        var phase = new DiscussionPhase("Test", PhaseType.OPINION);

        assertEquals("Test", phase.name());
        assertEquals(PhaseType.OPINION, phase.type());
        assertEquals("ALL", phase.participants());
        assertEquals(TurnOrder.SEQUENTIAL, phase.turnOrder());
        assertEquals(ContextScope.FULL, phase.contextScope());
        assertFalse(phase.targetEachPeer());
        assertNull(phase.inputTemplate());
        assertEquals(1, phase.repeats());
    }

    // --- Edge cases ---

    @Test
    void roundTable_maxRoundsZero_treatsAsOne() {
        List<DiscussionPhase> phases = DiscussionStylePresets.expand(DiscussionStyle.ROUND_TABLE, 0);

        // Should produce at minimum: 1 opinion + synthesis
        assertFalse(phases.isEmpty());
        assertEquals(PhaseType.OPINION, phases.getFirst().type());
        assertEquals(PhaseType.SYNTHESIS, phases.getLast().type());
    }

    @Test
    void delphi_singleRound_producesOneOpinionAndSynthesis() {
        List<DiscussionPhase> phases = DiscussionStylePresets.expand(DiscussionStyle.DELPHI, 1);

        assertEquals(2, phases.size());
        assertEquals(PhaseType.OPINION, phases.get(0).type());
        assertEquals(ContextScope.NONE, phases.get(0).contextScope());
        assertEquals(PhaseType.SYNTHESIS, phases.get(1).type());
    }
}
