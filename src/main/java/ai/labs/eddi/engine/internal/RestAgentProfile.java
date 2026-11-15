/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentConfigurationCache;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.api.IRestAgentProfile;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.ratings.ConversationRatingPolicy;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.security.ConversationReviewPolicy;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * @see IRestAgentProfile
 */
@ApplicationScoped
public class RestAgentProfile implements IRestAgentProfile {

    private static final Logger LOGGER = Logger.getLogger(RestAgentProfile.class);

    private final ResourceAccessGuard accessGuard;
    private final IDocumentDescriptorStore descriptorStore;
    private final IAgentFactory agentFactory;
    private final ConversationReviewPolicy reviewPolicy;
    private final ConversationRatingPolicy ratingPolicy;

    /**
     * Where the deployed version's {@code appearance} and
     * {@code conversationControls} are read from. Field-injected so the direct
     * constructor and its tests are unaffected; null serves no appearance and the
     * default controls.
     */
    @Inject
    AgentConfigurationCache agentConfigurationCache;

    @Inject
    public RestAgentProfile(ResourceAccessGuard accessGuard, IDocumentDescriptorStore descriptorStore, IAgentFactory agentFactory,
            ConversationReviewPolicy reviewPolicy, ConversationRatingPolicy ratingPolicy) {
        this.accessGuard = accessGuard;
        this.descriptorStore = descriptorStore;
        this.agentFactory = agentFactory;
        this.reviewPolicy = reviewPolicy;
        this.ratingPolicy = ratingPolicy;
    }

    @Override
    public AgentProfile readProfile(String agentId, Deployment.Environment environment) {
        // USE, like starting a conversation: this is what a person about to chat needs.
        accessGuard.requireAgentUseAccess(agentId);

        String name = null;
        String description = null;
        try {
            DocumentDescriptor descriptor = descriptorStore.readCurrentDescriptor(agentId);
            if (descriptor != null) {
                name = descriptor.getName();
                description = descriptor.getDescription();
            }
        } catch (Exception e) {
            LOGGER.debugf("No descriptor for agent %s: %s", sanitize(agentId), e.getMessage());
        }

        String notice = null;
        RatingProfile rating = null;
        AppearanceProfile appearance = null;
        ControlsProfile controls = null;
        try {
            // The notice follows the version a new conversation would run on — the one
            // the review decision is later made against.
            IAgent agent = agentFactory.getLatestReadyAgent(environment == null ? Deployment.Environment.production : environment, agentId);
            if (agent != null) {
                notice = reviewPolicy.noticeFor(agentId, agent.getAgentVersion()).orElse(null);
                // Like the notice, the rating controls follow the version a new
                // conversation would run on; ratings are later checked against the
                // version the conversation actually ran on. A client resuming a
                // conversation takes the controls of that version from
                // GET /agents/{conversationId}/rating instead.
                rating = ratingPolicy.ratingConfigOf(agentId, agent.getAgentVersion()).map(RatingProfile::of).orElse(null);
                // Appearance and controls follow the same version.
                AgentConfiguration configuration = presentationOf(agentId, agent.getAgentVersion());
                if (configuration != null) {
                    appearance = AppearanceProfile.of(configuration.getAppearance());
                    controls = ControlsProfile.of(configuration.getConversationControls());
                }
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not resolve the deployed version of %s: %s", sanitize(agentId), e.getMessage());
        }
        // No configuration: no appearance, and the default controls — rawHtml off.
        return new AgentProfile(agentId, name, description, notice, rating, appearance, controls);
    }

    private AgentConfiguration presentationOf(String agentId, Integer version) {
        if (agentConfigurationCache == null || version == null) {
            return null;
        }
        try {
            return agentConfigurationCache.read(agentId, version);
        } catch (Exception e) {
            LOGGER.debugf("Could not read agent %s v%s for its appearance: %s", sanitize(agentId), version, sanitize(e.getMessage()));
            return null;
        }
    }
}
