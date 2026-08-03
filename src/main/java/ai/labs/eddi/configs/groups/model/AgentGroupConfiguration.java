/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.hitl.HitlGranularity;
import ai.labs.eddi.configs.hitl.HitlRejectionPolicy;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Versioned configuration for a group of agents that can participate in
 * structured discussions using configurable phase-based styles. Persisted via
 * {@code AbstractResourceStore}.
 *
 * @author ginccc
 */
public class AgentGroupConfiguration {

    /**
     * Upper bound on {@link #members}. Every member is one LLM call per phase, so
     * the member list sizes the discussion's fan-out directly. 100 is far beyond
     * any deliberated group (the dynamic-agent guardrails default to 5 created / 10
     * recruited members) while still bounding a pathological list.
     */
    public static final int MAX_MEMBERS = 100;

    /**
     * Upper bound on {@link #maxRounds}. {@code DiscussionStylePresets.expand}
     * multiplies this into concrete phases for ROUND_TABLE and DELPHI, and every
     * phase fans out to every member — so this value alone multiplies the LLM cost
     * of a single discussion. 50 rounds against even a small group is already
     * hundreds of model calls; nothing legitimate goes near it, and without the
     * bound a config could ask for millions of phases.
     * <p>
     * Deliberately only an upper bound: {@code expand()} already clamps the low
     * side with {@code Math.max(maxRounds, 1)}, so rejecting 0 would break configs
     * that legitimately leave it unset for round-less styles such as DEBATE.
     */
    public static final int MAX_DISCUSSION_ROUNDS = 50;

    private String name;
    private String description;

    @Size(max = MAX_MEMBERS, message = "'members' must contain at most {max} entries")
    private List<GroupMember> members = new ArrayList<>();
    private String moderatorAgentId;
    private DiscussionStyle style;

    @Max(value = MAX_DISCUSSION_ROUNDS, message = "'maxRounds' must be at most {value}")
    private int maxRounds = 2;
    private List<DiscussionPhase> phases;
    private ProtocolConfig protocol;
    /** Pre-configured task list. If non-empty, skips the PLAN phase. */
    private List<TaskDefinition> tasks;
    /** Dynamic agent creation and recruitment configuration. */
    private DynamicAgentConfig dynamicAgents;
    /**
     * After each SYNTHESIS phase, give every participant who did NOT write the
     * synthesis one short turn to state where they still materially disagree (I4).
     * Non-PASS replies become public {@code DISSENT} transcript entries and
     * populate {@code DecisionRecord.dissents}.
     * <p>
     * Opt-in because it costs one extra short call per non-synthesizer. It is
     * nonetheless the honest design: the alternative is asking the synthesizer to
     * report the disagreement with its own synthesis, and an author summarizing the
     * objections to their own summary is exactly the failure mode a minority report
     * exists to prevent.
     */
    private boolean recordDissents;

    /**
     * Whether and how far members may file their own tasks mid-discussion (I5).
     * {@code null} means the tools are absent entirely, which is also what a
     * default-constructed {@link GroupTaskConfig} means.
     */
    private GroupTaskConfig taskListConfig;

    public GroupTaskConfig getTaskListConfig() {
        return taskListConfig;
    }

    public void setTaskListConfig(GroupTaskConfig taskListConfig) {
        this.taskListConfig = taskListConfig;
    }

    /**
     * Governs agent-filed tasks (I5). The task list is otherwise written only by
     * the PLAN phase and by config, so work an agent <em>discovers</em> while
     * executing — a missing migration, an untested edge case — dies in prose. The
     * wave loop already re-queries {@code findExecutableTasks()} each wave, so a
     * filed task flows into execution with no scheduler changes.
     * <p>
     * <b>Off by default, and deliberately so.</b> An LLM that can file work can
     * file it in a loop; the caps below are the bound. There is no permissive
     * standalone default anywhere — a discussion that does not opt in never sees
     * the tools at all.
     *
     * @param allowAgentTaskCreation
     *            master switch. {@code false} (default) means the tools are not
     *            assembled, not merely rejected at call time — an absent tool costs
     *            no prompt tokens and cannot be argued with
     * @param maxAgentAddedTasksPerDiscussion
     *            ceiling on agent-filed tasks across the whole discussion (default
     *            20). Non-positive values fall back to the default rather than
     *            meaning "unlimited": an unbounded write surface for an LLM is
     *            never the intent behind a mistyped 0
     * @param maxPerTurn
     *            ceiling within a single member turn (default 3). Separate from the
     *            discussion cap because the failure modes differ — a runaway turn
     *            files twenty tasks in one breath, where a discussion-long drift
     *            files one per turn for forty turns
     */
    public record GroupTaskConfig(boolean allowAgentTaskCreation, int maxAgentAddedTasksPerDiscussion, int maxPerTurn) {

