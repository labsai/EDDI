/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.IResourceStore;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.inject.Vetoed;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * LLM tool for managing persistent user memories. Constructed per-invocation by
 * {@code AgentOrchestrator} with the conversation's userId, agentId, groupIds,
 * and memory configuration.
 *
 * <p>
 * The LLM can call these tools to remember facts about the user, recall
 * previously remembered facts, forget specific memories, or list all known
 * memories.
 *
 * @author ginccc
 * @since 6.0.0
 */
@Vetoed // Instantiated per-invocation by AgentOrchestrator — must NOT be a CDI bean
public class UserMemoryTool {

    private static final Logger LOGGER = Logger.getLogger(UserMemoryTool.class);

    /** {@code onCapReached} mode: refuse the write once the cap is reached. */
    private static final String ON_CAP_REJECT = "reject";
    /**
     * {@code onCapReached} mode: delete this agent's oldest entries to make room.
     */
    private static final String ON_CAP_EVICT_OLDEST = "evict_oldest";
    /** GDPR bookkeeping keys are never evicted (mirrors the retention sweep). */
    private static final String GDPR_KEY_PREFIX = "_gdpr_";

    private final IUserMemoryStore store;
    private final String userId;
    private final String agentId;
    private final String conversationId;
    private final List<String> groupIds;
    private final AgentConfiguration.UserMemoryConfig config;
    private final AgentConfiguration.Guardrails guardrails;
    private int writesThisTurn = 0;

    public UserMemoryTool(IUserMemoryStore store, String userId, String agentId, String conversationId, List<String> groupIds,
            AgentConfiguration.UserMemoryConfig config) {
        this.store = store;
        this.userId = userId;
        this.agentId = agentId;
        this.conversationId = conversationId;
        this.groupIds = groupIds != null ? groupIds : List.of();
        this.config = config;
        this.guardrails = config.getGuardrails();
    }

    @Tool("Remember a fact, preference, or context about the current user. "
            + "Use this when the user shares personal information worth remembering across conversations. "
            + "Categories: 'preference', 'fact', 'context'. Visibility: 'self' (this agent only), "
            + "'group' (agents in same group), 'global' (all agents).")
    public String rememberFact(@P("Short key name for the fact, e.g. 'favorite_color', 'dietary_restriction'") String key,
                               @P("The value to remember") String value, @P("Category: 'preference', 'fact', or 'context'") String category,
                               @P("Visibility: 'self', 'group', or 'global'. Default: 'self'") String visibility) {

        // Guardrail: write-rate limit
        if (writesThisTurn >= guardrails.getMaxWritesPerTurn()) {
            return "⚠️ Maximum writes per turn (%d) reached. Try again in the next turn.".formatted(guardrails.getMaxWritesPerTurn());
        }

        // Guardrail: key length
        if (key == null || key.isBlank()) {
            return "⚠️ Key must not be empty.";
        }
        if (key.length() > guardrails.getMaxKeyLength()) {
            return "⚠️ Key too long. Maximum %d characters.".formatted(guardrails.getMaxKeyLength());
        }

        // Guardrail: value length
        if (value != null && value.length() > guardrails.getMaxValueLength()) {
            return "⚠️ Value too long. Maximum %d characters.".formatted(guardrails.getMaxValueLength());
        }

        // Guardrail: category
        String normalizedCategory = UserMemoryEntry.normalizeCategory(category);
        if (!guardrails.getAllowedCategories().contains(normalizedCategory)) {
            return "⚠️ Category '%s' not allowed. Allowed: %s".formatted(category, guardrails.getAllowedCategories());
        }

        Visibility vis;
        try {
            vis = (visibility != null && !visibility.isBlank()) ? Visibility.valueOf(visibility.trim().toLowerCase()) : Visibility.self;
        } catch (IllegalArgumentException e) {
            vis = Visibility.self;
        }

        try {
            // Check capacity — returns a user-facing message when the write must not
            // proceed, null when there is room (possibly after evicting).
            String capacityRefusal = enforceCapacity(key.trim());
            if (capacityRefusal != null) {
                return capacityRefusal;
            }

            UserMemoryEntry entry = UserMemoryEntry.fromToolCall(userId, agentId, conversationId, groupIds, key.trim(), value, normalizedCategory,
                    vis);
            store.upsert(entry);
            writesThisTurn++;

            LOGGER.debugf("[MEMORY] Tool rememberFact: user='%s', key='%s', category='%s', visibility='%s'", userId, key, normalizedCategory, vis);

            return "✅ Remembered: %s = %s [%s, %s]".formatted(key, value, normalizedCategory, vis);

        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.errorf("[MEMORY] Failed to remember fact: %s", e.getMessage());
            return "❌ Failed to store memory: " + e.getMessage();
        }
    }

