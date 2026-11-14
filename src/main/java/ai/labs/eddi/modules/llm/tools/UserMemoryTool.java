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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
    /**
     * Writes this turn — remembers and forgets alike — against
     * {@code maxWritesPerTurn}.
     */
    private final TurnCounter writesThisTurn;
    /**
     * Told the key of every fact {@code rememberFact} wrote. The store records a
     * write {@code ACCEPTED}, so the conversation drops the key from its
     * {@code contestedMemories} marker: the tool writes to the store directly and
     * the conversation property is left as it was, so the end-of-turn persistence
     * that lifts the marker for property writes never sees this one.
     */
    private final Consumer<String> keyWritten;

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
        this(store, userId, agentId, conversationId, groupIds, config, turnDiscarded, TurnCounter.local());
    }

    /**
     * @param writesThisTurn
     *            the turn's write count, which {@code maxWritesPerTurn} bounds.
     *            Pass {@link TurnCounter#onStep} so every instance built during one
     *            turn — one per cascade step, another on a HITL resume — spends the
     *            same budget; {@code null} counts per instance.
     */
    public UserMemoryTool(IUserMemoryStore store, String userId, String agentId, String conversationId, List<String> groupIds,
            AgentConfiguration.UserMemoryConfig config, BooleanSupplier turnDiscarded, TurnCounter writesThisTurn) {
        this(store, userId, agentId, conversationId, groupIds, config, turnDiscarded, writesThisTurn, key -> {
        });
    }

    /**
     * @param keyWritten
     *            called with the key of each fact this tool wrote, after the write
     *            stood; {@code null} for none
     */
    public UserMemoryTool(IUserMemoryStore store, String userId, String agentId, String conversationId, List<String> groupIds,
            AgentConfiguration.UserMemoryConfig config, BooleanSupplier turnDiscarded, TurnCounter writesThisTurn,
            Consumer<String> keyWritten) {
        this.keyWritten = keyWritten != null ? keyWritten : key -> {
        };
        this.writesThisTurn = writesThisTurn != null ? writesThisTurn : TurnCounter.local();
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
            // just shared. A contested or superseded entry is the exception: re-stating
            // it is the user confirming the value, and only a write records that.
            if (existing != null && existing.assessment() == UserMemoryEntry.Assessment.ACCEPTED && Objects.equals(existing.value(), value)
                    && Objects.equals(existing.category(), normalizedCategory)
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
            if (writeBudgetSpent()) {
                return writeLimitRefusal();
            }

            // Check capacity — returns a user-facing message when the write must not
            // proceed, null when there is room (possibly after evicting). An update of an
            // existing entry adds no row and always has room.
            // Eviction only PLANS here: the victims are chosen but nothing is deleted until
            // the write
            // has landed, so a turn cancelled on the way never costs the user a memory it
            // then fails
            // to replace.
            List<UserMemoryEntry> evictions = List.of();
            if (existing == null) {
                CapacityPlan plan = enforceCapacity();
                if (plan.refusal() != null) {
                    return plan.refusal();
                }
                evictions = plan.victims();
            }

            UserMemoryEntry entry = UserMemoryEntry.fromToolCall(userId, agentId, conversationId, groupIds, trimmedKey, value, normalizedCategory,
                    vis);
            // Checked again right before the write: the reads above take time, and a
            // GDPR erasure that started meanwhile has already signalled this turn.
            if (turnDiscarded.getAsBoolean()) {
                return TURN_DISCARDED_REFUSAL;
            }
            // An update goes through the update-only compare-and-set first: an erasure
            // that deleted the entry since it was read makes it match nothing, where an
            // upsert would have recreated the erased fact. Only when it no longer
            // applies (the entry changed or is gone) does the inserting write run, and
            // that write is then treated as an insert — taken back below if the turn
            // was cancelled meanwhile.
            boolean replaced = existing != null && existing.id() != null && store.replaceIfUnchanged(existing.id(), entry, existing.updatedAt());
            boolean inserted = !replaced;
            if (inserted) {
                if (ownedGlobalWrite) {
                    if (!store.upsertIfOwnedBy(entry, agentId)) {
                        // Another agent claimed the key between the check above and this
                        // write, or holds it with no recorded owner.
                        return globalKeyRefusal(trimmedKey);
                    }
                } else {
                    store.upsert(entry);
                }
            }
            if (inserted && turnDiscarded.getAsBoolean()) {
                // The turn was cancelled — by the user, or by an erasure of this user —
                // while the insert was in flight. An erasure signals before it deletes,
                // so an insert that lands after its sweep is caught here and taken back
                // rather than recreating a memory of an erased user. (An update made in
                // place is left alone: deleting it would destroy the fact it replaced,
                // and it landed on an entry the erasure had not yet removed, so the
                // erasure removes it anyway.)
                // Only this invocation's own write is taken back: the entry found under the key
                // may by
                // now be another turn's (it could have replaced this one), and deleting that
                // would lose
                // a fact it saved. The delete is conditional on the entry being unchanged since
                // it was
                // read, and is skipped when the entry is not recognisably this write. A
                // leftover is the
                // lesser harm: an erasure removes it anyway, and a cancelled turn's fact is not
                // lost data.
                UserMemoryEntry written = findUpsertTarget(trimmedKey, vis);
                if (written != null && written.id() != null && isThisWrite(written, entry)) {
                    store.deleteEntryIfUnchanged(written.id(), written.updatedAt());
                }
                return TURN_DISCARDED_REFUSAL;
            }
            if (existing == null) {
                // The write stood: now make the room it needed.
                evictPlanned(evictions);
                String overCap = settleCapacityAfterInsert(trimmedKey, vis);
                if (overCap != null) {
                    return overCap;
                }
            }
            writesThisTurn.increment();
            keyWritten.accept(trimmedKey);

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

            return entries.stream().map(UserMemoryTool::describe).collect(Collectors.joining("\n"));

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

            return entries.stream().map(UserMemoryTool::describe).collect(Collectors.joining("\n"));

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
            // belong to a different agent. Only delete what this agent may see and may
            // delete; if the first match is not that, look for this agent's own entry
            // with that key instead of deleting someone else's memory.
            UserMemoryEntry target = store.getByKey(userId, key).filter(this::isVisibleToThisAgent).filter(this::mayDelete).orElse(null);
            boolean foreignGlobalOnly = false;
            if (target == null) {
                // Every entry of the user, not filterEntries: that is a capped substring
                // search (IUserMemoryStore.MAX_FILTER_RESULTS, newest first), so for a
                // key whose terms many entries share, this agent's own older entry fell
                // outside the page and forgetting it answered "not found".
                List<UserMemoryEntry> visible = store.getAllEntries(userId).stream().filter(entry -> key.equals(entry.key()))
                        .filter(this::isVisibleToThisAgent).toList();
                target = visible.stream().filter(this::mayDelete).findFirst().orElse(null);
                foreignGlobalOnly = target == null && !visible.isEmpty();
            }
            if (foreignGlobalOnly) {
                return ("⚠️ The global memory with key '%s' is owned by another agent (or its owner is unknown) and cannot be "
                        + "deleted by this agent.").formatted(key);
            }
            if (target == null) {
                return "No memory with key '%s' found.".formatted(key);
            }
            // A deletion is a write: without the budget, one injected "forget everything"
            // deleted every memory of the user this agent owns in a single turn.
            if (writeBudgetSpent()) {
                return writeLimitRefusal();
            }
            if (turnDiscarded.getAsBoolean()) {
                return TURN_DISCARDED_REFUSAL;
            }

            // The linked versions go first, the forgotten entry last. Each delete is its
            // own statement on both stores, so a failure (or a crash) part way through
            // leaves whatever was not yet deleted; in this order that is the forgotten
            // entry and the versions still linked to it, which a retry finds and walks
            // again. The other way round, a failure after the first delete left older
            // versions whose replacement was gone — the loader served one again as the
            // only entry for the key, and a retry starting from it could not reach the
            // rest of the chain, so it answered "Forgotten" with a version still stored.
            int linked = forgetSupersessionChain(key, target);
            store.deleteEntry(target.id());
            writesThisTurn.increment();
            LOGGER.debugf("[MEMORY] Tool forgetFact: user='%s', key='%s', linkedVersions=%d", userId, key, linked);

            return "✅ Forgotten: %s".formatted(key);

        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.errorf("[MEMORY] Failed to forget fact: %s", e.getMessage());
            return "❌ Failed to forget memory: " + e.getMessage();
        }
    }

    private boolean writeBudgetSpent() {
        return writesThisTurn.get() >= guardrails.getMaxWritesPerTurn();
    }

    private String writeLimitRefusal() {
        return ("⚠️ Maximum writes per turn (%d) reached. Do not retry this turn — tell the user which facts were not "
                + "saved or forgotten; that can be done in the next turn.").formatted(guardrails.getMaxWritesPerTurn());
    }

    /**
     * Deletes the other versions of a forgotten fact: every entry under the same
     * key that is linked to {@code forgotten} through {@code supersededBy} — the
     * older entries it replaced, the newer one that replaced it, and so on along
     * the chain. Without this, forgetting the newest version left the
     * {@code SUPERSEDED} one behind, and the loader serves a superseded entry again
     * once it is the reader's only entry for the key — the user asked to forget the
     * fact and got its older value back.
     * <p>
     * Only entries this agent may see and delete, by the same two checks as the
     * entry itself, are touched: a version that belongs to another agent or scope
     * is left alone, and the chain is not followed through it. Each delete is
     * conditional on the entry being unchanged since it was read, so a version
     * rewritten meanwhile (which clears its {@code supersededBy}) survives. The
     * walk is bounded: every pass adds at least one entry or ends.
     *
     * @return how many linked versions were deleted
     */
    private int forgetSupersessionChain(String key, UserMemoryEntry forgotten) throws IResourceStore.ResourceStoreException {
        List<UserMemoryEntry> candidates = new ArrayList<>(store.getAllEntries(userId).stream()
                .filter(e -> e != null && e.id() != null && key.equals(e.key()) && !e.id().equals(forgotten.id()))
                .filter(this::isVisibleToThisAgent).filter(this::mayDelete).toList());
        Set<String> chainIds = new HashSet<>(Set.of(forgotten.id()));
        Set<String> replacements = new HashSet<>();
        if (forgotten.supersededBy() != null) {
            replacements.add(forgotten.supersededBy());
        }
        List<UserMemoryEntry> chain = new ArrayList<>();
        boolean grew = true;
        while (grew && !candidates.isEmpty()) {
            grew = false;
            for (var it = candidates.iterator(); it.hasNext();) {
                UserMemoryEntry e = it.next();
                if ((e.supersededBy() != null && chainIds.contains(e.supersededBy())) || replacements.contains(e.id())) {
                    it.remove();
                    chain.add(e);
                    chainIds.add(e.id());
                    if (e.supersededBy() != null) {
                        replacements.add(e.supersededBy());
                    }
                    grew = true;
                }
            }
        }
        // Farthest first: every version in the chain was reached through one found
        // before it, so deleting in reverse discovery order keeps what is left
        // connected to the forgotten entry if this loop stops part way.
        int deleted = 0;
        for (UserMemoryEntry e : chain.reversed()) {
            if (store.deleteEntryIfUnchanged(e.id(), e.updatedAt())) {
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * Whether {@code forgetFact} may delete this visible entry. Seeing a global
     * memory is not owning it: deleting another agent's global entry and then
     * writing the freed key would replace its value — exactly what the overwrite
     * guard in rememberFact refuses — so the same rule applies to deletion.
     */
    private boolean mayDelete(UserMemoryEntry entry) {
        return entry.visibility() != Visibility.global || guardrails.isAllowGlobalKeyOverwrite()
                || (agentId != null && !agentId.isBlank() && agentId.equals(entry.sourceAgentId()));
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

    /**
     * One line of a recall or search answer. A disputed entry says so, so the model
     * can ask the user rather than act on either value; the competing value is not
     * shown — it may be another agent's private memory.
     */
    private static String describe(UserMemoryEntry e) {
        String line = "• %s = %s [%s, %s]".formatted(e.key(), e.value(), e.category(), e.visibility());
        return switch (e.assessment()) {
            case CONTESTED -> line + " (contested: another memory holds a different value — confirm with the user)";
            case SUPERSEDED -> line + " (superseded by a newer memory)";
            case ACCEPTED -> line;
        };
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
    private CapacityPlan enforceCapacity() throws IResourceStore.ResourceStoreException {
        int cap = config.getMaxEntriesPerUser();
        long count = store.countEntries(userId);
        if (count < cap) {
            return CapacityPlan.room();
        }

        String onCap = config.getOnCapReached();
        if (ON_CAP_REJECT.equals(onCap)) {
            return CapacityPlan.refuse(("⚠️ Memory capacity reached (%d/%d). Cannot store NEW facts — existing ones can still be updated, or removed "
                    + "with forgetFact.").formatted(count, cap));
        }
        if (!ON_CAP_EVICT_OLDEST.equals(onCap)) {
            return CapacityPlan.refuse(("⚠️ Memory capacity reached (%d/%d) and onCapReached='%s' is not a known mode "
                    + "(expected '%s' or '%s'). Refusing the write.").formatted(count, cap, onCap, ON_CAP_EVICT_OLDEST, ON_CAP_REJECT));
        }

        List<UserMemoryEntry> evictable = evictableEntries();

        int required = (int) (count - cap + 1);
        if (evictable.size() < required) {
            return CapacityPlan.refuse(("⚠️ Memory capacity reached (%d/%d). Only %d of the %d entries needed could be evicted — the rest "
                    + "belong to other agents. Ask the user to remove memories, or raise 'maxEntriesPerUser'.").formatted(count, cap,
                            evictable.size(), required));
        }
        return new CapacityPlan(null, List.copyOf(evictable.subList(0, required)));
    }

    /**
     * The outcome of the capacity check: a refusal, or the entries to evict once
     * the write has landed.
     */
    private record CapacityPlan(String refusal, List<UserMemoryEntry> victims) {
        static CapacityPlan room() {
            return new CapacityPlan(null, List.of());
        }

        static CapacityPlan refuse(String message) {
            return new CapacityPlan(message, List.of());
        }
    }

    /**
     * Deletes the planned victims after the write that needed the room has landed.
     * Each is deleted only if it has not been written since it was chosen: a fact
     * refreshed meanwhile is no longer "oldest".
     */
    private void evictPlanned(List<UserMemoryEntry> victims) throws IResourceStore.ResourceStoreException {
        for (UserMemoryEntry victim : victims) {
            if (store.deleteEntryIfUnchanged(victim.id(), victim.updatedAt())) {
                LOGGER.debugf("[MEMORY] Evicted oldest entry to stay within cap: user='%s', key='%s'", userId, victim.key());
            }
        }
    }

    /**
     * Whether {@code stored} is what {@code written} put there: same value,
     * category and source conversation.
     */
    private static boolean isThisWrite(UserMemoryEntry stored, UserMemoryEntry written) {
        return Objects.equals(stored.value(), written.value()) && Objects.equals(stored.category(), written.category())
                && Objects.equals(stored.sourceConversationId(), written.sourceConversationId());
    }

    /**
     * Re-checks the cap once an insert has landed, and settles any overshoot.
     * <p>
     * {@link #enforceCapacity} reads the count and the write then inserts, so two
     * conversations of the same user writing at the same moment both read
     * {@code cap - 1}, both insert, and the user ends above the cap — one row per
     * concurrent writer, with nothing ever pulling it back. Counting again after
     * the write sees every insert that landed. The overshoot is attributed by a
     * rule every writer computes identically: the {@code excess} most recently
     * created entries are the ones over the cap. A writer whose own insert is among
     * them settles exactly one row — in {@code reject} mode by deleting its own
     * insert and refusing, in {@code evict_oldest} mode by evicting one of its
     * agent's oldest entries (its rank among the excess picks which, so two writers
     * never evict the same row). A writer whose insert is within the cap does
     * nothing.
     *
     * @return a refusal when this write was undone, otherwise null
     */
    private String settleCapacityAfterInsert(String key, Visibility vis) throws IResourceStore.ResourceStoreException {
        int cap = config.getMaxEntriesPerUser();
        long count = store.countEntries(userId);
        if (count <= cap) {
            return null;
        }
        UserMemoryEntry inserted = findUpsertTarget(key, vis);
        if (inserted == null || inserted.id() == null) {
            return null; // already gone — nothing of ours is over the cap
        }
        List<UserMemoryEntry> newestFirst = new ArrayList<>(store.getAllEntries(userId).stream().filter(e -> e.id() != null).toList());
        newestFirst.sort(Comparator.comparing(UserMemoryEntry::createdAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
                .thenComparing(UserMemoryEntry::id, Comparator.reverseOrder()));
        int excess = (int) Math.min(count - cap, newestFirst.size());
        List<String> overCapIds = newestFirst.subList(0, excess).stream().map(UserMemoryEntry::id).toList();
        int rank = overCapIds.indexOf(inserted.id());
        if (rank < 0) {
            return null; // a later concurrent insert is the one over the cap, and settles it
        }

        if (ON_CAP_EVICT_OLDEST.equals(config.getOnCapReached())) {
            List<UserMemoryEntry> evictable = evictableEntries().stream().filter(e -> !overCapIds.contains(e.id())).toList();
            if (rank < evictable.size()) {
                UserMemoryEntry victim = evictable.get(rank);
                store.deleteEntry(victim.id());
                LOGGER.debugf("[MEMORY] Evicted oldest entry after a concurrent write overshot the cap: user='%s', key='%s'", userId,
                        victim.key());
                return null;
            }
        }
        store.deleteEntry(inserted.id());
        return ("⚠️ Memory capacity reached (%d/%d): another conversation of this user stored a memory at the same moment, and this "
                + "fact was NOT saved. Tell the user; it can be saved once a memory is removed.").formatted(count, cap);
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