        public static final int DEFAULT_MAX_PER_DISCUSSION = 20;
        public static final int DEFAULT_MAX_PER_TURN = 3;

        /**
         * Normalizes at the one choke point every reader passes through, so no consumer
         * re-derives defaults from a partially-specified config — a JSON naming only
         * {@code allowAgentTaskCreation} is the common case. Same shape as
         * {@link ConvergenceConfig}'s compact constructor.
         */
        public GroupTaskConfig {
            if (maxAgentAddedTasksPerDiscussion <= 0) {
                maxAgentAddedTasksPerDiscussion = DEFAULT_MAX_PER_DISCUSSION;
            }
            if (maxPerTurn <= 0) {
                maxPerTurn = DEFAULT_MAX_PER_TURN;
            }
        }

        /** Disabled, with both caps at their defaults. */
        public GroupTaskConfig() {
            this(false, DEFAULT_MAX_PER_DISCUSSION, DEFAULT_MAX_PER_TURN);
        }
    }

    /**
     * A member of the group. Members can be individual agents or nested groups.
     * <p>
     * For {@code MemberType.GROUP} members, the {@code agentId} field contains the
     * group configuration ID instead. The sub-group runs its own discussion and its
     * synthesized answer becomes this member's response.
     * <p>
     * The optional {@code role} field controls which phases the member participates
     * in (e.g. "DEVIL_ADVOCATE", "PRO", "CON"). If null, the member is a default
     * participant.
     */
    public record GroupMember(String agentId, String displayName, Integer speakingOrder, String role, MemberType memberType) {

        /** Convenience constructor defaulting to AGENT member type. */
        public GroupMember(String agentId, String displayName, Integer speakingOrder, String role) {
            this(agentId, displayName, speakingOrder, role, MemberType.AGENT);
        }
    }

    /**
     * Whether a group member is an individual agent or a nested sub-group.
     */
    public enum MemberType {
        /** An individual EDDI agent. */
        AGENT,
        /** A nested group — runs its own discussion, returns synthesized answer. */
        GROUP
    }

    // --- Discussion Style ---

    /**
     * Preset discussion styles that auto-generate phases via
     * {@link DiscussionStylePresets}.
     */
    public enum DiscussionStyle {
        /** Everyone speaks → respond to others → repeat → synthesis. */
        ROUND_TABLE,
        /** Opinion → critique each peer → revise based on feedback → synthesis. */
        PEER_REVIEW,
        /** Opinion → devil challenges consensus → defend/revise → synthesis. */
        DEVIL_ADVOCATE,
        /** Anonymous independent rounds → gradual convergence → synthesis. */
        DELPHI,
        /** Pro team argues → Con team argues → rebuttals → judge decides. */
        DEBATE,
        /** Collaborative task accomplishment: plan → execute → verify → synthesis. */
        TASK_FORCE,
        /** User defines phases manually. */
        CUSTOM
    }

    // --- Discussion Phase ---

