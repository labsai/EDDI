/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.DiscussionStylePresets;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.internal.groups.GroupContextBuilder;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A NEGOTIATION group stored with a prompt-less Arbitration phase runs with the
 * preset's arbitration prompt, whichever client saved it.
 * <p>
 * Before the backend repair, such a phase fell through to the generic SYNTHESIS
 * prompt ("synthesize a balanced conclusion"), so the moderator summarised a
 * deadlock instead of deciding it, and the summary was still recorded as the
 * arbitrated verdict. Only the Manager fixed the stored phase, on its next
 * save.
 */
@DisplayName("NEGOTIATION — a stored prompt-less Arbitration phase gets the arbitration prompt")
class NegotiationArbitrationRepairTest {

    private static final int ARBITRATION = 3;

    private ITemplatingEngine templatingEngine;
    private GroupConversationService service;

    @BeforeEach
    void setUp() {
        templatingEngine = mock(ITemplatingEngine.class);
        service = new GroupConversationService(mock(IAgentGroupStore.class), mock(IGroupConversationStore.class),
                mock(IConversationService.class), mock(IAgentFactory.class), templatingEngine, mock(IJsonSerialization.class),
                new SimpleMeterRegistry(), null, mock(IAgentStore.class), null, null, null, new CallerIdentityContext(null, null),
                "default", 3);
    }

    /**
     * The phases as a pre-repair Manager stored them: the preset, minus the prompt.
     */
    private static List<DiscussionPhase> storedPhasesWithoutArbitrationPrompt() {
        List<DiscussionPhase> phases = new ArrayList<>(DiscussionStylePresets.expand(DiscussionStyle.NEGOTIATION, 2));
        phases.set(ARBITRATION, withTemplate(phases.get(ARBITRATION), null));
        return phases;
    }

    private static DiscussionPhase withTemplate(DiscussionPhase p, String template) {
        return new DiscussionPhase(p.name(), p.type(), p.participants(), p.turnOrder(), p.contextScope(), p.targetEachPeer(), template,
                p.repeats(), p.requiresApproval(), p.convergence(), p.allowAbstention(), p.voteConfig(), p.skipIf());
    }

    private static AgentGroupConfiguration negotiationGroup(List<DiscussionPhase> phases) {
        var config = new AgentGroupConfiguration();
        config.setName("Negotiation");
        config.setStyle(DiscussionStyle.NEGOTIATION);
        config.setMaxRounds(2);
        config.setModeratorAgentId("moderator");
        config.setMembers(List.of(new GroupMember("buyer", "Buyer", 1, null), new GroupMember("seller", "Seller", 2, null)));
        config.setPhases(phases);
        return config;
    }

    @Test
    @DisplayName("the discussion resolves the stored phase with the arbitration prompt")
    void resolvePhasesRestoresThePrompt() {
        var config = negotiationGroup(storedPhasesWithoutArbitrationPrompt());

        List<DiscussionPhase> phases = service.resolvePhases(config);

        assertEquals(DiscussionStylePresets.TEMPLATE_ARBITRATION, phases.get(ARBITRATION).inputTemplate());
        assertEquals(DiscussionStylePresets.expand(DiscussionStyle.NEGOTIATION, 2), phases,
                "apart from the prompt, the stored phases are left exactly as they were");
    }

    /**
     * The end the fix exists for: the moderator is asked to arbitrate. Without the
     * repair this call renders {@code TEMPLATE_SYNTHESIS}.
     */
    @Test
    @DisplayName("the moderator's arbitration turn renders the arbitration prompt, not the generic synthesis")
    void arbitrationTurnRendersTheArbitrationPrompt() throws Exception {
        when(templatingEngine.processTemplate(anyString(), any(), any())).thenReturn("rendered");
        var config = negotiationGroup(storedPhasesWithoutArbitrationPrompt());
        var moderator = new GroupMember("moderator", "Moderator", 3, null);
        var transcript = List.of(new TranscriptEntry("buyer", "Buyer", "My last offer is 90.", 2, "Bargaining",
                TranscriptEntryType.OPINION, Instant.now(), null, null));

        DiscussionPhase arbitration = service.resolvePhases(config).get(ARBITRATION);
        new GroupContextBuilder(templatingEngine).buildPhaseInput(arbitration, moderator, "What price?", transcript, ARBITRATION, null,
                config.getMembers());

        verify(templatingEngine).processTemplate(eq(DiscussionStylePresets.TEMPLATE_ARBITRATION), any(),
                eq(ITemplatingEngine.TemplateMode.TEXT));
    }

    /**
     * A discussion whose phase list was persisted as runtime phases (a facilitator
     * diverged it) before the repair existed resumes on that list, not the config.
     */
    @Test
    @DisplayName("a persisted runtime phase list gets the prompt too")
    void effectivePhasesRepairsRuntimePhases() {
        var config = negotiationGroup(null);
        var gc = new GroupConversation();
        gc.setRuntimePhases(storedPhasesWithoutArbitrationPrompt());

        List<DiscussionPhase> phases = service.effectivePhases(gc, config);

        assertEquals(DiscussionStylePresets.TEMPLATE_ARBITRATION, phases.get(ARBITRATION).inputTemplate());
    }

    @Test
    @DisplayName("an author's own template on the Arbitration phase is kept")
    void authorTemplateKept() {
        List<DiscussionPhase> stored = storedPhasesWithoutArbitrationPrompt();
        stored.set(ARBITRATION, withTemplate(stored.get(ARBITRATION), "Decide: {question}"));

        assertEquals("Decide: {question}", service.resolvePhases(negotiationGroup(stored)).get(ARBITRATION).inputTemplate());
    }

    @Test
    @DisplayName("a phase the preset did not produce is not rewritten")
    void authorsOwnPhaseUntouched() {
        List<DiscussionPhase> stored = storedPhasesWithoutArbitrationPrompt();
        // Same name, but not skipped on agreement: the author's own phase.
        var own = stored.get(ARBITRATION);
        stored.set(ARBITRATION, new DiscussionPhase(own.name(), own.type(), own.participants(), own.turnOrder(), own.contextScope(),
                own.targetEachPeer(), null, own.repeats(), own.requiresApproval(), own.convergence(), own.allowAbstention(),
                own.voteConfig(), null));

        assertSame(stored, DiscussionStylePresets.withNegotiationArbitrationRepaired(DiscussionStyle.NEGOTIATION, stored));
    }

    @Test
    @DisplayName("other styles are never touched")
    void otherStylesUntouched() {
        List<DiscussionPhase> stored = storedPhasesWithoutArbitrationPrompt();

        assertSame(stored, DiscussionStylePresets.withNegotiationArbitrationRepaired(DiscussionStyle.CUSTOM, stored));
        assertSame(stored, DiscussionStylePresets.withNegotiationArbitrationRepaired(null, stored));
    }

    @Test
    @DisplayName("an intact list is returned as it is")
    void intactListReturnedAsIs() {
        List<DiscussionPhase> intact = DiscussionStylePresets.expand(DiscussionStyle.NEGOTIATION, 2);

        assertSame(intact, DiscussionStylePresets.withNegotiationArbitrationRepaired(DiscussionStyle.NEGOTIATION, intact));
        assertEquals(PhaseType.SYNTHESIS, intact.get(ARBITRATION).type());
    }
}
