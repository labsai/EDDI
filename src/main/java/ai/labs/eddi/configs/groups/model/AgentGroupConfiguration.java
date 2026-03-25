package ai.labs.eddi.configs.groups.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Versioned configuration for a group of agents that can participate in
 * structured debate rounds. Persisted via {@code AbstractResourceStore}.
 *
 * @author ginccc
 */
public class AgentGroupConfiguration {
    private String name;
    private String description;
    private List<GroupMember> members = new ArrayList<>();
    private String moderatorAgentId;
    private ProtocolConfig protocol;
    private ContextConfig contextConfig;

    // --- Member definition ---

    public record GroupMember(String agentId, String displayName, Integer speakingOrder, boolean allowGroupDiscussion,
            boolean allowAgentToAgentCalls) {
    }

    // --- Protocol definition ---

    public record ProtocolConfig(ProtocolType type, int maxRounds, int agentTimeoutSeconds, MemberFailurePolicy onAgentFailure, int maxRetries,
            MemberUnavailablePolicy onMemberUnavailable) {

        public enum ProtocolType {
            SEQUENTIAL, PARALLEL
        }

        public enum MemberFailurePolicy {
            SKIP, RETRY, ABORT
        }

        public enum MemberUnavailablePolicy {
            SKIP, FAIL
        }
    }

    // --- Context/input construction ---

    public record ContextConfig(HistoryStrategy historyStrategy, Integer windowSize, boolean summarizeBetweenRounds, String inputTemplateRound1,
            String inputTemplateRoundN, String inputTemplateSynthesis) {

        public enum HistoryStrategy {
            FULL, LAST_ROUND, WINDOW
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

    public ProtocolConfig getProtocol() {
        return protocol;
    }

    public void setProtocol(ProtocolConfig protocol) {
        this.protocol = protocol;
    }

    public ContextConfig getContextConfig() {
        return contextConfig;
    }

    public void setContextConfig(ContextConfig contextConfig) {
        this.contextConfig = contextConfig;
    }
}
