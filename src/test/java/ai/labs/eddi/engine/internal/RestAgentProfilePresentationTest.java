/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentConfigurationCache;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentAppearance;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.ConversationControls;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.api.IRestAgentProfile.AgentProfile;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.ratings.ConversationRatingPolicy;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.security.ConversationReviewPolicy;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /agents/{agentId}/profile} serves the deployed version's
 * {@code appearance} and resolved {@code conversationControls}, with
 * {@code rawHtml} off unless the agent switched it on.
 */
@DisplayName("RestAgentProfile — appearance and controls")
class RestAgentProfilePresentationTest {

    private static final String AGENT = "agent-1";

    private ResourceAccessGuard guard;
    private AgentConfigurationCache cache;
    private RestAgentProfile profile;

    @BeforeEach
    void setUp() throws Exception {
        guard = mock(ResourceAccessGuard.class);
        cache = mock(AgentConfigurationCache.class);
        var factory = mock(IAgentFactory.class);
        var deployed = mock(IAgent.class);
        when(deployed.getAgentVersion()).thenReturn(3);
        when(factory.getLatestReadyAgent(Deployment.Environment.production, AGENT)).thenReturn(deployed);
        var reviewPolicy = mock(ConversationReviewPolicy.class);
        when(reviewPolicy.noticeFor(anyString(), any())).thenReturn(Optional.empty());
        profile = new RestAgentProfile(guard, mock(IDocumentDescriptorStore.class), factory, reviewPolicy,
                new ConversationRatingPolicy(mock(IAgentStore.class)));
        profile.agentConfigurationCache = cache;
    }

    @Test
    @DisplayName("serves the appearance and the controls of the deployed version")
    void servesPresentation() throws Exception {
        var appearance = new AgentAppearance();
        appearance.setDisplayName("Ava");
        appearance.setAvatar("https://cdn.example.com/ava.png");
        appearance.setAccentColor("#4f46e5");
        appearance.setStarters(List.of("Track my order", "Talk to a human"));
        var controls = new ConversationControls();
        controls.setUndo(false);
        controls.setRawHtml(true);
        var configuration = new AgentConfiguration();
        configuration.setAppearance(appearance);
        configuration.setConversationControls(controls);
        when(cache.read(AGENT, 3)).thenReturn(configuration);

        AgentProfile result = profile.readProfile(AGENT, Deployment.Environment.production);

        verify(guard).requireAgentUseAccess(AGENT);
        assertEquals("Ava", result.appearance().displayName());
        assertEquals("https://cdn.example.com/ava.png", result.appearance().avatar());
        assertEquals("#4f46e5", result.appearance().accentColor());
        assertEquals(List.of("Track my order", "Talk to a human"), result.appearance().starters());
        assertFalse(result.conversationControls().undo());
        assertTrue(result.conversationControls().redo(), "an absent switch keeps the control");
        assertTrue(result.conversationControls().rawHtml());
    }

    @Test
    @DisplayName("rawHtml is false by default — with no controls, and with controls that leave it out")
    void rawHtmlDefaultsFalse() throws Exception {
        when(cache.read(AGENT, 3)).thenReturn(new AgentConfiguration());

        AgentProfile none = profile.readProfile(AGENT, Deployment.Environment.production);
        assertNull(none.appearance());
        assertFalse(none.conversationControls().rawHtml());
        assertTrue(none.conversationControls().undo() && none.conversationControls().streaming() && none.conversationControls().attachments());

        var configuration = new AgentConfiguration();
        var controls = new ConversationControls();
        controls.setStreaming(false);
        configuration.setConversationControls(controls);
        when(cache.read(AGENT, 3)).thenReturn(configuration);

        AgentProfile partial = profile.readProfile(AGENT, Deployment.Environment.production);
        assertFalse(partial.conversationControls().rawHtml());
        assertFalse(partial.conversationControls().streaming());
    }

    @Test
    @DisplayName("an undeployed agent still gets the default controls, rawHtml off")
    void undeployed() {
        var factory = mock(IAgentFactory.class);
        var reviewPolicy = mock(ConversationReviewPolicy.class);
        var undeployed = new RestAgentProfile(guard, mock(IDocumentDescriptorStore.class), factory, reviewPolicy,
                new ConversationRatingPolicy(mock(IAgentStore.class)));

        AgentProfile result = undeployed.readProfile(AGENT, null);

        assertNull(result.appearance());
        assertFalse(result.conversationControls().rawHtml());
    }

    @Test
    @DisplayName("the JSON a client reads names every field")
    void json() throws Exception {
        when(cache.read(AGENT, 3)).thenReturn(new AgentConfiguration());

        String json = new ObjectMapper().writeValueAsString(profile.readProfile(AGENT, Deployment.Environment.production));

        assertTrue(json.contains("\"conversationControls\":{\"undo\":true,\"redo\":true,\"attachments\":true,\"rating\":true,"
                + "\"quickReplies\":true,\"streaming\":true,\"rawHtml\":false}"), json);
        assertTrue(json.contains("\"appearance\":null"), json);
    }
}