    @Tool("Recall all memories known about the current user that are visible to this agent. "
            + "Returns a formatted list of remembered facts, preferences, and context.")
    public String recallMemories() {
        try {
            List<UserMemoryEntry> entries = store.getVisibleEntries(userId, agentId, groupIds, config.getRecallOrder(), config.getMaxRecallEntries());

            if (entries.isEmpty()) {
                return "No memories found for this user.";
            }

            return entries.stream().map(e -> "• %s = %s [%s, %s]".formatted(e.key(), e.value(), e.category(), e.visibility()))
                    .collect(Collectors.joining("\n"));

        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.errorf("[MEMORY] Failed to recall memories: %s", e.getMessage());
            return "❌ Failed to recall memories: " + e.getMessage();
        }
    }

    @Tool("Search for a specific memory by key name or value content.")
    public String searchMemory(@P("Search query to filter memories by key or value") String query) {
        try {
            // filterEntries is scoped by userId ONLY — it happily returns another
            // agent's visibility:self entries about the same user. Re-apply the same
            // visibility rules the recall path uses before the LLM ever sees them.
            List<UserMemoryEntry> entries = store.filterEntries(userId, query).stream().filter(this::isVisibleToThisAgent).toList();

            if (entries.isEmpty()) {
                return "No memories matching '%s' found.".formatted(query);
            }

            return entries.stream().map(e -> "• %s = %s [%s, %s]".formatted(e.key(), e.value(), e.category(), e.visibility()))
                    .collect(Collectors.joining("\n"));

        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.errorf("[MEMORY] Failed to search memories: %s", e.getMessage());
            return "❌ Failed to search memories: " + e.getMessage();
        }
    }

    @Tool("Forget (delete) a specific memory for the current user by its key name.")
    public String forgetFact(@P("The key name of the memory to forget") String key) {
        if (key == null || key.isBlank()) {
            return "⚠️ Key must not be empty.";
        }

        try {
            // getByKey is scoped by userId ONLY and returns the FIRST match, which may
            // belong to a different agent. Only delete what this agent may see; if the
            // first match is not ours, look for this agent's own entry with that key
            // instead of deleting someone else's memory.
            UserMemoryEntry target = store.getByKey(userId, key).filter(this::isVisibleToThisAgent).orElse(null);
            if (target == null) {
                target = store.filterEntries(userId, key).stream().filter(entry -> key.equals(entry.key()))
                        .filter(this::isVisibleToThisAgent).findFirst().orElse(null);
            }
            if (target == null) {
                return "No memory with key '%s' found.".formatted(key);
            }

            store.deleteEntry(target.id());
            LOGGER.debugf("[MEMORY] Tool forgetFact: user='%s', key='%s'", userId, key);

            return "✅ Forgotten: %s".formatted(key);

        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.errorf("[MEMORY] Failed to forget fact: %s", e.getMessage());
            return "❌ Failed to forget memory: " + e.getMessage();
        }
    }

