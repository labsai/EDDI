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
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
        validateMembersAndLimits(groupConfiguration);
        validateVotePhases(groupConfiguration);
        validateHumanMembers(groupConfiguration);
        validateFacilitator(groupConfiguration);
        ArtifactValidators.requireValidSpecs(groupConfiguration.getArtifactConfig());
        normalizeNonPositiveCostCeiling(groupConfiguration);
        warnCostCeilingNeedsPricedMembers(groupConfiguration);
        warnOnModeratorlessPhases(groupConfiguration);
        warnOnSummarizerlessWindow(groupConfiguration);
        noteDebateVerdictSynthesis(groupConfiguration);
        noteBuiltInToolPrerequisite(groupConfiguration);
        return super.create(groupConfiguration);
    }

    @Override
    @IResourceStore.ConfigurationUpdate
    public Integer update(String id, Integer version, AgentGroupConfiguration groupConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException,
            IResourceStore.ResourceNotFoundException {
        HitlConfigValidation.validate(groupConfiguration.getHitlConfig());
        validateMembersAndLimits(groupConfiguration);
        rejectNestingCycle(id, groupConfiguration);
        validateVotePhases(groupConfiguration);
        validateHumanMembers(groupConfiguration);
        validateFacilitator(groupConfiguration);
        ArtifactValidators.requireValidSpecs(groupConfiguration.getArtifactConfig());
        normalizeNonPositiveCostCeiling(groupConfiguration);
        warnCostCeilingNeedsPricedMembers(groupConfiguration);
        warnOnModeratorlessPhases(groupConfiguration);
        warnOnSummarizerlessWindow(groupConfiguration);
        noteDebateVerdictSynthesis(groupConfiguration);
        noteBuiltInToolPrerequisite(groupConfiguration);
        return super.update(id, version, groupConfiguration);
    }

    /**
     * B15: values that saved without complaint and then misbehaved at discussion
     * time — a member with no agent to call, negative turn/retry/timeout budgets,
     * negative dynamic-agent caps and repeat counts, and preset styles missing the
     * roles they are built on. All are new-field-shaped mistakes rather than legacy
     * data, so they are hard errors (same rationale as
     * {@link #validateVotePhases}).
     */
    static void validateMembersAndLimits(AgentGroupConfiguration config) {
        List<String> problems = memberAndLimitProblems(config);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
    }

    /** The assertable half of {@link #validateMembersAndLimits}. Empty = valid. */
    public static List<String> memberAndLimitProblems(AgentGroupConfiguration config) {
        List<String> problems = new ArrayList<>();
        List<AgentGroupConfiguration.GroupMember> members = config.getMembers() != null ? config.getMembers() : List.of();
        for (int i = 0; i < members.size(); i++) {
            var member = members.get(i);
            if (member == null) {
                problems.add("members[" + i + "] is null");
            } else if (member.memberType() != AgentGroupConfiguration.MemberType.HUMAN
                    && (member.agentId() == null || member.agentId().isBlank())) {
                problems.add("members[" + i + "] needs an agentId (the " + (member.memberType() == AgentGroupConfiguration.MemberType.GROUP
                        ? "group"
                        : "agent") + " it stands for)");
            }
        }
        if (config.getMaxRounds() < 0) {
            problems.add("maxRounds must not be negative");
        }
        var protocol = config.getProtocol();
        if (protocol != null) {
            if (protocol.agentTimeoutSeconds() < 0) {
                problems.add("protocol.agentTimeoutSeconds must not be negative");
            }
            if (protocol.maxRetries() < 0) {
                problems.add("protocol.maxRetries must not be negative");
            }
            if (protocol.maxTurns() < 0) {
                problems.add("protocol.maxTurns must not be negative (0 selects the engine default)");
            }
        }
        var dynamic = config.getDynamicAgents();
        if (dynamic != null) {
            if (dynamic.getMaxCreatedAgentsPerDiscussion() < 0) {
                problems.add("dynamicAgents.maxCreatedAgentsPerDiscussion must not be negative");
            }
            if (dynamic.getMaxRecruitedAgentsPerDiscussion() < 0) {
                problems.add("dynamicAgents.maxRecruitedAgentsPerDiscussion must not be negative");
            }
            if (dynamic.getMaxDelegationsPerTask() < 0) {
                problems.add("dynamicAgents.maxDelegationsPerTask must not be negative");
            }
            if (dynamic.getMaxDelegationDepth() < 0) {
                problems.add("dynamicAgents.maxDelegationDepth must not be negative");
            }
        }
        List<DiscussionPhase> phases = config.getPhases() != null ? config.getPhases() : List.of();
        for (int i = 0; i < phases.size(); i++) {
            if (phases.get(i) != null && phases.get(i).repeats() < 0) {
                problems.add("phases[" + i + "].repeats must not be negative");
            }
        }
        problems.addAll(presetRoleProblems(config));
        return problems;
    }

    /**
     * W6: the DEBATE and DEVIL_ADVOCATE presets address members by role
     * ({@code ROLE:PRO}, {@code ROLE:CON}, {@code ROLE:DEVIL_ADVOCATE}). With
     * nobody holding the role the engine falls back to ALL — every member argues
     * both sides, or everyone plays the challenger — so the style silently becomes
     * something else. Checked for preset-expanded groups only: explicit phases may
     * route roles however they like, and shipped templates set no such style.
     */
    static List<String> presetRoleProblems(AgentGroupConfiguration config) {
        if (config.getPhases() != null && !config.getPhases().isEmpty()) {
            return List.of();
        }
        Set<String> roles = new HashSet<>();
        if (config.getMembers() != null) {
            config.getMembers().stream()
                    .filter(m -> m != null && m.role() != null)
                    .forEach(m -> roles.add(m.role().trim().toUpperCase(Locale.ROOT)));
        }
        List<String> problems = new ArrayList<>();
        if (config.getStyle() == DiscussionStyle.DEBATE && (!roles.contains("PRO") || !roles.contains("CON"))) {
            problems.add("style DEBATE needs at least one member with role PRO and one with role CON — without them every member "
                    + "argues both sides");
        }
        if (config.getStyle() == DiscussionStyle.DEVIL_ADVOCATE && !roles.contains("DEVIL_ADVOCATE")) {
            problems.add("style DEVIL_ADVOCATE needs a member with role DEVIL_ADVOCATE — without one every member plays the challenger");
        }
        return problems;
    }

    /**
     * W5: a group that reaches itself through its GROUP members. The runtime depth
     * limit bounded the recursion, so this used to "work" — as
     * {@code eddi.groups.max-depth} nested copies of the same discussion, paid for
     * in full. Only an update can close a cycle: a group being created has no id
     * anything could reference yet.
     */
    void rejectNestingCycle(String groupId, AgentGroupConfiguration config) {
        if (groupId != null && reachesGroup(groupId, config, new HashSet<>(), 0)) {
            throw new IllegalArgumentException("group '" + groupId + "' contains itself through its GROUP members — nested "
                    + "groups must not form a cycle");
        }
    }

    private boolean reachesGroup(String targetGroupId, AgentGroupConfiguration config, Set<String> visited, int depth) {
        if (config == null || config.getMembers() == null || depth > MAX_CYCLE_SEARCH_DEPTH) {
            return false;
        }
        for (var member : config.getMembers()) {
            if (member == null || member.memberType() != AgentGroupConfiguration.MemberType.GROUP || member.agentId() == null) {
                continue;
            }
            if (targetGroupId.equals(member.agentId())) {
                return true;
            }
            if (visited.add(member.agentId()) && reachesGroup(targetGroupId, readChildConfig(member.agentId()), visited, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /** Bounds the cycle search; far beyond any nesting the engine will run. */
    private static final int MAX_CYCLE_SEARCH_DEPTH = 32;

    /**
     * W1/W7: artifact, task-list and dynamic-agent tools are only assembled for a
     * member whose LLM task sets {@code enableBuiltInTools: true} — and whether
     * they do cannot be answered here without reading every member's LLM
     * configuration. State the prerequisite once, at the moment someone enables the
     * feature, which is where it is actionable (same shape as
     * {@link #warnCostCeilingNeedsPricedMembers}).
     */
    private void noteBuiltInToolPrerequisite(AgentGroupConfiguration config) {
        List<String> features = builtInToolFeatures(config);
        if (!features.isEmpty()) {
            LOGGER.infof("Group '%s' enables %s. Members only receive these tools when their LLM task sets "
                    + "enableBuiltInTools: true — a member without it takes part without them, silently.",
                    LogSanitizer.sanitize(config.getName()), String.join(", ", features));
        }
    }

    /** The tool-backed group features {@code config} turns on. */
    static List<String> builtInToolFeatures(AgentGroupConfiguration config) {
        List<String> features = new ArrayList<>();
        if (config.getArtifactConfig() != null && config.getArtifactConfig().allowArtifactTools()) {
            features.add("shared artifacts");
        }
        if (config.getTaskListConfig() != null && config.getTaskListConfig().allowAgentTaskCreation()) {
            features.add("agent task creation");
        }
        if (config.getDynamicAgents() != null) {
            features.add("dynamic agents");
        }
        return features;
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
                    p -> p.type() == PhaseType.PLAN
                            || p.type() == PhaseType.EXECUTE
                            || p.type() == PhaseType.VERIFY);
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
    AgentGroupConfiguration readChildConfig(String groupId) {
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
                LogSanitizer.sanitize(groupConfiguration.getName()), LogSanitizer.sanitize(name)));
    }

    /**
     * Says, at save time, that a SYNTHESIS phase will answer with a <b>scoring
     * verdict</b> rather than the balanced prose its moderator's own prompt asks
     * for.
     * <p>
     * Giving members structural roles is what switches this on, and nothing in the
     * config says so. A grant board whose members were given {@code role: PRO} and
     * {@code role: CON} for flavour had its chair return
     * {@code {"winner":"CON","scores":{...}}} instead of the recommendation its
     * system prompt specified — correct for a debate-scoring exercise, wrong for
     * anything else, and discoverable only by running it.
     * <p>
     * INFO rather than WARN, deliberately: for a real debate this is the intended
     * behaviour and the note is the documentation of it, not a complaint. The
     * Manager renders the same list beside the phase (see {@code
     * debateVerdictSynthesisPhaseNames} in
     * {@code ui/manager/src/lib/group-config.ts}), which is where a config author
     * will actually read it.
     */
    private void noteDebateVerdictSynthesis(AgentGroupConfiguration groupConfiguration) {
        debateVerdictSynthesisPhaseNames(groupConfiguration).forEach(name -> LOGGER.infof(
                "Group '%s' phase '%s' will answer with a scoring verdict (winner/scores JSON), not prose: the members hold "
                        + "two or more distinct roles and arguments precede it, so it takes the debate-judgment path and the "
                        + "moderator's own synthesis prompt is not used. Set an inputTemplate on that phase to get prose back.",
                LogSanitizer.sanitize(groupConfiguration.getName()), LogSanitizer.sanitize(name)));
    }

    /**
     * The SYNTHESIS phases of this config that will take the debate-judgment path.
     * Separated from the logging so the decision is assertable, and mirrored in the
     * Manager so a config author sees it rather than a server log.
     * <p>
     * Mirrors {@code GroupContextBuilder.isDebateJudgment}, with the two
     * substitutions a config-time check has to make:
     * <ul>
     * <li>the runtime asks whether ARGUMENT/REBUTTAL entries are already on the
     * transcript; here we ask whether an {@code ARGUE}/{@code REBUTTAL}
     * <em>phase</em> precedes the synthesis, which is where those entries come
     * from;</li>
     * <li>the runtime knows who is speaking; here only a phase restricted to
     * {@code participants: "MODERATOR"} has a speaker that can be resolved from
     * configuration at all, so only those are reported. A synthesis open to other
     * participants can still hit the verdict path for a non-debating speaker — it
     * is left unreported rather than guessed at.</li>
     * </ul>
     * Preset-expanded like {@link #moderatorlessPhaseNames}, or the check would be
     * inert for exactly the style it matters most for: a DEBATE group stores no
     * phases of its own.
     */
    static List<String> debateVerdictSynthesisPhaseNames(AgentGroupConfiguration groupConfiguration) {
        Set<String> roles = distinctMemberRoles(groupConfiguration);
        // The judgment prompt scores one side against another, so a roster with
        // fewer than two sides never takes this path.
        if (roles.size() < 2) {
            return List.of();
        }
        List<DiscussionPhase> phases = resolvedPhases(groupConfiguration);
        // A moderator that is itself a debater judges nothing: the runtime refuses to
        // let a partisan score its own debate and falls back to prose.
        if (moderatorIsADebater(groupConfiguration, roles)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        boolean argumentsSoFar = false;
        for (DiscussionPhase phase : phases) {
            if (phase == null) {
                continue;
            }
            if (phase.type() == PhaseType.SYNTHESIS
                    && phase.inputTemplate() == null
                    && argumentsSoFar
                    && "MODERATOR".equalsIgnoreCase(phase.participants())) {
                names.add(phase.name());
            }
            if (phase.type() == PhaseType.ARGUE || phase.type() == PhaseType.REBUTTAL) {
                argumentsSoFar = true;
            }
        }
        return List.copyOf(names);
    }

    /** The distinct, upper-cased, non-blank member roles — a debate's "sides". */
    private static Set<String> distinctMemberRoles(AgentGroupConfiguration config) {
        if (config.getMembers() == null) {
            return Set.of();
        }
        return config.getMembers().stream()
                .filter(m -> m != null && m.role() != null && !m.role().isBlank())
                .map(m -> m.role().trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    /**
     * Whether the agent that will speak a MODERATOR phase holds one of the debating
     * roles. A moderator named but absent from the roster has no role at all, so it
     * judges — which is the common shape: a chair agent that is not a member.
     */
    private static boolean moderatorIsADebater(AgentGroupConfiguration config, Set<String> roles) {
        String moderator = config.getModeratorAgentId();
        List<AgentGroupConfiguration.GroupMember> members = config.getMembers() != null ? config.getMembers() : List.of();
        AgentGroupConfiguration.GroupMember speaker;
        if (moderator != null && !moderator.isBlank()) {
            speaker = members.stream().filter(m -> m != null && moderator.equals(m.agentId())).findFirst().orElse(null);
        } else {
            // No moderator: the engine substitutes the first member by speaking order
            // (warnOnModeratorlessPhases says so separately), and that member is
            // usually a debater.
            speaker = members.stream()
                    .filter(Objects::nonNull)
                    .min(Comparator.comparing(m -> m.speakingOrder() == null ? Integer.MAX_VALUE : m.speakingOrder()))
                    .orElse(null);
        }
        return speaker != null && speaker.role() != null && roles.contains(speaker.role().trim().toUpperCase(Locale.ROOT));
    }

    /** This config's phases, preset-expanded when it declares none of its own. */
    private static List<DiscussionPhase> resolvedPhases(AgentGroupConfiguration groupConfiguration) {
        List<DiscussionPhase> phases = groupConfiguration.getPhases();
        if (phases != null && !phases.isEmpty()) {
            return phases;
        }
        DiscussionStyle style = groupConfiguration.getStyle() != null ? groupConfiguration.getStyle() : DiscussionStyle.ROUND_TABLE;
        return DiscussionStylePresets.expand(style, groupConfiguration.getMaxRounds());
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
        return resolvedPhases(groupConfiguration).stream()
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
                LogSanitizer.sanitize(groupConfiguration.getName()), protocol.maxCostPerDiscussion());
        groupConfiguration.setProtocol(new AgentGroupConfiguration.ProtocolConfig(
                protocol.agentTimeoutSeconds(), protocol.onAgentFailure(), protocol.maxRetries(),
                protocol.onMemberUnavailable(), protocol.maxTurns(), null, protocol.onCostExceeded()));
    }

    /**
     * A dollar ceiling only binds if something is priced.
     * <p>
     * {@code TokenPricing} ships no provider price table by design, so a member
     * whose LLM task carries no {@code inputPricePer1M}/{@code outputPricePer1M}
     * contributes exactly $0 and {@code totalCost} stays at 0 for the whole
     * discussion — the ceiling can never fire. That is deliberate, but it is also
     * invisible: the group reports {@code totalCost: 0}, {@code onCostExceeded}
     * never runs, and a template advertising a "dollar ceiling" appears to be
     * working. {@code setup_agent} exposes no pricing fields at all, so every agent
     * it builds lands in exactly this state.
     * <p>
     * Whether the members are priced cannot be answered here without resolving each
     * member agent's LLM configuration — a cross-resource read on the save path.
     * The prerequisite is stated once, at the moment someone configures the
     * ceiling, which is where it is actionable.
     */
    private void warnCostCeilingNeedsPricedMembers(AgentGroupConfiguration groupConfiguration) {
        var protocol = groupConfiguration.getProtocol();
        if (protocol == null || protocol.maxCostPerDiscussion() == null || protocol.maxCostPerDiscussion() <= 0) {
            return;
        }
        LOGGER.infof("Group '%s' sets maxCostPerDiscussion=%s. This only takes effect for members whose LLM task "
                + "defines inputPricePer1M/outputPricePer1M — EDDI ships no provider price table, so unpriced "
                + "members contribute $0 and the ceiling never fires.",
                LogSanitizer.sanitize(groupConfiguration.getName()), protocol.maxCostPerDiscussion());
    }
}
