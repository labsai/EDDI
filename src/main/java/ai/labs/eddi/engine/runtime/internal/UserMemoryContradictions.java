/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry.Assessment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dream's contradiction phase as a pure function: given a user's memories,
 * decide which entries contradict each other and what assessment each one
 * should carry afterwards. {@link DreamService} performs the writes, each a
 * compare-and-set on {@code updatedAt}.
 * <p>
 * Nothing here deletes anything, and no strategy silently picks a winner:
 * {@link Resolution#KEEP_NEWEST} changes no entry (today's log-only behaviour),
 * {@link Resolution#MARK_CONTESTED} keeps both sides and flags them, and
 * {@link Resolution#SUPERSEDE_OLDER} retires an older value only where the
 * newer one reaches every reader of the older — anywhere else the disagreement
 * stays visible as {@code CONTESTED}.
 */
final class UserMemoryContradictions {

    private UserMemoryContradictions() {
    }

    /**
     * {@code userMemoryConfig.dream.contradictionResolution}.
     */
    enum Resolution {
        /**
         * The default: count and log contradictions, change no entry. What an agent
         * sees is decided by the loader's own precedence — the most specific scope,
         * then the newest write. The name is historical: no value is chosen or kept by
         * Dream.
         */
        KEEP_NEWEST("keep_newest"),
        /** Flag every entry of a disputed key {@code CONTESTED}; keep them all. */
        MARK_CONTESTED("mark_contested"),
        /**
         * Retire each older disagreeing entry as {@code SUPERSEDED} (pointing at the
         * newest), where the newest is readable by all of its readers; mark whatever
         * still disagrees {@code CONTESTED}.
         */
        SUPERSEDE_OLDER("supersede_older");

        private final String configValue;

        Resolution(String configValue) {
            this.configValue = configValue;
        }

        String configValue() {
            return configValue;
        }

        /**
         * The strategy a config value names, case-insensitively; {@link #KEEP_NEWEST}
         * for {@code null} or blank, and {@code null} for a value that names none (the
         * caller warns and falls back to the default).
         */
        static Resolution fromConfig(String value) {
            if (value == null || value.isBlank()) {
                return KEEP_NEWEST;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Resolution resolution : values()) {
                if (resolution.configValue.equals(normalized)) {
                    return resolution;
                }
            }
            return null;
        }

        static String allowedValues() {
            return Arrays.stream(values()).map(Resolution::configValue).collect(Collectors.joining(", "));
        }
    }

    /**
     * Two adjacent entries of one key holding different values — the unit the
     * {@code contradictionsFound} count has always counted.
     */
    record Contradiction(UserMemoryEntry previous, UserMemoryEntry entry) {
    }

    /**
     * An assessment to write onto {@code entry} (as it was read — its
     * {@code updatedAt} is the compare-and-set expectation).
     */
    record Change(UserMemoryEntry entry, Assessment assessment, String supersededBy) {
    }

    record Plan(List<Contradiction> contradictions, List<Change> changes) {
    }

    /**
     * Decide the contradiction phase for one user.
     *
     * @param entries
     *            all of the user's memories, every agent's — detection reads past
     *            the firing agent's own entries
     * @param writableIds
     *            ids of the entries this cycle may change: the same agent-scoped
     *            set pruning and summarization work on
     * @param agentId
     *            the firing agent; unless {@code crossAgent}, only keys it holds
     *            itself are considered
     * @return the contradictions found and the assessment changes to write; a
     *         change never targets an entry outside {@code writableIds}
     */
    static Plan plan(Collection<UserMemoryEntry> entries, Set<String> writableIds, String agentId, boolean crossAgent, Resolution resolution) {
        Map<String, UserMemoryEntry> byId = new HashMap<>();
        for (UserMemoryEntry entry : entries) {
            if (entry.id() != null) {
                byId.put(entry.id(), entry);
            }
        }
        // The assessment each entry will carry once the plan is applied, by id.
        Map<String, Change> decided = new LinkedHashMap<>();

        // 1. A SUPERSEDED entry whose replacement is gone (deleted, pruned, or itself
        // superseded) has nothing left to defer to; it is ACCEPTED again and judged
        // with the rest below, so a chain collapses onto its newest link.
        for (UserMemoryEntry entry : entries) {
            if (entry.assessment() == Assessment.SUPERSEDED && writable(entry, writableIds)) {
                UserMemoryEntry replacement = entry.supersededBy() != null ? byId.get(entry.supersededBy()) : null;
                if (replacement == null || !Objects.equals(replacement.key(), entry.key())
                        || replacement.assessment() == Assessment.SUPERSEDED) {
                    decided.put(entry.id(), new Change(entry, Assessment.ACCEPTED, null));
                }
            }
        }

        Map<String, List<UserMemoryEntry>> activeByKey = entries.stream()
                .filter(e -> e.key() != null)
                .filter(e -> effective(e, decided) != Assessment.SUPERSEDED)
                .collect(Collectors.groupingBy(UserMemoryEntry::key, LinkedHashMap::new, Collectors.toList()));

        List<Contradiction> contradictions = new ArrayList<>();
        for (List<UserMemoryEntry> sameKey : activeByKey.values()) {
            if (!crossAgent && sameKey.stream().noneMatch(e -> agentId != null && agentId.equals(e.sourceAgentId()))) {
                continue;
            }

            UserMemoryEntry previous = null;
            for (UserMemoryEntry entry : sameKey) {
                if (previous != null && !Objects.equals(previous.value(), entry.value())) {
                    contradictions.add(new Contradiction(previous, entry));
                }
                previous = entry;
            }

            if (!disputed(sameKey)) {
                // 2. The disagreement is gone — the other side was rewritten, deleted or
                // superseded. A contest that no longer exists is lifted, whatever the
                // strategy, so switching back to keep_newest never strands a flag.
                for (UserMemoryEntry entry : sameKey) {
                    if (effective(entry, decided) == Assessment.CONTESTED && writable(entry, writableIds)) {
                        decided.put(entry.id(), new Change(entry, Assessment.ACCEPTED, null));
                    }
                }
                continue;
            }
            if (resolution == Resolution.KEEP_NEWEST) {
                continue;
            }

            List<UserMemoryEntry> remaining = new ArrayList<>(sameKey);
            if (resolution == Resolution.SUPERSEDE_OLDER) {
                UserMemoryEntry newest = uniqueNewest(sameKey);
                if (newest != null && newest.id() != null) {
                    for (UserMemoryEntry entry : sameKey) {
                        if (entry != newest && !Objects.equals(entry.value(), newest.value()) && writable(entry, writableIds)
                                && reachesEveryReaderOf(newest, entry)) {
                            decided.put(entry.id(), new Change(entry, Assessment.SUPERSEDED, newest.id()));
                            remaining.remove(entry);
                        }
                    }
                }
            }
            // 3. Whatever still disagrees is contested — both sides kept, both flagged.
            if (disputed(remaining)) {
                for (UserMemoryEntry entry : remaining) {
                    if (effective(entry, decided) != Assessment.CONTESTED && writable(entry, writableIds)) {
                        decided.put(entry.id(), new Change(entry, Assessment.CONTESTED, null));
                    }
                }
            }
        }

        List<Change> changes = decided.values().stream()
                .filter(change -> change.entry().assessment() != change.assessment()
                        || !Objects.equals(change.entry().supersededBy(), change.supersededBy()))
                .toList();
        return new Plan(contradictions, changes);
    }

    private static Assessment effective(UserMemoryEntry entry, Map<String, Change> decided) {
        Change change = entry.id() != null ? decided.get(entry.id()) : null;
        return change != null ? change.assessment() : entry.assessment();
    }

    private static boolean writable(UserMemoryEntry entry, Set<String> writableIds) {
        return entry.id() != null && writableIds.contains(entry.id());
    }

    private static boolean disputed(List<UserMemoryEntry> sameKey) {
        return sameKey.stream().map(UserMemoryEntry::value).distinct().count() > 1;
    }

    /**
     * The single most recently written entry, or {@code null} when the newest
     * timestamp is shared by entries that disagree — then there is no "newest" to
     * prefer, and the key stays contested.
     */
    private static UserMemoryEntry uniqueNewest(List<UserMemoryEntry> sameKey) {
        UserMemoryEntry newest = null;
        boolean tied = false;
        for (UserMemoryEntry entry : sameKey) {
            Instant at = entry.updatedAt();
            if (at == null) {
                continue;
            }
            if (newest == null || at.isAfter(newest.updatedAt())) {
                newest = entry;
                tied = false;
            } else if (at.equals(newest.updatedAt()) && !Objects.equals(entry.value(), newest.value())) {
                tied = true;
            }
        }
        return tied ? null : newest;
    }

    /**
     * Whether everyone who can read {@code older} can also read {@code newer} — the
     * condition for retiring {@code older} without taking the fact away from a
     * reader who cannot see its replacement. A {@code global} entry is read by
     * every agent of the user; a {@code group} entry by agents in an overlapping
     * group, so it covers an older {@code group} entry whose groups it includes; a
     * {@code self} entry is read by its own agent alone, and that agent cannot hold
     * a second entry for the same key, so it covers nothing.
     */
    static boolean reachesEveryReaderOf(UserMemoryEntry newer, UserMemoryEntry older) {
        if (!Objects.equals(newer.userId(), older.userId()) || newer.visibility() == null) {
            return false;
        }
        if (newer.visibility() == Visibility.global) {
            return true;
        }
        if (newer.visibility() == Visibility.group && older.visibility() == Visibility.group) {
            List<String> olderGroups = older.groupIds() != null ? older.groupIds() : List.of();
            List<String> newerGroups = newer.groupIds() != null ? newer.groupIds() : List.of();
            return !olderGroups.isEmpty() && newerGroups.containsAll(olderGroups);
        }
        return false;
    }
}
