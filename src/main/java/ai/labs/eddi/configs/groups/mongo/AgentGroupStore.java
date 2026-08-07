/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.hitl.HitlConfigValidation;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.DiscussionStylePresets;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * DB-agnostic store for group configurations. Extends
 * {@link AbstractResourceStore} which delegates to either MongoDB or PostgreSQL
 * via {@link IResourceStorageFactory}.
 *
 * @author ginccc
 */
@ApplicationScoped
public class AgentGroupStore extends AbstractResourceStore<AgentGroupConfiguration> implements IAgentGroupStore {

    private static final Logger LOGGER = Logger.getLogger(AgentGroupStore.class);

    @Inject
    public AgentGroupStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        super(storageFactory, "groups", documentBuilder, AgentGroupConfiguration.class);
    }

    @Override
    public IResourceStore.IResourceId create(AgentGroupConfiguration groupConfiguration)
            throws IResourceStore.ResourceStoreException {
        HitlConfigValidation.validate(groupConfiguration.getHitlConfig());
        normalizeNonPositiveCostCeiling(groupConfiguration);
        warnOnModeratorlessPhases(groupConfiguration);
        warnOnSummarizerlessWindow(groupConfiguration);
        return super.create(groupConfiguration);
    }

    @Override
    @IResourceStore.ConfigurationUpdate
    public Integer update(String id, Integer version, AgentGroupConfiguration groupConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException,
            IResourceStore.ResourceNotFoundException {
        HitlConfigValidation.validate(groupConfiguration.getHitlConfig());
        normalizeNonPositiveCostCeiling(groupConfiguration);
        warnOnModeratorlessPhases(groupConfiguration);
        warnOnSummarizerlessWindow(groupConfiguration);
        return super.update(id, version, groupConfiguration);
    }

    /**
     * I3: a phase restricted to {@code participants: "MODERATOR"} in a group that
     * names no {@code moderatorAgentId} cannot run as written. The engine picks the
     * first member by speaking order and says so at runtime, but that is a silent
     * substitution the config author never asked for — worth telling them at the
     * moment they save it, when they can still fix it.
     * <p>
     * A warning, deliberately not a rejection: groups saved before this check
     * exists have to keep loading and saving through the same API that stored them.
     * Same warn-rather-than-reject shape as
     * {@link #normalizeNonPositiveCostCeiling}, minus the mutation — there is no
     * safe value to substitute for "which agent should moderate".
     */
    private void warnOnModeratorlessPhases(AgentGroupConfiguration groupConfiguration) {
        moderatorlessPhaseNames(groupConfiguration).forEach(name -> LOGGER.warnf(
                "Group '%s' phase '%s' is restricted to MODERATOR but the group names no moderatorAgentId — "
                        + "the first member by speakingOrder will stand in",
                groupConfiguration.getName(), name));
    }

    /**
     * The phases this config restricts to a moderator it does not have. Separated
     * from the logging so the decision is assertable — a log-only method is a
     * decision nothing can pin.
     */
    static List<String> moderatorlessPhaseNames(AgentGroupConfiguration groupConfiguration) {
        String moderator = groupConfiguration.getModeratorAgentId();
        if (moderator != null && !moderator.isBlank()) {
            return List.of();
        }
        // Checking getPhases() alone made this inert for exactly the configs that
        // need it: a preset-style group stores NO phases, the engine expands the
        // preset at discussion time, and every one of the six presets ends in a
        // participants="MODERATOR" phase. Mirror
        // GroupConversationService.resolvePhases.
        List<DiscussionPhase> phases = groupConfiguration.getPhases();
        if (phases == null || phases.isEmpty()) {
            DiscussionStyle style = groupConfiguration.getStyle() != null ? groupConfiguration.getStyle() : DiscussionStyle.ROUND_TABLE;
            phases = DiscussionStylePresets.expand(style, groupConfiguration.getMaxRounds());
        }
        return phases.stream()
                .filter(p -> p != null && "MODERATOR".equalsIgnoreCase(p.participants()))
                .map(DiscussionPhase::name)
                .toList();
    }

    /**
     * I9: a window that asks for summarization but names no summarizer model will
     * silently degrade to the plain truncation marker at discussion time. Worth
     * telling the author at save time, when they can still add
     * {@code llmProvider}/{@code llmModel}. A warning, not a rejection — the
     * truncation fallback is well-defined behaviour, same warn-not-reject shape as
     * {@link #warnOnModeratorlessPhases}.
     */
    private void warnOnSummarizerlessWindow(AgentGroupConfiguration groupConfiguration) {
        var window = groupConfiguration.getContextWindow();
        if (window == null || !window.enabled() || !Boolean.TRUE.equals(window.summarizeOverflow())) {
            return;
        }
        if (window.llmProvider() == null || window.llmProvider().isBlank() || window.llmModel() == null || window.llmModel().isBlank()) {
            LOGGER.warnf("Group '%s' enables contextWindow summarization but names no llmProvider/llmModel — "
                    + "overflow will fall back to a plain truncation marker", groupConfiguration.getName());
        }
    }

    /**
     * I1: a {@code maxCostPerDiscussion} of zero or less would stop the very first
     * turn of every discussion this group ever runs — almost certainly a mistake (a
     * placeholder, or a unit mix-up) rather than a deliberate "never run me".
     * Coalesced to {@code null} (unlimited) with a warning rather than rejected, so
     * an existing config with a bad value keeps loading and saving instead of
     * becoming unfixable through the same API that stored it. Same warn-and-mutate
     * shape as {@code RagStore.normalizeLegacyChunkStrategy}.
     */
    private void normalizeNonPositiveCostCeiling(AgentGroupConfiguration groupConfiguration) {
        var protocol = groupConfiguration.getProtocol();
        if (protocol == null || protocol.maxCostPerDiscussion() == null || protocol.maxCostPerDiscussion() > 0) {
            return;
        }
        LOGGER.warnf("Group '%s' has maxCostPerDiscussion=%s (not positive) — treating as unlimited",
                groupConfiguration.getName(), protocol.maxCostPerDiscussion());
        groupConfiguration.setProtocol(new AgentGroupConfiguration.ProtocolConfig(
                protocol.agentTimeoutSeconds(), protocol.onAgentFailure(), protocol.maxRetries(),
                protocol.onMemberUnavailable(), protocol.maxTurns(), null, protocol.onCostExceeded()));
    }
}
