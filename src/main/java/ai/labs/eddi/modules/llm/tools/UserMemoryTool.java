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
            // Check capacity
            long count = store.countEntries(userId);
            if (count >= config.getMaxEntriesPerUser()) {
                String onCap = config.getOnCapReached();
                if ("reject".equals(onCap)) {
                    return "⚠️ Memory capacity reached (%d/%d). Cannot store more facts.".formatted(count, config.getMaxEntriesPerUser());
                }
                // evict_oldest is handled at the DB level during recall (entries just get
                // pushed out of the window)
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
            List<UserMemoryEntry> entries = store.filterEntries(userId, query);

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
            var existing = store.getByKey(userId, key);
            if (existing.isEmpty()) {
                return "No memory with key '%s' found.".formatted(key);
            }

            store.deleteEntry(existing.get().id());
            LOGGER.debugf("[MEMORY] Tool forgetFact: user='%s', key='%s'", userId, key);

            return "✅ Forgotten: %s".formatted(key);

        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.errorf("[MEMORY] Failed to forget fact: %s", e.getMessage());
            return "❌ Failed to forget memory: " + e.getMessage();
        }
    }
}
