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
import java.util.Map;

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
        validateVotePhases(groupConfiguration);
        normalizeNonPositiveCostCeiling(groupConfiguration);
        warnOnModeratorlessPhases(groupConfiguration);
        return super.create(groupConfiguration);
    }

    @Override
    @IResourceStore.ConfigurationUpdate
    public Integer update(String id, Integer version, AgentGroupConfiguration groupConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException,
            IResourceStore.ResourceNotFoundException {
        HitlConfigValidation.validate(groupConfiguration.getHitlConfig());
        validateVotePhases(groupConfiguration);
        normalizeNonPositiveCostCeiling(groupConfiguration);
        warnOnModeratorlessPhases(groupConfiguration);
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
     * I14: VOTE phases are validated as HARD errors — the fields are new, so no
     * stored config predating this release can trip them (same rationale as the
     * cascade pricing validation). Ballot independence is the point of the PARALLEL
     * + NONE requirement: enforced structurally at save time, not advised in a
     * prompt at run time.
     */
    static void validateVotePhases(AgentGroupConfiguration groupConfiguration) {
        List<DiscussionPhase> phases = groupConfiguration.getPhases();
        if (phases == null) {
            return;
        }
        for (int i = 0; i < phases.size(); i++) {
            DiscussionPhase phase = phases.get(i);
            if (phase == null || phase.type() != AgentGroupConfiguration.PhaseType.VOTE) {
                continue;
            }
            String path = "phases[" + i + "] (VOTE)";
            if (phase.turnOrder() != AgentGroupConfiguration.TurnOrder.PARALLEL) {
                throw new IllegalArgumentException(path + " must use turnOrder PARALLEL — ballots are cast blind against the "
                        + "pre-fan-out snapshot; a sequential vote lets later ballots read earlier ones");
            }
            if (phase.contextScope() != null && phase.contextScope() != AgentGroupConfiguration.ContextScope.NONE) {
                throw new IllegalArgumentException(path + " must use contextScope NONE — ballot independence is enforced "
                        + "structurally, not advised in the prompt");
            }
            if (phase.targetEachPeer()) {
                throw new IllegalArgumentException(path + " must not use targetEachPeer");
            }
            var voteConfig = phase.voteConfig();
            if (voteConfig == null) {
                continue;
            }
            if (voteConfig.optionsSource() == AgentGroupConfiguration.OptionsSource.EXPLICIT && voteConfig.options().size() < 2) {
                throw new IllegalArgumentException(path + " with optionsSource EXPLICIT needs at least 2 options");
            }
            if (voteConfig.tiePolicy() == AgentGroupConfiguration.TiePolicy.HUMAN_DECIDES) {
                throw new IllegalArgumentException(path + ".tiePolicy HUMAN_DECIDES needs human group members (I6), which are "
                        + "not available yet — use MODERATOR_DECIDES or NO_DECISION");
            }
            for (Map.Entry<String, Double> weight : voteConfig.weights().entrySet()) {
                // isFinite: NaN passes every < comparison and would poison the
                // weighted totals; infinity would decide every vote alone.
                if (weight.getValue() == null || !Double.isFinite(weight.getValue()) || weight.getValue() < 0) {
                    throw new IllegalArgumentException(path + ".weights['" + weight.getKey() + "'] must be finite and >= 0");
                }
            }
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
