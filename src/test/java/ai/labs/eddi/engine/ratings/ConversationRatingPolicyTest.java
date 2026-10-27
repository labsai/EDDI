/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("the rating setting of an agent version")
class ConversationRatingPolicyTest {

    private static final String AGENT = "6aba70011a25f8be7920e055";

    private IAgentStore agentStore;
    private ConversationRatingPolicy policy;

    @BeforeEach
    void setUp() {
        agentStore = mock(IAgentStore.class);
        policy = new ConversationRatingPolicy(agentStore);
    }

    private static AgentConfiguration withRating(boolean enabled) {
        AgentConfiguration configuration = new AgentConfiguration();
        RatingConfig rating = new RatingConfig();
        rating.setEnabled(enabled);
        configuration.setRatingConfig(rating);
        return configuration;
    }

    @Test
    @DisplayName("is read per version: switching ratings on in v2 does not open v1 conversations")
    void perVersion() throws Exception {
        when(agentStore.read(AGENT, 1)).thenReturn(new AgentConfiguration());
        when(agentStore.read(AGENT, 2)).thenReturn(withRating(true));

        assertFalse(policy.ratingConfigOf(AGENT, 1).isPresent(), "absent ratingConfig means off");
        assertTrue(policy.ratingConfigOf(AGENT, 2).isPresent());
    }

    @Test
    @DisplayName("a ratingConfig without enabled=true is off")
    void enabledFlag() throws Exception {
        when(agentStore.read(AGENT, 1)).thenReturn(withRating(false));

        assertFalse(policy.ratingConfigOf(AGENT, 1).isPresent());
        assertFalse(new RatingConfig().isEnabled(), "the default is off");
    }

    @Test
    @DisplayName("is cached per version")
    void cached() throws Exception {
        when(agentStore.read(AGENT, 2)).thenReturn(withRating(true));

        policy.ratingConfigOf(AGENT, 2);
        policy.ratingConfigOf(AGENT, 2);

        verify(agentStore, times(1)).read(AGENT, 2);
    }

    @Test
    @DisplayName("a store failure denies, and is not cached")
    void failureNotCached() throws Exception {
        when(agentStore.read(AGENT, 2)).thenThrow(new ResourceStoreException("blink")).thenReturn(withRating(true));

        assertFalse(policy.ratingConfigOf(AGENT, 2).isPresent());
        assertTrue(policy.ratingConfigOf(AGENT, 2).isPresent(), "the next request reads the store again");
    }

    @Test
    @DisplayName("an unknown agent or version is off")
    void unknown() {
        assertFalse(policy.ratingConfigOf(null, 1).isPresent());
        assertFalse(policy.ratingConfigOf(AGENT, null).isPresent());
    }
}
