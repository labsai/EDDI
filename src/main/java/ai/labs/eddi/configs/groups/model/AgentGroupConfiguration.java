package ai.labs.eddi.configs.groups.model;

import java.util.ArrayList;
import java.util.List;

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
            boolean targetEachPeer, String inputTemplate, int repeats) {

        /**
         * Convenience constructor with defaults: participants=ALL,
         * turnOrder=SEQUENTIAL, contextScope=FULL, no peer targeting, no custom
         * template, 1 repeat.
         */
        public DiscussionPhase(String name, PhaseType type) {
            this(name, type, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1);
        }
    }

    public enum PhaseType {
        OPINION, CRITIQUE, REVISION, CHALLENGE, DEFENSE, ARGUE, REBUTTAL, SYNTHESIS
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
        OWN_FEEDBACK
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
}
