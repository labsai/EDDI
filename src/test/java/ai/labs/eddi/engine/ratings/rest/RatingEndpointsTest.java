/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings.rest;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.api.IRestAgentProfile.AgentProfile;
import ai.labs.eddi.engine.api.IRestConversationRating;
import ai.labs.eddi.engine.api.IRestConversationRatingAdmin;
import ai.labs.eddi.engine.internal.RestAgentProfile;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.ratings.ConversationRatingPolicy;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.security.ConversationReviewPolicy;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("rating endpoints: roles, paths and the chat profile")
class RatingEndpointsTest {

    private static final String AGENT = "6aba70011a25f8be7920e055";

    private static Set<String> roles(Class<?> type) {
        return Set.of(type.getAnnotation(RolesAllowed.class).value());
    }

    @Test
    @DisplayName("the person chatting rates with the chat roles; the analysis is admin and editor only, no viewer")
    void roles() {
        assertEquals(Set.of("eddi-admin", "eddi-editor", "eddi-user"), roles(IRestConversationRating.class));
        assertEquals(Set.of("eddi-admin", "eddi-editor"), roles(IRestConversationRatingAdmin.class),
                "no role hierarchy in REST, and eddi-viewer is deliberately excluded");
        assertEquals("/agents", IRestConversationRating.class.getAnnotation(Path.class).value());
        assertEquals("/ratings", IRestConversationRatingAdmin.class.getAnnotation(Path.class).value());
    }

    @Test
    @DisplayName("the profile carries the rating controls of the deployed version, and nothing when ratings are off")
    void profile() throws Exception {
        IAgentStore agentStore = mock(IAgentStore.class);
        AgentConfiguration rated = new AgentConfiguration();
        RatingConfig rating = new RatingConfig();
        rating.setEnabled(true);
        rated.setRatingConfig(rating);
        when(agentStore.read(AGENT, 2)).thenReturn(rated);
        when(agentStore.read(AGENT, 1)).thenReturn(new AgentConfiguration());

        ResourceAccessGuard guard = mock(ResourceAccessGuard.class);
        IAgentFactory factory = mock(IAgentFactory.class);
        IAgent deployed = mock(IAgent.class);
        when(factory.getLatestReadyAgent(Deployment.Environment.production, AGENT)).thenReturn(deployed);
        RestAgentProfile rest = new RestAgentProfile(guard, mock(IDocumentDescriptorStore.class), factory, mock(ConversationReviewPolicy.class),
                new ConversationRatingPolicy(agentStore));

        when(deployed.getAgentVersion()).thenReturn(2);
        AgentProfile on = rest.readProfile(AGENT, Deployment.Environment.production);
        assertNotNull(on.rating());
        assertEquals("THUMBS", on.rating().scale());

        when(deployed.getAgentVersion()).thenReturn(1);
        assertNull(rest.readProfile(AGENT, Deployment.Environment.production).rating());
        verify(guard, times(2)).requireAgentUseAccess(AGENT);
    }
}
