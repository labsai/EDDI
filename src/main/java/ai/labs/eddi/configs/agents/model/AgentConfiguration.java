package ai.labs.eddi.configs.agents.model;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

public class AgentConfiguration {
    @JsonAlias("packages")
    private List<URI> workflows = new ArrayList<>();
    private List<ChannelConnector> channels = new ArrayList<>();

    /**
     * Opt-in flag — when true, this agent is exposed via A2A protocol for
     * inter-agent discovery and communication.
     */
    private boolean a2aEnabled = false;

    /**
     * Skills to advertise in the A2A Agent Card. Each skill describes a capability
     * (e.g., "translation", "code-review"). If empty, a single default skill
     * derived from the agent description is used.
     */
    private List<String> a2aSkills = new ArrayList<>();

    /**
     * Structured capabilities for A2A capability registry. Each capability declares
     * a skill, optional attributes, and a confidence level. Used by
     * {@code CapabilityRegistryService} for runtime agent discovery and by the
     * {@code capabilityMatch} behavior rule condition for soft routing.
     *
     * @since 6.0.0
     */
    private List<Capability> capabilities = new ArrayList<>();

    /** Human-readable description for the A2A Agent Card */
    private String description;

    /**
     * Cryptographic identity for inter-agent trust. Auto-generated on agent
     * creation. The public key is stored here; the private key is in SecretsVault.
     *
     * @since 6.0.0
     */
    private AgentIdentity identity;

    /**
     * Security configuration for cryptographic signing.
     *
     * @since 6.0.0
     */
    private SecurityConfig security;

    public static class ChannelConnector {
        private URI type;
        private Map<String, String> config = new HashMap<>();

        public URI getType() {
            return type;
        }

        public void setType(URI type) {
            this.type = type;
        }

        public Map<String, String> getConfig() {
            return config;
        }

        public void setConfig(Map<String, String> config) {
            this.config = config;
        }
    }

    public List<URI> getWorkflows() {
        return workflows;
    }

    public void setWorkflows(List<URI> workflows) {
        this.workflows = workflows;
    }

    public List<ChannelConnector> getChannels() {
        return channels;
    }

    public void setChannels(List<ChannelConnector> channels) {
        this.channels = channels;
    }

    public boolean isA2aEnabled() {
        return a2aEnabled;
    }

    public void setA2aEnabled(boolean a2aEnabled) {
        this.a2aEnabled = a2aEnabled;
    }

    public List<String> getA2aSkills() {
        return a2aSkills;
    }

