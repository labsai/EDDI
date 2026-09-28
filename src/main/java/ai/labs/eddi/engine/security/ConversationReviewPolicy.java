/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.utils.RestUtilities;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Decides whether the caller may read somebody else's conversation with an
 * agent they maintain — see {@link AgentConfiguration.ConversationReview}.
 * <p>
 * Two conditions, both required:
 * <ol>
 * <li>the agent <em>version the conversation ran on</em> had review enabled —
 * so the person chatting was shown the notice, and switching review on later
 * never exposes what was said before;</li>
 * <li>the caller holds {@code EDIT} on the agent now — its owner, anybody it
 * was shared with at edit level, members of the team space it is filed in.
 * Losing that access ends review access with it.</li>
 * </ol>
 */
@ApplicationScoped
public class ConversationReviewPolicy {

    private static final Logger LOGGER = Logger.getLogger(ConversationReviewPolicy.class);

    /** What the person chatting is told when the agent does not say more. */
    public static final String DEFAULT_NOTICE = "Conversations with this agent may be read by the people who maintain it, to improve it.";

    private final IAgentStore agentStore;
    private final ResourceAccessGuard accessGuard;
    private final SecurityIdentity identity;

    /**
     * id:version → review setting. Versions are immutable, so this never goes
     * stale.
     */
    private final Cache<String, Optional<AgentConfiguration.ConversationReview>> reviewByVersion = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(30)).maximumSize(10_000).build();

    @Inject
    public ConversationReviewPolicy(IAgentStore agentStore, ResourceAccessGuard accessGuard, SecurityIdentity identity) {
        this.agentStore = agentStore;
        this.accessGuard = accessGuard;
        this.identity = identity;
    }

    /**
     * The review setting of one agent version, when review is enabled on it.
     */
    public Optional<AgentConfiguration.ConversationReview> reviewOf(String agentId, Integer version) {
        if (agentId == null || version == null) {
            return Optional.empty();
        }
        return reviewByVersion.get(agentId + ":" + version, key -> {
            try {
                AgentConfiguration configuration = agentStore.read(agentId, version);
                AgentConfiguration.ConversationReview review = configuration == null ? null : configuration.getConversationReview();
                return review != null && review.isEnabled() ? Optional.of(review) : Optional.empty();
            } catch (Exception e) {
                LOGGER.debugf("Could not read agent %s v%s for its review setting: %s", sanitize(agentId), version, e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** The notice to show for this agent version, or empty when review is off. */
    public Optional<String> noticeFor(String agentId, Integer version) {
        return reviewOf(agentId, version).map(review -> review.getNotice() == null || review.getNotice().isBlank()
                ? DEFAULT_NOTICE
                : review.getNotice().trim());
    }

    /**
     * Whether the caller may review a conversation that ran on
     * {@code agentResource}.
     *
     * @param agentResource
     *            the conversation's {@code eddi://…/agents/<id>?version=<n>}
     */
    public boolean mayReview(URI agentResource) {
        if (agentResource == null) {
            return false;
        }
        IResourceId id = RestUtilities.extractResourceId(agentResource);
        if (id == null || id.getId() == null) {
            return false;
        }
        if (reviewOf(id.getId(), id.getVersion()).isEmpty()) {
            return false;
        }
        return maintains(id.getId());
    }

    /**
     * Whether the caller holds {@code EDIT} on the agent now.
     * <p>
     * Without workspace enforcement every caller "holds" everything, including a
     * chat-only {@code eddi-user}. There, maintaining an agent means what it always
     * meant: being allowed to author agents at all.
     */
    public boolean maintains(String agentId) {
        if (!accessGuard.settings().isEnforcing()) {
            return accessGuard.isAdmin() || (identity != null && identity.hasRole("eddi-editor"));
        }
        try {
            accessGuard.requireAccess(agentId, AccessLevel.EDIT, "agent");
            return true;
        } catch (ForbiddenException e) {
            return false;
        }
    }
}
