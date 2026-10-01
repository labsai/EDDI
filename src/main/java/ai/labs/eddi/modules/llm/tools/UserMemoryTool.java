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
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
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
    private static final String GDPR_KEY_PREFIX = IUserMemoryStore.RESERVED_KEY_PREFIX;
    /** Marker for a global memory whose entry records no owning agent. */
    private static final String UNKNOWN_GLOBAL_OWNER = "(unknown)";
    private static final String TURN_DISCARDED_REFUSAL = "⚠️ This turn has been cancelled; nothing was stored or changed.";
    private static final String RESERVED_KEY_REFUSAL = "⚠️ Keys starting with '%s' are reserved for GDPR bookkeeping and cannot be "
            + "written or forgotten by an agent.";

    private final IUserMemoryStore store;
    private final String userId;
    private final String agentId;
    private final String conversationId;
    private final List<String> groupIds;
    private final AgentConfiguration.UserMemoryConfig config;
    private final AgentConfiguration.Guardrails guardrails;
    private final BooleanSupplier turnDiscarded;
    private int writesThisTurn = 0;

    public UserMemoryTool(IUserMemoryStore store, String userId, String agentId, String conversationId, List<String> groupIds,
            AgentConfiguration.UserMemoryConfig config) {
        this(store, userId, agentId, conversationId, groupIds, config, () -> false);
    }

    /**
     * @param turnDiscarded
     *            whether the turn this tool serves has been cancelled — by the
     *            user, or by a GDPR erasure of the user. Checked before every
     *            write: this tool writes straight to the store mid-turn, so a turn
     *            told to stop would otherwise still recreate memories the erasure
     *            had just deleted while its tool loop wound down.
     */
    public UserMemoryTool(IUserMemoryStore store, String userId, String agentId, String conversationId, List<String> groupIds,
            AgentConfiguration.UserMemoryConfig config, BooleanSupplier turnDiscarded) {
        this.turnDiscarded = turnDiscarded != null ? turnDiscarded : () -> false;
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

        // Guardrail: key length
        if (key == null || key.isBlank()) {
            return "⚠️ Key must not be empty.";
        }
        if (key.length() > guardrails.getMaxKeyLength()) {
            return "⚠️ Key too long. Maximum %d characters.".formatted(guardrails.getMaxKeyLength());
        }
        // Checked here as well as in the store so the model gets a readable refusal
        // instead of a store failure. Without it a model could write
        // _gdpr_processing_restricted=true and lock its own user out with a GDPR 403
        // no admin had applied — or, as a global entry, overwrite the admin's real
        // restriction row in place.
        if (IUserMemoryStore.isReservedKey(key.trim())) {
            return RESERVED_KEY_REFUSAL.formatted(IUserMemoryStore.RESERVED_KEY_PREFIX);
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

        // Resolve visibility: the model's choice if it named a valid one, otherwise
        // the configured defaultVisibility (itself falling back to self).
        Visibility vis;
        if (visibility != null && !visibility.isBlank()) {
            vis = parseVisibility(visibility, defaultVisibility());
        } else {
            vis = defaultVisibility();
        }

        // Guardrail: the resolved visibility must be permitted for this agent. By
        // default only 'self' is allowed, so a prompt-injected model cannot broadcast
        // a memory to every other agent (global) or to the group unless an operator
        // opted in via guardrails.allowedVisibilities. The configured
        // defaultVisibility is always permitted so setting it is never self-blocking.
        if (!allowedVisibilities().contains(vis)) {
            return "⚠️ Visibility '%s' is not permitted for this agent. Allowed: %s".formatted(vis,
                    allowedVisibilities().stream().map(Enum::name).collect(Collectors.joining(", ")));
        }

        if (turnDiscarded.getAsBoolean()) {
            return TURN_DISCARDED_REFUSAL;
        }

        try {
            String trimmedKey = key.trim();
            // The entry this write would land on, resolved with the stores' upsert
            // identity. Knowing it up front answers two questions the write used to get
            // wrong: is this a no-op, and does it add a row?
            UserMemoryEntry existing = findUpsertTarget(trimmedKey, vis);

            // Re-stating a fact that is already stored, unchanged, is not a write. Models
            // do it constantly — they do not see their earlier tool calls, so they save
            // the same facts again each turn — and counting it spent the whole
            // maxWritesPerTurn budget on re-saves, refusing the one new fact the user had
            // just shared.
            if (existing != null && Objects.equals(existing.value(), value) && Objects.equals(existing.category(), normalizedCategory)
                    && existing.visibility() == vis
                    && (vis != Visibility.group || Set.copyOf(existing.groupIds()).equals(Set.copyOf(groupIds)))) {
                return "✅ Already remembered (unchanged): %s = %s [%s, %s]".formatted(trimmedKey, value, normalizedCategory, vis);
            }

            // Guardrail: a 'global' write must not silently overwrite the value of a
            // global memory another agent owns. The store preserves the original owner
            // but still applies the new value, so refuse here unless configured to
            // allow it. This early check only spares a doomed write the capacity
            // eviction below; the decision that counts is the owner-conditional write,
            // because two agents can both pass this check for a key neither holds yet.
            boolean ownedGlobalWrite = vis == Visibility.global && !guardrails.isAllowGlobalKeyOverwrite();
            if (ownedGlobalWrite) {
                if (agentId == null || agentId.isBlank()) {
                    return globalKeyRefusal(trimmedKey);
                }
                String otherOwner = globalKeyOwnedByAnotherAgent(trimmedKey);
                if (otherOwner != null) {
                    return globalKeyRefusal(trimmedKey);
                }
            }

            // Guardrail: write-rate limit
            if (writesThisTurn >= guardrails.getMaxWritesPerTurn()) {
                return ("⚠️ Maximum writes per turn (%d) reached. Do not retry this turn — tell the user which facts were not "
                        + "saved; they can be saved in the next turn.").formatted(guardrails.getMaxWritesPerTurn());
            }

            // Check capacity — returns a user-facing message when the write must not
            // proceed, null when there is room (possibly after evicting). An update of an
            // existing entry adds no row and always has room.
            String capacityRefusal = existing != null ? null : enforceCapacity();
            if (capacityRefusal != null) {
                return capacityRefusal;
            }

            UserMemoryEntry entry = UserMemoryEntry.fromToolCall(userId, agentId, conversationId, groupIds, trimmedKey, value, normalizedCategory,
                    vis);
            if (ownedGlobalWrite) {
                if (!store.upsertIfOwnedBy(entry, agentId)) {
                    // Another agent claimed the key between the check above and this
                    // write, or holds it with no recorded owner.
                    return globalKeyRefusal(trimmedKey);
                }
            } else {
                store.upsert(entry);
            }
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
        // A model must not be able to lift an Art. 18 restriction either: deleting the
        // row is what the admin unrestrict endpoint does, with an audit entry.
        if (IUserMemoryStore.isReservedKey(key.trim())) {
            return RESERVED_KEY_REFUSAL.formatted(IUserMemoryStore.RESERVED_KEY_PREFIX);
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
            if (turnDiscarded.getAsBoolean()) {
                return TURN_DISCARDED_REFUSAL;
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
     * The agent's configured default visibility, parsed, falling back to
     * {@link Visibility#self} for a null/blank/unrecognised value.
     */
    private Visibility defaultVisibility() {
        return parseVisibility(config.getDefaultVisibility(), Visibility.self);
    }

    /**
     * Parses a visibility name case-insensitively, returning {@code fallback} for a
     * null, blank or unrecognised value.
     */
    private static Visibility parseVisibility(String value, Visibility fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Visibility.valueOf(value.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * The set of visibilities the {@code rememberFact} tool may write: the
     * configured {@code guardrails.allowedVisibilities} (default {@code [self]}),
     * always unioned with the configured {@code defaultVisibility} so that setting
     * a default can never make every write fail. Unrecognised names are ignored.
     */
    private Set<Visibility> allowedVisibilities() {
        var allowed = EnumSet.noneOf(Visibility.class);
        List<String> configured = guardrails.getAllowedVisibilities();
        if (configured != null) {
            for (String name : configured) {
                Visibility parsed = parseVisibility(name, null);
                if (parsed != null) {
                    allowed.add(parsed);
                }
            }
        }
        if (allowed.isEmpty()) {
            allowed.add(Visibility.self);
        }
        // A configured default must always be writable.
        allowed.add(defaultVisibility());
        return allowed;
    }

    private static String globalKeyRefusal(String key) {
        return ("⚠️ A global memory with key '%s' is owned by another agent (or its owner is unknown) "
                + "and cannot be overwritten. Use a different key, or store it with 'self' visibility.").formatted(key);
    }

    /**
     * If a {@code global} memory with this key already exists and is not provably
     * owned by this agent, returns a non-null marker (the owning agent's id, or
     * {@link #UNKNOWN_GLOBAL_OWNER} when the entry records no owner — legacy or
     * migrated data); otherwise {@code null}. Used to refuse a cross-agent global
     * value overwrite. An entry of unknown ownership fails closed: the guard cannot
     * show this agent owns it, so only {@code allowGlobalKeyOverwrite} permits the
     * write.
     */
    private String globalKeyOwnedByAnotherAgent(String key) throws IResourceStore.ResourceStoreException {
        for (UserMemoryEntry entry : store.getAllEntries(userId)) {
            if (entry != null && entry.visibility() == Visibility.global && key.equals(entry.key())) {
                String owner = entry.sourceAgentId();
                if (owner == null || owner.isBlank()) {
                    return UNKNOWN_GLOBAL_OWNER;
                }
                if (!owner.equals(agentId)) {
                    return owner;
                }
            }
        }
        return null;
    }

    /**
     * The stored entry an upsert of {@code key} with {@code visibility} by this
     * agent would overwrite, or {@code null} when it would insert. Mirrors the
     * stores' identity: one shared document per {@code (userId, key)} for
     * {@code global}; one per {@code (userId, key, sourceAgentId)} among the
     * agent's non-global entries otherwise.
     */
    private UserMemoryEntry findUpsertTarget(String key, Visibility visibility) throws IResourceStore.ResourceStoreException {
        for (UserMemoryEntry entry : store.getAllEntries(userId)) {
            if (!key.equals(entry.key())) {
                continue;
            }
            boolean entryIsGlobal = entry.visibility() == Visibility.global;
            boolean sameIdentity = visibility == Visibility.global
                    ? entryIsGlobal
                    : !entryIsGlobal && agentId != null && agentId.equals(entry.sourceAgentId());
            if (sameIdentity) {
                return entry;
            }
        }
        return null;
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
    private String enforceCapacity() throws IResourceStore.ResourceStoreException {
        int cap = config.getMaxEntriesPerUser();
        long count = store.countEntries(userId);
        if (count < cap) {
            return null;
        }

        String onCap = config.getOnCapReached();
        if (ON_CAP_REJECT.equals(onCap)) {
            return ("⚠️ Memory capacity reached (%d/%d). Cannot store NEW facts — existing ones can still be updated, or removed "
                    + "with forgetFact.").formatted(count, cap);
        }
        if (!ON_CAP_EVICT_OLDEST.equals(onCap)) {
            return ("⚠️ Memory capacity reached (%d/%d) and onCapReached='%s' is not a known mode "
                    + "(expected '%s' or '%s'). Refusing the write.").formatted(count, cap, onCap, ON_CAP_EVICT_OLDEST, ON_CAP_REJECT);
        }

        List<UserMemoryEntry> evictable = evictableEntries();

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
