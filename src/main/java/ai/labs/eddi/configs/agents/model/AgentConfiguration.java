/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.model;
import ai.labs.eddi.configs.agents.crypto.AgentPublicKey;

import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;

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
    /**
     * @deprecated Since 6.1.0. Use standalone
     *             {@code ChannelIntegrationConfiguration} documents instead. Legacy
     *             connectors are auto-migrated at startup by
     *             {@code ChannelConnectorMigration}. This field will be removed in
     *             a future release.
     */
    @Deprecated(since = "6.1.0", forRemoval = true)
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

    /**
     * Memory management policy for this agent. Controls how failed task data is
     * handled in conversation history.
     *
     * @since 6.0.0
     */
    private MemoryPolicy memoryPolicy;

    /**
     * Session management configuration. Controls automatic checkpointing before
     * state-changing tool executions and conversation forking.
     *
     * @since 6.0.0
     */
    private SessionManagement sessionManagement;

    /**
     * @deprecated Since 6.1.0. Replaced by {@code ChannelIntegrationConfiguration}
     *             with multi-target routing support.
     */
    @Deprecated(since = "6.1.0", forRemoval = true)
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

    @Deprecated(since = "6.1.0", forRemoval = true)
    public List<ChannelConnector> getChannels() {
        return channels;
    }

    @Deprecated(since = "6.1.0", forRemoval = true)
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

    public MemoryPolicy getMemoryPolicy() {
        return memoryPolicy;
    }

    public void setMemoryPolicy(MemoryPolicy memoryPolicy) {
        this.memoryPolicy = memoryPolicy;
    }

    public SessionManagement getSessionManagement() {
        return sessionManagement;
    }

    public void setSessionManagement(SessionManagement sessionManagement) {
        this.sessionManagement = sessionManagement;
    }

    /**
     * Human-in-the-loop (HITL) configuration. Controls approval timeouts and
     * timeout policies for paused conversations.
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

    /**
     * Cryptographic identity for an agent. The public key is stored in the agent
     * configuration; the private key is in SecretsVault.
     *
     * @since 6.0.0
     */
    public static class AgentIdentity {
        private String agentDid;
        private String publicKey;
        /**
         * Versioned key list for rotation. If empty, falls back to {@code publicKey}.
         */
        private List<AgentPublicKey> keys = new ArrayList<>();

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

        public List<AgentPublicKey> getKeys() {
            return keys;
        }

        public void setKeys(List<AgentPublicKey> keys) {
            this.keys = keys != null ? keys : new ArrayList<>();
        }

        /**
         * Get the key for a specific version. Falls back to {@code publicKey} if no
         * versioned keys exist.
         *
         * @param version
         *            the key version to find
         * @return the public key string, or null if not found
         */
        public String getKeyForVersion(int version) {
            if (keys == null || keys.isEmpty()) {
                return version == 0 ? publicKey : null;
            }
            String versioned = keys.stream()
                    .filter(k -> k.version() == version)
                    .map(AgentPublicKey::publicKeyB64)
                    .findFirst()
                    .orElse(null);
            // Version 0 means "signed before key versioning existed", so the legacy
            // single publicKey field IS its key — even once a versioned list has been
            // added. Without this, onboarding a keys list starting at v1 (the normal
            // rotation path) made every pre-rotation entry resolve to null, and peer
            // verification reported authentic entries as unverifiable. The old
            // getKeyValidAt lookup ended `.orElse(publicKey)` and so never had the
            // problem; the version-exact lookup that replaced it dropped the fallback
            // along with the rotation-window bug it was fixing.
            return versioned != null || version != 0 ? versioned : publicKey;
        }

        /**
         * Get the key that is valid at a given epoch millisecond. Returns the
         * highest-version valid key.
         *
         * @param epochMs
         *            the point in time
         * @return the public key string, or falls back to {@code publicKey}
         */
        public String getKeyValidAt(long epochMs) {
            if (keys == null || keys.isEmpty()) {
                return publicKey;
            }
            return keys.stream()
                    .filter(k -> k.isValidAt(epochMs))
                    .reduce((a, b) -> a.version() > b.version() ? a : b)
                    .map(AgentPublicKey::publicKeyB64)
                    .orElse(publicKey);
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
        private boolean requirePeerVerification = false;

        public boolean isSignInterAgentMessages() {
            return signInterAgentMessages;
        }

        public void setSignInterAgentMessages(boolean signInterAgentMessages) {
            this.signInterAgentMessages = signInterAgentMessages;
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
     * Background Dream consolidation configuration.
     * <p>
     * Dream runs through the regular cluster-aware schedule machinery: a
     * {@code ScheduleConfiguration} carrying the metadata marker
     * {@code {"dreamType": "dream_consolidation"}} plus the target {@code agentId}
     * and {@code userId} is dispatched by {@code ScheduleFireExecutor} to
     * {@code DreamService}, which resolves this block off the agent and runs the
     * cycle. {@link #getSchedule()} is the cron expression such a schedule should
     * use.
     */
    public static class DreamConfig {
        private boolean enabled = false;
        private String schedule = "0 3 * * *";
        private boolean detectContradictions = true;
        /**
         * Contradiction resolution strategy. Reserved for future use — the current V1
         * detector only counts and logs contradictions without resolving them.
         */
        private String contradictionResolution = "keep_newest";
        private int pruneStaleAfterDays = 90;
        private boolean summarizeInteractions = false;
        private String llmProvider = "anthropic";
        private String llmModel = "claude-sonnet-4-6";
        private double maxCostPerRun = 0.50;
        private int batchSize = 50;
        private int maxUsersPerRun = 1000;

        /** Minimum entries in a group before summarization triggers. */
        private int summarizeMinEntries = 5;

        /** Target number of entries per group after consolidation. */
        private int summarizeTargetEntries = 2;

        /**
         * Grouping strategy: "category" (group by fact/preference/context) or "all"
         * (single group).
         */
        private String summarizeGroupBy = "category";

        /**
         * Whether to sub-group by sourceAgentId before consolidating. true = entries
         * from different agents stay separate (preserves provenance). false = entries
         * from all agents consolidated together (better compression).
         * <p>
         * <b>Note:</b> this switch never applies to {@code self}-scoped memories. Those
         * are always sub-grouped by {@code sourceAgentId}, because merging them across
         * agents would produce a single entry readable by agents that never had access
         * to the originals.
         */
        private boolean preserveAgentProvenance = false;

        /**
         * Whether this agent's dream cycle may act on memories written by
         * <em>other</em> agents.
         * <p>
         * Every knob in this block — {@link #getPruneStaleAfterDays()}, the grouping
         * strategy, the consolidation model and its endpoint — comes from exactly one
         * agent. Letting that agent's cycle delete or rewrite another agent's memories
         * means a retention value their owner never configured decides when their data
         * disappears, and (with {@link #isSummarizeInteractions()} on) their text is
         * sent to this agent's provider. So the default is {@code false}: the cycle
         * only touches entries whose {@code sourceAgentId} is the firing agent, the
         * same ownership rule {@code UserMemoryTool} applies before evicting.
         * <p>
         * Set to {@code true} for a dedicated housekeeping agent that is meant to
         * maintain the user's whole memory set across agents — the cross-agent
         * consolidation {@link #isPreserveAgentProvenance()}{@code =false} describes.
         * Entries without a {@code sourceAgentId} (legacy/migrated rows) are only in
         * scope in this mode, since no agent owns them.
         *
         * @since 6.1.0
         */
        private boolean crossAgentMaintenance = false;

        /**
         * Model parameters for the consolidation LLM — {@code apiKey}, {@code baseUrl},
         * {@code temperature}, … — passed through to {@code ChatModelRegistry} exactly
         * like an LLM task's {@code parameters} block, so {@code ${vault:...}} and
         * {@code ${vars:...}} references resolve the same way.
         * <p>
         * Dream is a background job with no parent LLM task, so unlike the rolling
         * conversation summary it has nothing to inherit credentials from — they must
         * be configured here. Example:
         *
         * <pre>
         * "parameters": { "apiKey": "${vault:anthropic-api-key}" }
         * </pre>
         */
        private Map<String, String> parameters = new HashMap<>();

        /**
         * @deprecated Since 6.1.0. Superseded by {@link #getMaxCostPerRun()}, which is
         *             the real budget: a call count says nothing about spend, because
         *             different consolidations cost vastly different amounts. It is
         *             <em>still enforced as a secondary backstop</em> whenever a stored
         *             configuration actually carries the field
         *             ({@link #isMaxSummarizationCallsSet()}) — silently discarding a
         *             ceiling an operator wrote would let a config that says "at most 3
         *             calls" make hundreds. Configurations that never set it are
         *             bounded by the dollar budget alone, so this field's default value
         *             never caps anything on its own.
         */
        @Deprecated(since = "6.1.0", forRemoval = true)
        private int maxSummarizationCalls = 10;

        /**
         * Whether {@link #maxSummarizationCalls} was explicitly configured, as opposed
         * to sitting at its default. Set by the setter, which Jackson calls only when
         * the property is present in the stored/imported JSON — that is what lets the
         * deprecated ceiling stay honoured for the configs that declare it without
         * imposing it on the ones that do not.
         */
        private boolean maxSummarizationCallsSet = false;

        /**
         * LLM instructions for memory consolidation. Customizable by the agent
         * designer. Entries are appended as JSON after this prompt.
         */
        private String summarizationPrompt = "You are a memory consolidation assistant. Given a list of remembered facts "
                + "about a user, distill them into fewer, non-redundant entries. Preserve all "
                + "important details. Remove duplicates and merge related facts. Each entry "
                + "should be a single, clear statement.\n\n"
                + "Respond ONLY with a JSON array: [{\"key\": \"...\", \"value\": \"...\"}]\n"
                + "Do not add any text outside the JSON array.";

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

        public int getSummarizeMinEntries() {
            return summarizeMinEntries;
        }

        public void setSummarizeMinEntries(int summarizeMinEntries) {
            this.summarizeMinEntries = summarizeMinEntries;
        }

        public int getSummarizeTargetEntries() {
            return summarizeTargetEntries;
        }

        /**
         * Plain assignment — the {@code >= 1} rule is enforced on the WRITE path, by
         * {@code AgentStore.create}/{@code update}, not here.
         * <p>
         * Jackson calls this setter on every MongoDB read, ZIP import and instance
         * sync, so throwing from it made any agent already stored with
         * {@code summarizeTargetEntries: 0} — legal when it was written — permanently
         * unreadable: not deployable, not exportable, and not even repairable by PUT,
         * because the read happens first. See {@code AbstractResourceStore.validate}:
         * "a document already in the database must keep loading even if the rules
         * tightened".
         */
        public void setSummarizeTargetEntries(int summarizeTargetEntries) {
            this.summarizeTargetEntries = summarizeTargetEntries;
        }

        public String getSummarizeGroupBy() {
            return summarizeGroupBy;
        }

        public void setSummarizeGroupBy(String summarizeGroupBy) {
            this.summarizeGroupBy = summarizeGroupBy;
        }

        public boolean isPreserveAgentProvenance() {
            return preserveAgentProvenance;
        }

        public void setPreserveAgentProvenance(boolean preserveAgentProvenance) {
            this.preserveAgentProvenance = preserveAgentProvenance;
        }

        public boolean isCrossAgentMaintenance() {
            return crossAgentMaintenance;
        }

        public void setCrossAgentMaintenance(boolean crossAgentMaintenance) {
            this.crossAgentMaintenance = crossAgentMaintenance;
        }

        public Map<String, String> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, String> parameters) {
            this.parameters = parameters != null ? parameters : new HashMap<>();
        }

        /**
         * @deprecated Since 6.1.0. Secondary backstop only, and only when
         *             {@link #isMaxSummarizationCallsSet()} — see
         *             {@link #getMaxCostPerRun()} for the real budget.
         */
        @Deprecated(since = "6.1.0", forRemoval = true)
        public int getMaxSummarizationCalls() {
            return maxSummarizationCalls;
        }

        /**
         * @deprecated Since 6.1.0. Prefer {@link #setMaxCostPerRun(double)}. Calling
         *             this marks the ceiling as explicitly configured, which keeps it
         *             enforced as a backstop until the field is removed.
         */
        @Deprecated(since = "6.1.0", forRemoval = true)
        public void setMaxSummarizationCalls(int maxSummarizationCalls) {
            this.maxSummarizationCalls = maxSummarizationCalls;
            this.maxSummarizationCallsSet = true;
        }

        /**
         * True when {@link #getMaxSummarizationCalls()} was explicitly configured
         * (present in the stored/imported JSON, or set programmatically) rather than
         * left at its default. Never serialized — it is derived from the presence of
         * {@code maxSummarizationCalls}, so it survives an export/import round trip
         * without adding a field to stored agent configurations.
         *
         * @deprecated Since 6.1.0, together with the ceiling it guards.
         */
        @JsonIgnore
        @Deprecated(since = "6.1.0", forRemoval = true)
        public boolean isMaxSummarizationCallsSet() {
            return maxSummarizationCallsSet;
        }

        public String getSummarizationPrompt() {
            return summarizationPrompt;
        }

        public void setSummarizationPrompt(String summarizationPrompt) {
            this.summarizationPrompt = summarizationPrompt;
        }
    }

    /**
     * Memory management policy. Currently contains strict write discipline
     * settings; will be extended in future phases with context selection,
     * auto-compaction, and consolidation.
     *
     * @since 6.0.0
     */
    public static class MemoryPolicy {
        private StrictWriteDiscipline strictWriteDiscipline = new StrictWriteDiscipline();

        public StrictWriteDiscipline getStrictWriteDiscipline() {
            return strictWriteDiscipline;
        }

        public void setStrictWriteDiscipline(StrictWriteDiscipline strictWriteDiscipline) {
            this.strictWriteDiscipline = strictWriteDiscipline;
        }

        /**
         * Returns true if strict write discipline is enabled and its mode is not
         * "keep_all" (which preserves backwards-compatible behavior).
         * <p>
         * {@code @JsonIgnore} because it is derived from {@code strictWriteDiscipline}
         * rather than stored alongside it. Jackson wrote an {@code effectivelyEnabled}
         * key that nothing could read back, which turned into a 400 on every agent
         * configuration EDDI itself serializes once
         * {@code StrictConfigurationBodyInterceptor} began rejecting unknown keys.
         */
        @JsonIgnore
        public boolean isEffectivelyEnabled() {
            return strictWriteDiscipline != null && strictWriteDiscipline.isEnabled()
                    && !"keep_all".equals(strictWriteDiscipline.getOnFailure());
        }
    }

    /**
     * Strict write discipline: on task failure, raw data is marked uncommitted and
     * an error digest is injected into the conversation output.
     * <p>
     * Modes:
     * <ul>
     * <li>{@code digest} (default) — raw data hidden, concise error summary visible
     * to LLM as a special output type</li>
     * <li>{@code exclude_all} — raw data hidden, no error info visible</li>
     * <li>{@code keep_all} — everything visible (backwards-compatible)</li>
     * </ul>
     *
     * @since 6.0.0
     */
    public static class StrictWriteDiscipline {
        private boolean enabled = false;
        private String onFailure = "digest";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getOnFailure() {
            return onFailure;
        }

        public void setOnFailure(String onFailure) {
            this.onFailure = onFailure;
        }
    }

    /**
     * Session management configuration. Controls automatic checkpointing.
     * <p>
     * <strong>Note:</strong> Conversation forking (session branching) is planned
     * for a future release. When implemented, forking config fields will be added
     * here alongside the implementation.
     * <p>
     * <strong>Current wiring status — read before relying on these fields.</strong>
     * A checkpoint captures the conversation PROPERTIES only; rolling one back does
     * not restore steps, outputs or external side-effects (see
     * {@code MemorySnapshotService#rollbackToCheckpoint}). Checkpointing itself is
     * performed unconditionally by {@code AgentOrchestrator} before every tool call
     * — {@link AutoSnapshot#isEnabled()} and {@link AutoSnapshot#getTriggerOn()}
     * are NOT consulted yet, and {@link #getMaxCheckpointsPerConversation()} is
     * honoured only where a caller passes it to
     * {@code MemorySnapshotService#createCheckpoint(..., int)}. No production
     * caller does: {@code AgentOrchestrator} uses the 3-arg overload because
     * {@code SessionManagement} has no slot on {@code IConversationMemory} (unlike
     * {@link UserMemoryConfig} and {@link MemoryPolicy}), so auto-checkpointing
     * always prunes to the service default of 10.
     *
     * @since 6.0.0
     */
    public static class SessionManagement {
        private AutoSnapshot autoSnapshot;
        private int maxCheckpointsPerConversation = 10;

        public AutoSnapshot getAutoSnapshot() {
            return autoSnapshot;
        }

        public void setAutoSnapshot(AutoSnapshot autoSnapshot) {
            this.autoSnapshot = autoSnapshot;
        }

        public int getMaxCheckpointsPerConversation() {
            return maxCheckpointsPerConversation;
        }

        public void setMaxCheckpointsPerConversation(int maxCheckpointsPerConversation) {
            this.maxCheckpointsPerConversation = maxCheckpointsPerConversation;
        }

        /**
         * Auto-snapshot configuration. When enabled, checkpoints are created
         * automatically before state-changing tool executions.
         * <p>
         * <strong>Reserved — not yet honoured by the engine.</strong>
         * Auto-checkpointing currently runs before every tool call regardless of these
         * values; see the wiring note on {@link SessionManagement}.
         */
        public static class AutoSnapshot {
            private boolean enabled = false;
            /** Events that trigger auto-snapshots: "before_tool", "before_action" */
            private List<String> triggerOn = new ArrayList<>();

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public List<String> getTriggerOn() {
                return triggerOn;
            }

            public void setTriggerOn(List<String> triggerOn) {
                this.triggerOn = triggerOn;
            }
        }
    }

    /**
     * HITL approval timeout configuration. Controls what happens when a
     * human-in-the-loop approval is not provided within the configured duration.
     *
     * @since 6.0.0
     */
    public static class HitlConfig {
        /** ISO-8601 duration (e.g., "PT30S"), null = indefinite. */
        private String approvalTimeout;
        private HitlTimeoutPolicy timeoutPolicy = HitlTimeoutPolicy.WAIT_INDEFINITELY;
        /**
         * Designer-supplied reason shown to approvers in pending-approval listings and
         * approval-status (e.g. "Deletion requires manager sign-off"). Answers "what am
         * I approving?" — falls back to a generic reason when absent.
         */
        private String pauseReason;

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
            // JSON "timeoutPolicy": null must not wipe the default (mirrors the
            // group-level HitlConfig setters)
            if (timeoutPolicy != null) {
                this.timeoutPolicy = timeoutPolicy;
            }
        }

        public String getPauseReason() {
            return pauseReason;
        }

        public void setPauseReason(String pauseReason) {
            this.pauseReason = pauseReason;
        }

        /**
         * Agent-level default tool-approval gating (tool-level HITL). Applies to every
         * LLM task in the agent unless a task overrides it with its own
         * {@code toolApprovals}. Absent = no tool gating.
         *
         * @since 6.0.0
         */
        private ToolApprovalsConfig toolApprovals;

        public ToolApprovalsConfig getToolApprovals() {
            return toolApprovals;
        }

        public void setToolApprovals(ToolApprovalsConfig toolApprovals) {
            this.toolApprovals = toolApprovals;
        }
    }
}
