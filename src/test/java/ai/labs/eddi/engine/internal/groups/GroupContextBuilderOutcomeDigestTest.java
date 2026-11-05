/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("GroupContextBuilder — outcome digest in the RETRO phase")
class GroupContextBuilderOutcomeDigestTest {

    private ITemplatingEngine templating;
    private GroupContextBuilder builder;
    private GroupConversation gc;

    @BeforeEach
    void setUp() throws Exception {
        templating = mock(ITemplatingEngine.class);
        when(templating.processTemplate(any(), any(), eq(ITemplatingEngine.TemplateMode.TEXT))).thenReturn("RENDERED");
        builder = new GroupContextBuilder(templating);
        gc = new GroupConversation();
        gc.setGroupId("g1");
    }

    private String build(PhaseType type, GroupConversation conversation) {
        var phase = new DiscussionPhase("P", type, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
        return builder.buildPhaseInput(phase, new GroupMember("a", "A", 1, null), "Q?", List.of(), 1, null, null, null, conversation, null);
    }

    @Test
    void aRetroQuotesTheTeamsOutcomesAfterTheRenderedTemplate() {
        builder.setOutcomeDigestProvider(groupId -> "\n[digest of " + groupId + "]");
        assertEquals("RENDERED\n[digest of g1]", build(PhaseType.RETRO, gc));
    }

    @Test
    void otherPhasesNeverGetThem() {
        builder.setOutcomeDigestProvider(groupId -> "\n[digest]");
        assertEquals("RENDERED", build(PhaseType.OPINION, gc));
        assertEquals("RENDERED", build(PhaseType.SYNTHESIS, gc));
    }

    @Test
    void nothingWiredNothingQuoted() {
        assertEquals("RENDERED", build(PhaseType.RETRO, gc));
    }

    @Test
    void aFailingOrNullProviderNeverFailsTheRetro() {
        builder.setOutcomeDigestProvider(groupId -> {
            throw new IllegalStateException("store down");
        });
        assertEquals("RENDERED", build(PhaseType.RETRO, gc));
        builder.setOutcomeDigestProvider(groupId -> null);
        assertEquals("RENDERED", build(PhaseType.RETRO, gc));
    }

    @Test
    void aConversationWithoutAGroupIdAsksNothing() {
        builder.setOutcomeDigestProvider(groupId -> {
            throw new AssertionError("must not be called");
        });
        assertEquals("RENDERED", build(PhaseType.RETRO, new GroupConversation()));
        assertEquals("RENDERED", build(PhaseType.RETRO, null));
    }
}