    public void setA2aSkills(List<String> a2aSkills) {
        this.a2aSkills = a2aSkills;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<Capability> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(List<Capability> capabilities) {
        this.capabilities = capabilities;
    }

    /**
     * Structured capability declaration for A2A discovery and soft routing.
     * <p>
     * Example JSON:
     *
     * <pre>
     * {
     *   "skill": "language-translation",
     *   "attributes": { "languages": "en,de,fr", "domain": "legal" },
     *   "confidence": "high"
     * }
     * </pre>
     *
     * @since 6.0.0
     */
    public static class Capability {
        private String skill;
        private Map<String, String> attributes = new HashMap<>();
        private String confidence = "medium";

        public Capability() {
        }

        public Capability(String skill, Map<String, String> attributes, String confidence) {
            this.skill = skill;
            this.attributes = attributes != null ? attributes : new HashMap<>();
            this.confidence = confidence != null ? confidence : "medium";
        }

        public String getSkill() {
            return skill;
        }

        public void setSkill(String skill) {
            this.skill = skill;
        }

        public Map<String, String> getAttributes() {
            return attributes;
        }

        public void setAttributes(Map<String, String> attributes) {
            this.attributes = attributes;
        }

        public String getConfidence() {
            return confidence;
        }

        public void setConfidence(String confidence) {
            this.confidence = confidence;
        }
    }

    public AgentIdentity getIdentity() {
        return identity;
    }

    public void setIdentity(AgentIdentity identity) {
        this.identity = identity;
    }

    public SecurityConfig getSecurity() {
        return security;
    }

    public void setSecurity(SecurityConfig security) {
        this.security = security;
    }

    /**
     * Cryptographic identity for an agent. The public key is stored in the agent
     * configuration; the private key is in SecretsVault.
     *
     * @since 6.0.0
     */
    public static class AgentIdentity {
        private String agentDid;
        private String publicKey;

        public AgentIdentity() {
        }

        public AgentIdentity(String agentDid, String publicKey) {
            this.agentDid = agentDid;
            this.publicKey = publicKey;
        }

        public String getAgentDid() {
            return agentDid;
        }

        public void setAgentDid(String agentDid) {
            this.agentDid = agentDid;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }
    }

    /**
     * Security configuration for cryptographic signing. All defaults are
     * {@code false} for backwards compatibility.
     *
     * @since 6.0.0
     */
    public static class SecurityConfig {
        private boolean signInterAgentMessages = false;
        private boolean signMcpInvocations = false;
        private boolean requirePeerVerification = false;

        public boolean isSignInterAgentMessages() {
            return signInterAgentMessages;
        }

        public void setSignInterAgentMessages(boolean signInterAgentMessages) {
            this.signInterAgentMessages = signInterAgentMessages;
        }

        public boolean isSignMcpInvocations() {
            return signMcpInvocations;
        }

        public void setSignMcpInvocations(boolean signMcpInvocations) {
            this.signMcpInvocations = signMcpInvocations;
        }

        public boolean isRequirePeerVerification() {
            return requirePeerVerification;
        }

        public void setRequirePeerVerification(boolean requirePeerVerification) {
            this.requirePeerVerification = requirePeerVerification;
        }
    }

    // === Persistent User Memory (Phase 11a) ===

    /**
     * Enables advanced memory features: LLM UserMemoryTool
     * (remember/recall/forget), Dream consolidation, write guardrails, and custom
     * recall settings. Basic longTerm property persistence always works regardless
     * of this flag.
     */
    private boolean enableMemoryTools = false;

    /**
     * Memory configuration — only meaningful when {@code enableMemoryTools} is
     * true.
     */
    private UserMemoryConfig userMemoryConfig;

    public boolean isEnableMemoryTools() {
        return enableMemoryTools;
    }

    public void setEnableMemoryTools(boolean enableMemoryTools) {
        this.enableMemoryTools = enableMemoryTools;
    }

    public UserMemoryConfig getUserMemoryConfig() {
        return userMemoryConfig;
    }

    public void setUserMemoryConfig(UserMemoryConfig userMemoryConfig) {
        this.userMemoryConfig = userMemoryConfig;
    }

    /**
     * Configuration for persistent user memory. Controls visibility defaults,
     * recall behavior, write guardrails, and background Dream consolidation.
     *
     * @since 6.0.0
     */
    public static class UserMemoryConfig {
        private String defaultVisibility = "self";
        private int maxRecallEntries = 50;
        private int maxEntriesPerUser = 500;
        private String onCapReached = "evict_oldest";
        private String recallOrder = "most_recent";
        private List<String> autoRecallCategories = List.of("preference", "fact");
        private Guardrails guardrails = new Guardrails();
        private DreamConfig dream = new DreamConfig();

        public String getDefaultVisibility() {
            return defaultVisibility;
        }

        public void setDefaultVisibility(String defaultVisibility) {
            this.defaultVisibility = defaultVisibility;
        }

        public int getMaxRecallEntries() {
            return maxRecallEntries;
        }

        public void setMaxRecallEntries(int maxRecallEntries) {
            this.maxRecallEntries = maxRecallEntries;
        }

        public int getMaxEntriesPerUser() {
            return maxEntriesPerUser;
        }

        public void setMaxEntriesPerUser(int maxEntriesPerUser) {
            this.maxEntriesPerUser = maxEntriesPerUser;
        }

        public String getOnCapReached() {
            return onCapReached;
        }

        public void setOnCapReached(String onCapReached) {
            this.onCapReached = onCapReached;
        }

        public String getRecallOrder() {
            return recallOrder;
        }

        public void setRecallOrder(String recallOrder) {
            this.recallOrder = recallOrder;
        }

        public List<String> getAutoRecallCategories() {
            return autoRecallCategories;
        }

        public void setAutoRecallCategories(List<String> autoRecallCategories) {
            this.autoRecallCategories = autoRecallCategories;
        }

        public Guardrails getGuardrails() {
            return guardrails;
        }

        public void setGuardrails(Guardrails guardrails) {
            this.guardrails = guardrails;
        }

        public DreamConfig getDream() {
            return dream;
        }

        public void setDream(DreamConfig dream) {
            this.dream = dream;
        }
    }

    /**
     * Write guardrails for memory operations (LLM tools, REST, MCP).
     */
    public static class Guardrails {
        private int maxKeyLength = 100;
        private int maxValueLength = 1000;
        private int maxWritesPerTurn = 10;
        private List<String> allowedCategories = List.of("preference", "fact", "context");

        public int getMaxKeyLength() {
            return maxKeyLength;
        }

        public void setMaxKeyLength(int maxKeyLength) {
            this.maxKeyLength = maxKeyLength;
        }

        public int getMaxValueLength() {
            return maxValueLength;
        }

        public void setMaxValueLength(int maxValueLength) {
            this.maxValueLength = maxValueLength;
        }

        public int getMaxWritesPerTurn() {
            return maxWritesPerTurn;
        }

        public void setMaxWritesPerTurn(int maxWritesPerTurn) {
            this.maxWritesPerTurn = maxWritesPerTurn;
        }

        public List<String> getAllowedCategories() {
            return allowedCategories;
        }

        public void setAllowedCategories(List<String> allowedCategories) {
            this.allowedCategories = allowedCategories;
        }
    }

    /**
     * Background Dream consolidation configuration. Uses
     * {@code ScheduleFireExecutor} with SERVICE trigger type.
     */
    public static class DreamConfig {
        private boolean enabled = false;
        private String schedule = "0 3 * * *";
        private boolean detectContradictions = true;
        private String contradictionResolution = "keep_newest";
        private int pruneStaleAfterDays = 90;
        private boolean summarizeInteractions = false;
        private String llmProvider = "anthropic";
        private String llmModel = "claude-sonnet-4-6";
        private double maxCostPerRun = 5.00;
        private int batchSize = 50;
        private int maxUsersPerRun = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSchedule() {
            return schedule;
        }

        public void setSchedule(String schedule) {
            this.schedule = schedule;
        }

        public boolean isDetectContradictions() {
            return detectContradictions;
        }

        public void setDetectContradictions(boolean detectContradictions) {
            this.detectContradictions = detectContradictions;
        }

        public String getContradictionResolution() {
            return contradictionResolution;
        }

        public void setContradictionResolution(String contradictionResolution) {
            this.contradictionResolution = contradictionResolution;
        }

        public int getPruneStaleAfterDays() {
            return pruneStaleAfterDays;
        }

        public void setPruneStaleAfterDays(int pruneStaleAfterDays) {
            this.pruneStaleAfterDays = pruneStaleAfterDays;
        }

        public boolean isSummarizeInteractions() {
            return summarizeInteractions;
        }

        public void setSummarizeInteractions(boolean summarizeInteractions) {
            this.summarizeInteractions = summarizeInteractions;
        }

        public String getLlmProvider() {
            return llmProvider;
        }

        public void setLlmProvider(String llmProvider) {
            this.llmProvider = llmProvider;
        }

        public String getLlmModel() {
            return llmModel;
        }

        public void setLlmModel(String llmModel) {
            this.llmModel = llmModel;
        }

        public double getMaxCostPerRun() {
            return maxCostPerRun;
        }

        public void setMaxCostPerRun(double maxCostPerRun) {
            this.maxCostPerRun = maxCostPerRun;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxUsersPerRun() {
            return maxUsersPerRun;
        }

        public void setMaxUsersPerRun(int maxUsersPerRun) {
            this.maxUsersPerRun = maxUsersPerRun;
        }
    }
}