    /**
     * Whether this agent may see the given entry. Mirrors the store-side recall
     * scoping ({@code self(agentId) OR group(groupIds) OR global}) and additionally
     * always admits entries this agent itself created.
     * <p>
     * The store's untargeted query methods ({@code filterEntries},
     * {@code getByKey}) are scoped by {@code userId} alone because the
     * ownership-validated REST/admin surfaces legitimately need the unscoped view.
     * The LLM tool path must not inherit that: agent B must never read or delete
     * agent A's {@code visibility:self} memories about a shared user.
     */
    private boolean isVisibleToThisAgent(UserMemoryEntry entry) {
        if (entry == null) {
            return false;
        }
        if (agentId != null && agentId.equals(entry.sourceAgentId())) {
            return true;
        }
        Visibility visibility = entry.visibility() != null ? entry.visibility() : Visibility.self;
        return switch (visibility) {
            case global -> true;
            case group -> entry.groupIds() != null && entry.groupIds().stream().anyMatch(groupIds::contains);
            case self -> false;
        };
    }

    /**
     * Enforces {@code maxEntriesPerUser} according to {@code onCapReached}.
     *
     * @return a user-facing refusal message when the write must not proceed, or
     *         {@code null} when there is room (possibly after evicting)
     */
    private String enforceCapacity(String key) throws IResourceStore.ResourceStoreException {
        int cap = config.getMaxEntriesPerUser();
        long count = store.countEntries(userId);
        if (count < cap) {
            return null;
        }

        String onCap = config.getOnCapReached();
        if (ON_CAP_REJECT.equals(onCap)) {
            return "⚠️ Memory capacity reached (%d/%d). Cannot store more facts.".formatted(count, cap);
        }
        if (!ON_CAP_EVICT_OLDEST.equals(onCap)) {
            return ("⚠️ Memory capacity reached (%d/%d) and onCapReached='%s' is not a known mode "
                    + "(expected '%s' or '%s'). Refusing the write.").formatted(count, cap, onCap, ON_CAP_EVICT_OLDEST, ON_CAP_REJECT);
        }

        List<UserMemoryEntry> evictable = evictableEntries();
        if (evictable.stream().anyMatch(entry -> key.equals(entry.key()))) {
            // Updating an entry this agent already owns — no new row, so the cap is
            // untouched and nothing needs to be evicted.
            return null;
        }

        long required = count - cap + 1;
        int evicted = 0;
        for (UserMemoryEntry entry : evictable) {
            if (evicted >= required) {
                break;
            }
            store.deleteEntry(entry.id());
            evicted++;
            LOGGER.debugf("[MEMORY] Evicted oldest entry to stay within cap: user='%s', key='%s'", userId, entry.key());
        }

        if (evicted < required) {
            return ("⚠️ Memory capacity reached (%d/%d). Only %d of the %d entries needed could be evicted — the rest "
                    + "belong to other agents. Ask the user to remove memories, or raise 'maxEntriesPerUser'.").formatted(count, cap, evicted,
                            required);
        }
        return null;
    }

    /**
     * This agent's own entries, oldest first — the eviction candidates. Entries
     * owned by other agents are deliberately excluded: the cap is per user, but one
     * agent must not delete another agent's memories to make room for itself. GDPR
     * bookkeeping keys are excluded for the same reason the retention sweep
     * excludes them.
     */
    private List<UserMemoryEntry> evictableEntries() throws IResourceStore.ResourceStoreException {
        List<UserMemoryEntry> candidates = new ArrayList<>(store.getAllEntries(userId).stream()
                .filter(entry -> entry.id() != null)
                .filter(entry -> agentId != null && agentId.equals(entry.sourceAgentId()))
                .filter(entry -> entry.key() == null || !entry.key().startsWith(GDPR_KEY_PREFIX))
                .toList());
        candidates.sort(Comparator.comparing(UserMemoryEntry::updatedAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder())));
        return candidates;
    }
}
