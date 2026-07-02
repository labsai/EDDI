/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.hitl.HitlGranularity;
import ai.labs.eddi.configs.hitl.HitlRejectionPolicy;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;

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
    private String name;
    private String description;
    private List<GroupMember> members = new ArrayList<>();
    private String moderatorAgentId;
    private DiscussionStyle style;
    private int maxRounds = 2;
    private List<DiscussionPhase> phases;
    private ProtocolConfig protocol;
    /** Pre-configured task list. If non-empty, skips the PLAN phase. */
    private List<TaskDefinition> tasks;
    /** Dynamic agent creation and recruitment configuration. */
    private DynamicAgentConfig dynamicAgents;

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
            boolean targetEachPeer, String inputTemplate, int repeats, boolean requiresApproval) {

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
     */
    public record ProtocolConfig(int agentTimeoutSeconds, MemberFailurePolicy onAgentFailure, int maxRetries,
            MemberUnavailablePolicy onMemberUnavailable, int maxTurns) {

        /**
         * Backward-compatible constructor — defaults maxTurns to 0 (engine default:
         * 50).
         */
        public ProtocolConfig(int agentTimeoutSeconds, MemberFailurePolicy onAgentFailure, int maxRetries,
                MemberUnavailablePolicy onMemberUnavailable) {
            this(agentTimeoutSeconds, onAgentFailure, maxRetries, onMemberUnavailable, 0);
        }

        public enum MemberFailurePolicy {
            SKIP, RETRY, ABORT
        }

        public enum MemberUnavailablePolicy {
            SKIP, FAIL
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
            this.timeoutPolicy = timeoutPolicy;
        }

        public HitlGranularity getGranularity() {
            return granularity;
        }

        public void setGranularity(HitlGranularity granularity) {
            this.granularity = granularity;
        }

        public HitlRejectionPolicy getOnTaskRejection() {
            return onTaskRejection;
        }

        public void setOnTaskRejection(HitlRejectionPolicy onTaskRejection) {
            this.onTaskRejection = onTaskRejection;
        }
    }

}
