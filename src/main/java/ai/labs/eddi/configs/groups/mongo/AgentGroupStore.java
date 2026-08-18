/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.hitl.HitlConfigValidation;
import ai.labs.eddi.configs.groups.ArtifactValidators;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.DiscussionStylePresets;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.utils.LogSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        validateHumanMembers(groupConfiguration);
        validateFacilitator(groupConfiguration);
        ArtifactValidators.requireValidSpecs(groupConfiguration.getArtifactConfig());
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
        validateVotePhases(groupConfiguration);
        validateHumanMembers(groupConfiguration);
        validateFacilitator(groupConfiguration);
        ArtifactValidators.requireValidSpecs(groupConfiguration.getArtifactConfig());
        normalizeNonPositiveCostCeiling(groupConfiguration);
        warnOnModeratorlessPhases(groupConfiguration);
        warnOnSummarizerlessWindow(groupConfiguration);
        return super.update(id, version, groupConfiguration);
    }

    /**
     * I6 save-time matrix for HUMAN members. Hard rejections
     * ({@link IllegalArgumentException}, {@code HitlConfigValidation}'s contract)
     * are safe here in a way {@link #warnOnModeratorlessPhases} could not be: no
     * pre-existing document can contain {@code MemberType.HUMAN}, so there is no
     * legacy config a rejection could strand.
     * <ul>
     * <li>a HUMAN member must carry a {@code displayName} — a paused discussion
     * must be able to say WHO it is waiting on;</li>
     * <li>groups with HUMAN members must not run task-force phases
     * (PLAN/EXECUTE/VERIFY assign work on agent-latency math and pause inside wave
     * workers) nor {@code targetEachPeer} phases (a human on both axes of an
     * N×(N-1) round would owe up to 2(N-1) pauses per repeat, and the flat speaker
     * bookmark has no (speaker,target) coordinate) — preset-expanded like
     * {@link #moderatorlessPhaseNames}, or the check is inert for preset-style
     * groups;</li>
     * <li>a group containing HUMAN members may not be USED as a nested GROUP member
     * (one level deep here; {@code MemberTurnExecutor} carries the runtime backstop
     * for configs edited afterwards);</li>
     * <li>{@code humanMemberConfig.turnTimeout} must parse as an ISO-8601
     * duration;</li>
     * <li>a HUMAN moderator is allowed but warned about — every synthesis then
     * waits on a person.</li>
     * </ul>
     */
    void validateHumanMembers(AgentGroupConfiguration config) throws IResourceStore.ResourceStoreException {
        List<String> problems = humanMemberProblems(config);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        // "members": null in the JSON reaches here as a literal null list — the
        // pure helper already tolerates it; there is nothing human to validate.
        List<AgentGroupConfiguration.GroupMember> members = config.getMembers() != null ? config.getMembers() : List.of();
        // Nested check needs the store — kept out of the pure helper.
        for (var member : members) {
            if (member != null && member.memberType() == AgentGroupConfiguration.MemberType.GROUP) {
                AgentGroupConfiguration child = readChildConfig(member.agentId());
                if (child != null && hasHumanMembers(child)) {
                    throw new IllegalArgumentException(
                            "members['" + member.agentId() + "'] is a nested group that contains HUMAN members — "
                                    + "human turns cannot pause a nested discussion (I6 v1); remove the human from the "
                                    + "child group or flatten the hierarchy");
                }
            }
        }
        String moderator = config.getModeratorAgentId();
        if (moderator != null && members.stream()
                .anyMatch(m -> m != null && m.memberType() == AgentGroupConfiguration.MemberType.HUMAN
                        && moderator.equals(m.agentId()))) {
            LOGGER.warnf("Group '%s' names HUMAN member '%s' as moderator — every synthesis phase will pause and wait "
                    + "for their input", LogSanitizer.sanitize(config.getName()), LogSanitizer.sanitize(moderator));
        }
    }

    /**
     * The pure, assertable part of the I6 matrix (same split as
     * {@link #moderatorlessPhaseNames}): every problem with this config's HUMAN
     * members that needs no store access. Empty list = valid.
     */
    public static List<String> humanMemberProblems(AgentGroupConfiguration config) {
        List<AgentGroupConfiguration.GroupMember> humans = config.getMembers() == null
                ? List.of()
                : config.getMembers().stream()
                        .filter(m -> m != null && m.memberType() == AgentGroupConfiguration.MemberType.HUMAN)
                        .toList();
        List<String> problems = new ArrayList<>();
        for (var human : humans) {
            if (human.displayName() == null || human.displayName().isBlank()) {
                problems.add("HUMAN member '" + human.agentId() + "' needs a displayName");
            }
            if (human.agentId() == null || human.agentId().isBlank()) {
                problems.add("a HUMAN member needs an agentId carrying the human's principal id");
            }
        }
        if (!humans.isEmpty()) {
            // Preset-expanded, or the check is inert for preset-style groups.
            List<DiscussionPhase> phases = config.getPhases();
            if (phases == null || phases.isEmpty()) {
                DiscussionStyle style = config.getStyle() != null ? config.getStyle() : DiscussionStyle.ROUND_TABLE;
                phases = DiscussionStylePresets.expand(style, config.getMaxRounds());
            }
            boolean taskPhases = phases.stream().filter(Objects::nonNull).anyMatch(
                    p -> p.type() == AgentGroupConfiguration.PhaseType.PLAN
                            || p.type() == AgentGroupConfiguration.PhaseType.EXECUTE
                            || p.type() == AgentGroupConfiguration.PhaseType.VERIFY);
            if (taskPhases) {
                problems.add("HUMAN members cannot join task-force groups (PLAN/EXECUTE/VERIFY phases) — "
                        + "task waves assign and time work on agent latencies (I6 v1)");
            }
            boolean peerPhases = phases.stream().filter(Objects::nonNull).anyMatch(DiscussionPhase::targetEachPeer);
            if (peerPhases) {
                problems.add("HUMAN members cannot join groups with targetEachPeer phases — a human would owe one "
                        + "authored critique per peer AND be a target, multiplying pauses (I6 v1)");
            }
        }
        var humanConfig = config.getHumanMemberConfig();
        if (humanConfig != null && humanConfig.turnTimeout() != null && !humanConfig.turnTimeout().isBlank()) {
            try {
                Duration parsed = Duration.parse(humanConfig.turnTimeout());
                // Duration.parse accepts PT0S and PT-4H; both would arm a timeout
                // that fires effectively immediately (past-due clamps to the grace
                // window), silently skipping every human turn.
                if (parsed.isZero() || parsed.isNegative()) {
                    problems.add("humanMemberConfig.turnTimeout must be a positive duration, not '"
                            + humanConfig.turnTimeout() + "'");
                }
            } catch (Exception e) {
                problems.add("humanMemberConfig.turnTimeout must be an ISO-8601 duration (e.g. PT4H), not '"
                        + humanConfig.turnTimeout() + "'");
            }
        }
        return problems;
    }

    /** True if the config lists at least one HUMAN member. */
    static boolean hasHumanMembers(AgentGroupConfiguration config) {
        return config.getMembers() != null && config.getMembers().stream()
                .anyMatch(m -> m != null && m.memberType() == AgentGroupConfiguration.MemberType.HUMAN);
    }

    /** Latest version of a (possible) child group config, or null if unreadable. */
    private AgentGroupConfiguration readChildConfig(String groupId) {
        try {
            IResourceStore.IResourceId resId = getCurrentResourceId(groupId);
            return resId != null ? read(groupId, resId.getVersion()) : null;
        } catch (Exception e) {
            // Deployment-order tolerance: an unreadable/absent child cannot block
            // the parent save; the runtime backstop covers it.
            LOGGER.debugf("Nested-group human check could not read child '%s': %s", groupId, e.getMessage());
            return null;
        }
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
    public static void validateVotePhases(AgentGroupConfiguration groupConfiguration) {
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
                // HUMAN members themselves DO ship (see the I6 matrix above, which
                // validates them). What is missing is the resume machinery a tie
                // break needs: a tally that stops mid-phase has no pause to re-enter.
                throw new IllegalArgumentException(path + ".tiePolicy HUMAN_DECIDES is not supported yet — breaking a tie needs a "
                        + "resume path that a paused VOTE phase does not have. Use MODERATOR_DECIDES or NO_DECISION");
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
     * I12 save-time checks for {@code facilitator}. Hard rejections, same safety
     * argument as {@link #validateVotePhases}: no legacy document can contain the
     * new config block, so a throw can never brick an existing group.
     * <p>
     * The END_PHASE/EXTEND_PHASE-with-EACH_PHASE combination is rejected rather
     * than warned: both moves act on a phase's remaining repeats, and a
     * phase-boundary checkpoint has none — every attempt would be rejected at
     * runtime, which is a config that can only ever produce noise.
     */
    public static void validateFacilitator(AgentGroupConfiguration config) {
        var facilitator = config.getFacilitator();
        if (facilitator == null || !facilitator.enabled()) {
            return;
        }
        if (facilitator.agentId() == null || facilitator.agentId().isBlank()) {
            throw new IllegalArgumentException("facilitator.agentId is required when the facilitator is enabled");
        }
        var moves = facilitator.allowedMoves();
        boolean midPhaseMoves = moves.contains(AgentGroupConfiguration.FacilitatorMove.END_PHASE)
                || moves.contains(AgentGroupConfiguration.FacilitatorMove.EXTEND_PHASE);
        if (midPhaseMoves && facilitator.checkAfter() == AgentGroupConfiguration.FacilitatorCheckpoint.EACH_PHASE) {
            throw new IllegalArgumentException("facilitator.allowedMoves contains END_PHASE/EXTEND_PHASE, which act "
                    + "mid-phase — set checkAfter to EACH_REPEAT, or drop those moves");
        }
        if (moves.contains(AgentGroupConfiguration.FacilitatorMove.ESCALATE_HUMAN)
                && (facilitator.escalateTo() == null || facilitator.escalateTo().isBlank())) {
            throw new IllegalArgumentException("facilitator.escalateTo is required when ESCALATE_HUMAN is an allowed move "
                    + "— an escalation must name the principal it waits on");
        }
        if (facilitator.maxMovesPerDiscussion() > 100) {
            throw new IllegalArgumentException("facilitator.maxMovesPerDiscussion must be at most 100 — the facilitator "
                    + "is a bounded intervention mechanism, not an orchestrator");
        }
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
        if (window.llmProvider() == null || window.llmModel() == null) {
            LOGGER.warnf("Group '%s' enables contextWindow summarization but names no llmProvider/llmModel — "
                    + "overflow will fall back to a plain truncation marker", LogSanitizer.sanitize(groupConfiguration.getName()));
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
