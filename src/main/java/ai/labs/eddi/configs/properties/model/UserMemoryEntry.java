/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.model;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A structured memory entry stored in the {@code usermemories} collection.
 * Represents a single fact, preference, or context item that an agent has
 * remembered about a user.
 *
 * <p>
 * Upsert key semantics vary by visibility:
 * <ul>
 * <li>{@code self/group}: {@code (userId, key, sourceAgentId)}</li>
 * <li>{@code global}: {@code (userId, key)}</li>
 * </ul>
 * So the same key can be held by several entries at once — one per agent for
 * {@code self}/{@code group}, plus the shared {@code global} one — and those
 * can disagree. Dream's contradiction detection records that disagreement in
 * {@link #assessment()} instead of picking a winner.
 * <p>
 * {@code sourceAgentId} is the entry's owner: the agent that created it. A
 * global entry is shared, and a later write by another agent replaces its value
 * but keeps the owner, so who wrote the value it holds is
 * {@code lastWriterAgentId}: the agent whose write produced it, {@code null}
 * when the user or an admin wrote it. The stores record the writer an entry
 * names, so a write on behalf of an agent other than the owner names both. An
 * entry stored before writers were recorded reads back with its owner as
 * writer.
 *
 * @param conflicted
 *            mirror of {@code assessment == CONTESTED}, kept because existing
 *            clients (the Manager's memory page) already read it
 * @param assessment
 *            where this entry stands against other entries for the same key;
 *            {@code null} (a document stored before the field existed) is
 *            {@link Assessment#ACCEPTED}
 * @param supersededBy
 *            id of the entry that replaced this one; only kept when
 *            {@code assessment} is {@link Assessment#SUPERSEDED}
 * @param sourceEntryIds
 *            ids of the entries a Dream consolidation built this one from, when
 *            {@code dream.citeSources} is on; empty otherwise and for every
 *            ordinary write, which replaces the value and so the provenance.
 *            Consolidation deletes those originals afterwards (except one it
 *            overwrote in place, whose own id is then listed), so the ids
 *            identify them in logs, backups and exports rather than pointing at
 *            live entries. Never null
 * @author ginccc
 * @since 6.0.0
 */
public record UserMemoryEntry(String id, String userId, String key, Object value, String category, Visibility visibility, String sourceAgentId,
        List<String> groupIds, String sourceConversationId, boolean conflicted, int accessCount, Instant createdAt, Instant updatedAt,
        Assessment assessment, String supersededBy, List<String> sourceEntryIds, String lastWriterAgentId) {

    /**
     * Where an entry stands against the other entries holding the same key. Only
     * Dream's contradiction resolution sets anything but {@link #ACCEPTED}; every
     * ordinary write (conversation, LLM tool, REST, MCP) stores {@link #ACCEPTED},
     * so rewriting a key resolves its contest.
     */
    public enum Assessment {
        /** The default: nothing disputes this entry. */
        ACCEPTED,
        /**
         * Another entry for the same key holds a different value, and neither was
         * chosen. Both are kept and still loaded; the agent sees a marker so it can ask
         * the user.
         */
        CONTESTED,
        /**
         * A newer entry for the same key, readable by everyone who can read this one,
         * replaced it. Kept as evidence, never deleted by Dream; not loaded as a
         * property while the replacement is visible.
         */
        SUPERSEDED;

        /**
         * Lenient parse for stored values: {@code null}, blank or unknown text is
         * {@link #ACCEPTED}, so a document written by a newer release with a value this
         * one does not know never fails a recall.
         */
        public static Assessment parse(String value) {
            if (value == null || value.isBlank()) {
                return ACCEPTED;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ACCEPTED;
            }
        }
    }

    public UserMemoryEntry {
        assessment = assessment != null ? assessment : Assessment.ACCEPTED;
        supersededBy = assessment == Assessment.SUPERSEDED ? supersededBy : null;
        sourceEntryIds = sourceEntryIds == null ? List.of() : sourceEntryIds.stream().filter(Objects::nonNull).toList();
    }

    /**
     * An entry about to be written, or one read back whose writer is its owner:
     * {@code sourceAgentId} wrote the value.
     */
    public UserMemoryEntry(String id, String userId, String key, Object value, String category, Visibility visibility, String sourceAgentId,
            List<String> groupIds, String sourceConversationId, boolean conflicted, int accessCount, Instant createdAt, Instant updatedAt,
            Assessment assessment, String supersededBy, List<String> sourceEntryIds) {
        this(id, userId, key, value, category, visibility, sourceAgentId, groupIds, sourceConversationId, conflicted, accessCount, createdAt,
                updatedAt, assessment, supersededBy, sourceEntryIds, sourceAgentId);
    }

    /**
     * The shape before {@code sourceEntryIds}: an entry with no recorded sources,
     * whose writer is its owner.
     */
    public UserMemoryEntry(String id, String userId, String key, Object value, String category, Visibility visibility, String sourceAgentId,
            List<String> groupIds, String sourceConversationId, boolean conflicted, int accessCount, Instant createdAt, Instant updatedAt,
            Assessment assessment, String supersededBy) {
        this(id, userId, key, value, category, visibility, sourceAgentId, groupIds, sourceConversationId, conflicted, accessCount, createdAt,
                updatedAt, assessment, supersededBy, List.of());
    }

    /**
     * An {@link Assessment#ACCEPTED} entry with no recorded sources, written by
     * {@code lastWriterAgentId}.
     */
    public UserMemoryEntry(String id, String userId, String key, Object value, String category, Visibility visibility, String sourceAgentId,
            List<String> groupIds, String sourceConversationId, boolean conflicted, int accessCount, Instant createdAt, Instant updatedAt,
            String lastWriterAgentId) {
        this(id, userId, key, value, category, visibility, sourceAgentId, groupIds, sourceConversationId, conflicted, accessCount, createdAt,
                updatedAt, Assessment.ACCEPTED, null, List.of(), lastWriterAgentId);
    }

    /**
     * The shape before {@code assessment} and {@code supersededBy}: an
     * {@link Assessment#ACCEPTED} entry whose writer is its owner.
     */
    public UserMemoryEntry(String id, String userId, String key, Object value, String category, Visibility visibility, String sourceAgentId,
            List<String> groupIds, String sourceConversationId, boolean conflicted, int accessCount, Instant createdAt, Instant updatedAt) {
        this(id, userId, key, value, category, visibility, sourceAgentId, groupIds, sourceConversationId, conflicted, accessCount, createdAt,
                updatedAt, Assessment.ACCEPTED, null, List.of());
    }

    /**
     * This entry with another assessment; {@code conflicted} follows it. Nothing
     * else changes, {@code updatedAt} included.
     */
    public UserMemoryEntry withAssessment(Assessment newAssessment, String newSupersededBy) {
        return new UserMemoryEntry(id, userId, key, value, category, visibility, sourceAgentId, groupIds, sourceConversationId,
                newAssessment == Assessment.CONTESTED, accessCount, createdAt, updatedAt, newAssessment, newSupersededBy, sourceEntryIds,
                lastWriterAgentId);
    }

    /** This entry with other recorded sources. Nothing else changes. */
    public UserMemoryEntry withSourceEntryIds(List<String> newSourceEntryIds) {
        return new UserMemoryEntry(id, userId, key, value, category, visibility, sourceAgentId, groupIds, sourceConversationId, conflicted,
                accessCount, createdAt, updatedAt, assessment, supersededBy, newSourceEntryIds, lastWriterAgentId);
    }

    /**
     * This entry as a new statement of its fact: {@code ACCEPTED}, no
     * {@code supersededBy}, not {@code conflicted}. Both stores apply it to every
     * value write — upsert, insert, owned upsert, compare-and-set replace — so a
     * client that edits a contested entry and sends it back (REST {@code PUT}
     * echoes whatever it read) cannot carry the old verdict onto the new value, and
     * {@code conflicted} can never disagree with {@code assessment}. The only way
     * to store any other assessment is
     * {@code IUserMemoryStore#setAssessmentIfUnchanged}, which Dream uses.
     * <p>
     * {@link #sourceEntryIds()} is kept. Provenance is not a verdict, and Dream's
     * consolidation write — the one write that sets it — goes through these same
     * store methods. Writers that must not set it clear it themselves at their
     * boundary: the REST write ({@code UserMemoryWriteRules.withDefaults}); every
     * other writer builds its entry without sources.
     */
    public UserMemoryEntry asStatement() {
        if (assessment == Assessment.ACCEPTED && supersededBy == null && !conflicted) {
            return this;
        }
        return withAssessment(Assessment.ACCEPTED, null);
    }

    /**
     * Whether an agent other than {@code agentId} wrote the value this entry holds
     * ({@link #lastWriterAgentId}). One the user or an admin wrote names no agent,
     * and a team's lessons ({@link IUserMemoryStore#TEAM_OWNER_PREFIX}) are the
     * engine's, distilled from the team's records under its records rule. A reader
     * that requires peer verification is shown no entry another agent wrote: no
     * signature covers it.
     */
    public boolean writtenByAgentOtherThan(String agentId) {
        if (userId != null && userId.startsWith(IUserMemoryStore.TEAM_OWNER_PREFIX)) {
            return false;
        }
        return lastWriterAgentId != null && !lastWriterAgentId.isBlank() && !lastWriterAgentId.equals(agentId);
    }

    /** Accepted memory categories. Unknown values default to {@code "fact"}. */
    public static final List<String> DEFAULT_CATEGORIES = List.of("preference", "fact", "context");

    /**
     * Creates an entry from a {@link Property} with user memory metadata. The
     * property's own visibility wins; {@code defaultVisibility} applies only when
     * it has none. The entry carries no group ids, so a {@code group} entry built
     * here is not readable through group overlap — use the overload below for that.
     */
    public static UserMemoryEntry fromProperty(Property property, String userId, String agentId, String conversationId,
                                               Visibility defaultVisibility) {
        Visibility visibility = property.getVisibility() != null ? property.getVisibility() : defaultVisibility;
        return fromProperty(property, userId, agentId, conversationId, visibility, List.of());
    }

    /**
     * Creates an entry with an already RESOLVED visibility, for a conversation that
     * belongs to {@code groupIds}. Unlike the overload above, the property's own
     * visibility is not consulted again — the caller decided, possibly narrowing a
     * {@code group} property to {@code self} because the conversation has no group.
     * <p>
     * A {@code group} entry is only reachable through an overlap with the reader's
     * group ids, so one stored with none — which is what every {@code longTerm}
     * property got — could be read by nobody, the writing agent included.
     */
    public static UserMemoryEntry fromProperty(Property property, String userId, String agentId, String conversationId, Visibility visibility,
                                               List<String> groupIds) {
        Object value = PropertyValues.valueOf(property);

        Visibility vis = visibility != null ? visibility : Visibility.self;

        List<String> entryGroupIds = vis == Visibility.group && groupIds != null ? List.copyOf(groupIds) : List.of();
        return new UserMemoryEntry(null, userId, property.getName(), value, "fact", vis, agentId, entryGroupIds, conversationId, false, 0,
                Instant.now(), Instant.now());
    }

    /**
     * Creates an entry from an LLM tool call (rememberFact).
     */
    public static UserMemoryEntry fromToolCall(String userId, String agentId, String conversationId, List<String> groupIds, String key, Object value,
                                               String category, Visibility visibility) {
        return new UserMemoryEntry(null, userId, key, value, normalizeCategory(category), visibility != null ? visibility : Visibility.self, agentId,
                groupIds != null ? groupIds : List.of(), conversationId, false, 0, Instant.now(), Instant.now());
    }

    /**
     * Returns the category, defaulting unknown values to "fact".
     */
    public static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return "fact";
        }
        String normalized = category.trim().toLowerCase();
        return DEFAULT_CATEGORIES.contains(normalized) ? normalized : "fact";
    }
}