    /**
     * A single phase in a discussion. Each phase defines who speaks, what context
     * they receive, and what prompt template to use.
     *
     * <p>
     * The {@code participants} field accepts: "ALL", "MODERATOR", or
     * "ROLE:&lt;roleName&gt;" (e.g. "ROLE:DEVIL_ADVOCATE").
     */
    public record DiscussionPhase(String name, PhaseType type, String participants, TurnOrder turnOrder, ContextScope contextScope,
            boolean targetEachPeer, String inputTemplate, int repeats, boolean requiresApproval, ConvergenceConfig convergence,
            boolean allowAbstention) {

        /**
         * Convenience constructor with defaults: participants=ALL,
         * turnOrder=SEQUENTIAL, contextScope=FULL, no peer targeting, no custom
         * template, 1 repeat, no approval required.
         */
        public DiscussionPhase(String name, PhaseType type) {
            this(name, type, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
        }

        /**
         * Backward-compatible constructor without requiresApproval.
         */
        public DiscussionPhase(String name, PhaseType type, String participants, TurnOrder turnOrder, ContextScope contextScope,
                boolean targetEachPeer, String inputTemplate, int repeats) {
            this(name, type, participants, turnOrder, contextScope, targetEachPeer, inputTemplate, repeats, false);
        }

        /**
         * Backward-compatible constructor without convergence (I2) — {@code null} means
         * convergence detection is off, which is the default for every phase that
         * predates I2 and for every style preset (the plan's compat rule: no preset
         * changes; DELPHI's recommended convergence config is documented rather than
         * baked in).
         */
        public DiscussionPhase(String name, PhaseType type, String participants, TurnOrder turnOrder, ContextScope contextScope,
                boolean targetEachPeer, String inputTemplate, int repeats, boolean requiresApproval) {
            this(name, type, participants, turnOrder, contextScope, targetEachPeer, inputTemplate, repeats, requiresApproval, null);
        }

        /**
         * Backward-compatible constructor without {@code allowAbstention} (I4) —
         * {@code false} means every participant must produce a contribution, which is
         * the behavior of every phase that predates I4 and of every style preset.
         */
        public DiscussionPhase(String name, PhaseType type, String participants, TurnOrder turnOrder, ContextScope contextScope,
                boolean targetEachPeer, String inputTemplate, int repeats, boolean requiresApproval, ConvergenceConfig convergence) {
            this(name, type, participants, turnOrder, contextScope, targetEachPeer, inputTemplate, repeats, requiresApproval, convergence, false);
        }
    }

    /**
     * Early-exit detection for a phase whose {@code repeats > 1} (I2). Without this
     * a DELPHI-style phase always burns exactly {@code repeats} rounds, even once
     * the members have stopped changing their positions — "convergence" is prompt
     * text, not behavior.
     * <p>
     * Two mechanisms feed one exit path. <b>Deterministic:</b> every participant
     * abstained this repeat — free, no LLM call, and the reason {@code minRepeats}
     * does not gate it: unanimous silence is evidence on its own terms, not a
     * similarity estimate that needs a baseline. Requires a phase with
     * {@code allowAbstention} (I4), which is what produces the {@code ABSTAINED}
     * entries it counts. <b>Semantic:</b> a judge compares this repeat's
     * contributions with the previous repeat's and returns an agreement score. The
     * judge cannot run before repeat index {@code minRepeats - 1} because it needs
     * a previous repeat to compare against.
     *
     * @param enabled
     *            off by default — an LLM judge costs a call per repeat, so this is
     *            opt-in per phase
     * @param minRepeats
     *            the judge is skipped until this many repeats have completed
     *            (default 2: one round establishes positions, the second is the
     *            first that can differ from it). Values below 2 are raised to 2 —
     *            there is nothing to compare a first repeat against
     * @param threshold
     *            agreement score at or above which the phase is converged (default
     *            0.8). Compared with {@code >=}, so a judge returning exactly the
     *            threshold converges
     * @param judge
     *            {@code "MODERATOR"} (default) runs the group's configured
     *            moderator agent as the judge. {@code "SERVICE"} is accepted and
     *            documented but currently falls back to MODERATOR with a warning:
     *            the shared {@code SummarizationService} needs LLM provider/model
     *            coordinates and credentials that {@code AgentGroupConfiguration}
     *            does not carry, so wiring it needs config this item does not add
     */
    public record ConvergenceConfig(boolean enabled, int minRepeats, double threshold, String judge) {

        /**
         * The lowest {@code minRepeats} that can mean anything — see the record
         * Javadoc.
         */
        public static final int MIN_COMPARABLE_REPEATS = 2;
        public static final double DEFAULT_THRESHOLD = 0.8;
        public static final String JUDGE_MODERATOR = "MODERATOR";
        public static final String JUDGE_SERVICE = "SERVICE";

        /**
         * Normalizes at the one choke point every reader passes through, so no consumer
         * has to re-derive defaults from a partially-specified config (a JSON config
         * naming only {@code enabled} is the common case).
         */
        public ConvergenceConfig {
            minRepeats = Math.max(minRepeats, MIN_COMPARABLE_REPEATS);
            if (threshold <= 0.0 || threshold > 1.0) {
                threshold = DEFAULT_THRESHOLD;
            }
            judge = judge == null || judge.isBlank() ? JUDGE_MODERATOR : judge.trim().toUpperCase();
        }

        /** Convenience: enabled with every other setting defaulted. */
        public ConvergenceConfig(boolean enabled) {
            this(enabled, MIN_COMPARABLE_REPEATS, DEFAULT_THRESHOLD, JUDGE_MODERATOR);
        }
    }

    public enum PhaseType {
        OPINION, CRITIQUE, REVISION, CHALLENGE, DEFENSE, ARGUE, REBUTTAL, SYNTHESIS,
        /** Task decomposition and assignment. */
        PLAN,
        /** Task execution by assigned agents. */
        EXECUTE,
        /** Verification of task results. */
        VERIFY
    }

    public enum TurnOrder {
        SEQUENTIAL, PARALLEL
    }

    /**
     * Controls what prior transcript context each agent receives during a phase.
     */
    public enum ContextScope {
        /** Agent sees only the question (independent thinking). */
        NONE,
        /** Agent sees entire transcript so far. */
        FULL,
        /** Agent sees only the immediately preceding phase. */
        LAST_PHASE,
        /** Agent sees content from prior phases but not who said it. */
        ANONYMOUS,
        /** Agent sees only entries targeted AT them (for REVISION phase). */
        OWN_FEEDBACK,
        /** Agent sees only its assigned task description. */
        TASK_ONLY,
        /** Agent sees its task plus results of dependency tasks. */
        TASK_WITH_DEPS
    }

    // --- Protocol (error handling / timeouts) ---

    /**
     * Protocol-level configuration for group discussions: timeouts, retries,
     * failure policies, and safety caps.
     *
     * @param agentTimeoutSeconds
     *            per-agent timeout in seconds (default: 60)
     * @param onAgentFailure
     *            policy when an agent fails (SKIP, RETRY, ABORT)
     * @param maxRetries
     *            max retry attempts per agent (default: 2)
     * @param onMemberUnavailable
     *            policy when a member is unavailable (SKIP, FAIL)
     * @param maxTurns
     *            global hard cap on total agent turns across all phases. Prevents
     *            runaway discussions from misconfigured rounds. 0 or negative = use
     *            default (50). When exceeded, remaining phases are skipped and
     *            synthesis proceeds with whatever transcript exists.
     * @param maxCostPerDiscussion
     *            dollar ceiling on {@code GroupConversation#getTotalCost()} (I1).
     *            {@code null} = unlimited. Checked before each turn and before each
     *            {@code TaskForceEngine} EXECUTE wave — an already-dispatched,
     *            in-flight turn may still push the total past the ceiling; that
     *            overshoot is accepted, not prevented. A non-positive value is
     *            treated as {@code null} (unlimited) at save time, with a warning —
     *            see {@code AgentGroupStore}.
     * @param onCostExceeded
     *            what to do once {@code maxCostPerDiscussion} is hit. {@code null}
     *            defaults to {@code SYNTHESIZE_NOW} (see the constructor).
     */
    public record ProtocolConfig(int agentTimeoutSeconds, MemberFailurePolicy onAgentFailure, int maxRetries,
            MemberUnavailablePolicy onMemberUnavailable, int maxTurns, Double maxCostPerDiscussion, CostPolicy onCostExceeded) {

        /**
         * Canonical constructor — normalizes a {@code null} {@link #onCostExceeded} to
         * {@link CostPolicy#SYNTHESIZE_NOW} so every reader can treat the field as
         * non-null, the same way {@link #maxTurns}'s 0-or-negative-means-default is
         * normalized at the read site rather than pushed onto every caller.
         */
        public ProtocolConfig {
            if (onCostExceeded == null) {
                onCostExceeded = CostPolicy.SYNTHESIZE_NOW;
            }
        }

        /**
         * Backward-compatible constructor — defaults maxTurns to 0 (engine default:
         * 50), cost ceiling to unlimited.
         */
        public ProtocolConfig(int agentTimeoutSeconds, MemberFailurePolicy onAgentFailure, int maxRetries,
                MemberUnavailablePolicy onMemberUnavailable) {
            this(agentTimeoutSeconds, onAgentFailure, maxRetries, onMemberUnavailable, 0);
        }

        /**
         * Backward-compatible constructor — defaults the cost ceiling to unlimited.
         */
        public ProtocolConfig(int agentTimeoutSeconds, MemberFailurePolicy onAgentFailure, int maxRetries,
                MemberUnavailablePolicy onMemberUnavailable, int maxTurns) {
            this(agentTimeoutSeconds, onAgentFailure, maxRetries, onMemberUnavailable, maxTurns, null, null);
        }

        public enum MemberFailurePolicy {
            SKIP, RETRY, ABORT
        }

        public enum MemberUnavailablePolicy {
            SKIP, FAIL
        }

        /**
         * What happens once {@link #maxCostPerDiscussion} is exceeded (I1).
         */
        public enum CostPolicy {
            /**
             * Stop scheduling new turns; jump ahead to the next remaining SYNTHESIS phase,
             * if any.
             */
            SYNTHESIZE_NOW,
            /**
             * Fail the discussion immediately (state FAILED), with an actionable reason.
             */
            ABORT
        }
    }

    // --- Getters/Setters ---

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<GroupMember> getMembers() {
        return members;
    }

    public void setMembers(List<GroupMember> members) {
        this.members = members;
    }

    public String getModeratorAgentId() {
        return moderatorAgentId;
    }

    public void setModeratorAgentId(String moderatorAgentId) {
        this.moderatorAgentId = moderatorAgentId;
    }

    public DiscussionStyle getStyle() {
        return style;
    }

    public void setStyle(DiscussionStyle style) {
        this.style = style;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public void setMaxRounds(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    public List<DiscussionPhase> getPhases() {
        return phases;
    }

    public void setPhases(List<DiscussionPhase> phases) {
        this.phases = phases;
    }

    public ProtocolConfig getProtocol() {
        return protocol;
    }

    public void setProtocol(ProtocolConfig protocol) {
        this.protocol = protocol;
    }

    public List<TaskDefinition> getTasks() {
        return tasks;
    }

    public void setTasks(List<TaskDefinition> tasks) {
        this.tasks = tasks;
    }

    public DynamicAgentConfig getDynamicAgents() {
        return dynamicAgents;
    }

    public void setDynamicAgents(DynamicAgentConfig dynamicAgents) {
        this.dynamicAgents = dynamicAgents;
    }

    public boolean isRecordDissents() {
        return recordDissents;
    }

    public void setRecordDissents(boolean recordDissents) {
        this.recordDissents = recordDissents;
    }

    /**
     * Human-in-the-loop (HITL) configuration for group discussions. Controls
     * approval timeouts, timeout policies, and granularity level.
     *
     * @since 6.0.0
     */
    private HitlConfig hitlConfig;

    public HitlConfig getHitlConfig() {
        return hitlConfig;
    }

    public void setHitlConfig(HitlConfig hitlConfig) {
        this.hitlConfig = hitlConfig;
    }

    // --- Task Definition ---

    /**
     * A pre-configured task for config-driven task orchestration. When tasks are
     * pre-defined here, the PLAN phase is skipped and these tasks are used
     * directly.
     *
     * @param subject
     *            short task title
     * @param description
     *            detailed instructions for the assigned agent
     * @param assignToRole
     *            "ALL", "ROLE:<name>", or specific agentId
     * @param dependsOn
     *            subjects of tasks that must complete first
     * @param priority
     *            0 = highest
     */
    public record TaskDefinition(
            String subject,
            String description,
            String assignToRole,
            List<String> dependsOn,
            int priority) {

        public TaskDefinition(String subject, String description) {
            this(subject, description, "ALL", List.of(), 0);
        }

        public TaskDefinition {
            Objects.requireNonNull(subject, "Task subject must not be null");
            Objects.requireNonNull(description, "Task description must not be null");
            if (dependsOn == null) {
                dependsOn = List.of();
            }
            if (assignToRole == null) {
                assignToRole = "ALL";
            }
        }
    }

    // --- Dynamic Agent Configuration ---

    /**
     * Lifecycle policy for agents created during a discussion.
     */
    public enum LifecyclePolicy {
        EPHEMERAL, KEEP_DEPLOYED, UNDEPLOY_ONLY, AGENT_DECIDES;

        @com.fasterxml.jackson.annotation.JsonValue
        public String toJson() {
            return name().toLowerCase().replace('_', '-');
        }

        @com.fasterxml.jackson.annotation.JsonCreator
        public static LifecyclePolicy fromJson(String value) {
            if (value == null)
                return EPHEMERAL;
            return valueOf(value.toUpperCase().replace('-', '_'));
        }
    }

    /**
     * Configuration for dynamic agent creation, recruitment, and delegation during
     * group discussions. Controls guardrails, allowed providers/models, and
     * lifecycle policy for dynamically created agents.
     */
    public static class DynamicAgentConfig {
        private boolean enabled;
        private boolean allowCreation;
        private boolean allowRecruitment;
        private boolean allowDelegation = true;

        private int maxCreatedAgentsPerDiscussion = 5;
        private int maxRecruitedAgentsPerDiscussion = 10;
        private int maxDelegationsPerTask = 3;

        /**
         * Maximum delegation hops. Agent A calling B calling C is depth 2; the call
         * that would exceed this depth is refused.
         * <p>
         * Finding F18: {@code converse_with_agent} had no depth bound at all, so an
         * A→B→A cycle recursed until the 60s per-hop watchdog happened to fire — and
         * because a call without a {@code conversationId} starts a FRESH conversation,
         * the busy-guard never broke the loop. Prompt injection in a single user
         * message was enough to start it.
         */
        private int maxDelegationDepth = 3;

        /**
         * Agent IDs this agent may delegate to via {@code converse_with_agent}.
         * {@code null} or empty means "any deployed agent" (the previous, unrestricted
         * behavior).
         */
        private List<String> allowedDelegationTargets;

        /** Allowed LLM providers for created agents. Null = inherit parent. */
        private List<String> allowedProviders;
        /**
         * Allowed models per provider. Keys are provider names, values are lists of
         * model names. Null = inherit parent model.
         */
        private Map<String, List<String>> allowedModels;
        private boolean inheritParentModel = true;

        /**
         * Lifecycle policy for agents created during the discussion.
         * <ul>
         * <li>{@code ephemeral} — auto-delete after discussion ends</li>
         * <li>{@code keep-deployed} — keep deployed for future use</li>
         * <li>{@code undeploy-only} — undeploy but keep config</li>
         * <li>{@code agent-decides} — default ephemeral, but agent can retain</li>
         * </ul>
         */
        private LifecyclePolicy lifecyclePolicy = LifecyclePolicy.EPHEMERAL;

        public boolean isEnabled() {
            return enabled;
        }
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        public boolean isAllowCreation() {
            return allowCreation;
        }
        public void setAllowCreation(boolean allowCreation) {
            this.allowCreation = allowCreation;
        }
        public boolean isAllowRecruitment() {
            return allowRecruitment;
        }
        public void setAllowRecruitment(boolean allowRecruitment) {
            this.allowRecruitment = allowRecruitment;
        }
        public boolean isAllowDelegation() {
            return allowDelegation;
        }
        public void setAllowDelegation(boolean allowDelegation) {
            this.allowDelegation = allowDelegation;
        }
        public int getMaxCreatedAgentsPerDiscussion() {
            return maxCreatedAgentsPerDiscussion;
        }
        public void setMaxCreatedAgentsPerDiscussion(int max) {
            this.maxCreatedAgentsPerDiscussion = max;
        }
        public int getMaxRecruitedAgentsPerDiscussion() {
            return maxRecruitedAgentsPerDiscussion;
        }
        public void setMaxRecruitedAgentsPerDiscussion(int max) {
            this.maxRecruitedAgentsPerDiscussion = max;
        }
        public int getMaxDelegationsPerTask() {
            return maxDelegationsPerTask;
        }
        public void setMaxDelegationsPerTask(int max) {
            this.maxDelegationsPerTask = max;
        }
        public int getMaxDelegationDepth() {
            return maxDelegationDepth;
        }
        public void setMaxDelegationDepth(int maxDelegationDepth) {
            this.maxDelegationDepth = maxDelegationDepth;
        }
        public List<String> getAllowedDelegationTargets() {
            return allowedDelegationTargets;
        }
        public void setAllowedDelegationTargets(List<String> allowedDelegationTargets) {
            this.allowedDelegationTargets = allowedDelegationTargets;
        }
        public List<String> getAllowedProviders() {
            return allowedProviders;
        }
        public void setAllowedProviders(List<String> allowedProviders) {
            this.allowedProviders = allowedProviders;
        }
        public Map<String, List<String>> getAllowedModels() {
            return allowedModels;
        }
        public void setAllowedModels(Map<String, List<String>> allowedModels) {
            this.allowedModels = allowedModels;
        }
        public boolean isInheritParentModel() {
            return inheritParentModel;
        }
        public void setInheritParentModel(boolean inheritParentModel) {
            this.inheritParentModel = inheritParentModel;
        }
        public LifecyclePolicy getLifecyclePolicy() {
            return lifecyclePolicy;
        }
        public void setLifecyclePolicy(LifecyclePolicy lifecyclePolicy) {
            this.lifecyclePolicy = lifecyclePolicy != null ? lifecyclePolicy : LifecyclePolicy.EPHEMERAL;
        }
    }

    /**
     * HITL approval timeout configuration for group discussions. Extends the
     * agent-level config with a {@code granularity} field to control at what level
     * human approval is required: {@code PHASE} (after each gated phase) or
     * {@code TASK} (per task inside a gated EXECUTE phase; non-EXECUTE phases fall
     * back to PHASE behavior).
     *
     * @since 6.0.0
     */
    public static class HitlConfig {
        private String approvalTimeout;
        private HitlTimeoutPolicy timeoutPolicy = HitlTimeoutPolicy.WAIT_INDEFINITELY;
        private HitlGranularity granularity = HitlGranularity.PHASE;
        private HitlRejectionPolicy onTaskRejection = HitlRejectionPolicy.FAIL;

        public String getApprovalTimeout() {
            return approvalTimeout;
        }

        public void setApprovalTimeout(String approvalTimeout) {
            this.approvalTimeout = approvalTimeout;
        }

        public HitlTimeoutPolicy getTimeoutPolicy() {
            return timeoutPolicy;
        }

        public void setTimeoutPolicy(HitlTimeoutPolicy timeoutPolicy) {
            // Coalesce null to the default (mirrors setLifecyclePolicy) so an explicit
            // JSON "timeoutPolicy": null cannot silently disable the default policy.
            this.timeoutPolicy = timeoutPolicy != null ? timeoutPolicy : HitlTimeoutPolicy.WAIT_INDEFINITELY;
        }

        public HitlGranularity getGranularity() {
            return granularity;
        }

        public void setGranularity(HitlGranularity granularity) {
            this.granularity = granularity != null ? granularity : HitlGranularity.PHASE;
        }

        public HitlRejectionPolicy getOnTaskRejection() {
            return onTaskRejection;
        }

        public void setOnTaskRejection(HitlRejectionPolicy onTaskRejection) {
            this.onTaskRejection = onTaskRejection != null ? onTaskRejection : HitlRejectionPolicy.FAIL;
        }
    }

}
