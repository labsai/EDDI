/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model;

import ai.labs.eddi.modules.output.impl.OutputGeneration;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code compareTo} used to look at {@code occurred} only while {@code equals}
 * compares four fields. Any sorted collection therefore treated two entries
 * with the same occurrence as duplicates and silently dropped one of them.
 */
@DisplayName("OutputEntry — compareTo/equals consistency")
class OutputEntryComparisonTest {

    private static final String ACTION = "greet";
    private static final int OCCURRED = 1;

    private static OutputEntry entry(String action, int occurred, String text) {
        return new OutputEntry(action, occurred, List.of(new OutputValue(List.of(new TextOutputItem(text)))),
                List.of(new QuickReply("Yes", "expr", true)));
    }

    /**
     * Builds {@code count} entries whose creation order is the exact opposite of
     * their content-hash order, so any hash-based tie-break reorders them visibly
     * while a creation-order tie-break leaves them alone.
     */
    private static List<OutputEntry> entriesCreatedInDescendingHashOrder(int count) {
        var texts = new ArrayList<String>();
        for (int i = 0; i < 256; i++) {
            texts.add("output-" + i);
        }
        texts.sort(Comparator.comparingInt((String text) -> entry(ACTION, OCCURRED, text).hashCode()).reversed());

        var entries = new ArrayList<OutputEntry>();
        Integer previousHash = null;
        for (String text : texts) {
            var candidate = entry(ACTION, OCCURRED, text);
            if (previousHash == null || candidate.hashCode() < previousHash) {
                entries.add(candidate);
                previousHash = candidate.hashCode();
            }
            if (entries.size() == count) {
                break;
            }
        }

        assertEquals(count, entries.size(), "fixture precondition: not enough entries with strictly descending hash codes");
        return entries;
    }

    @Test
    @DisplayName("a TreeSet keeps distinct entries that share the same 'occurred'")
    void treeSetKeepsDistinctEntriesWithSameOccurrence() {
        var greet = entry("greet", 1, "Hi");
        var bye = entry("bye", 1, "Bye");

        var set = new TreeSet<OutputEntry>();
        set.add(greet);
        set.add(bye);

        assertEquals(2, set.size(), "distinct output entries must not collapse just because they share an occurrence");
        assertTrue(set.contains(greet));
        assertTrue(set.contains(bye));
    }

    @Test
    @DisplayName("a TreeSet still deduplicates genuinely equal entries")
    void treeSetDeduplicatesEqualEntries() {
        var first = entry("greet", 1, "Hi");
        var second = entry("greet", 1, "Hi");

        assertEquals(first, second);

        var set = new TreeSet<OutputEntry>();
        set.add(first);
        set.add(second);

        assertEquals(1, set.size());
    }

    @Test
    @DisplayName("compareTo returns 0 exactly when equals is true")
    void compareToAgreesWithEquals() {
        var a = entry("greet", 1, "Hi");
        var equalToA = entry("greet", 1, "Hi");
        var differentOutputs = entry("greet", 1, "Hello");
        var differentAction = entry("bye", 1, "Hi");

        assertEquals(0, a.compareTo(equalToA));
        assertNotEquals(0, a.compareTo(differentOutputs), "different outputs must not compare equal");
        assertNotEquals(0, a.compareTo(differentAction), "different actions must not compare equal");
    }

    @Test
    @DisplayName("comparison is symmetric and 'occurred' remains the primary sort key")
    void occurredRemainsPrimarySortKey() {
        var early = entry("zzz", 1, "Hi");
        var late = entry("aaa", 5, "Hi");

        assertTrue(early.compareTo(late) < 0);
        assertTrue(late.compareTo(early) > 0);

        var sorted = new ArrayList<>(List.of(late, early));
        Collections.sort(sorted);
        assertEquals(1, sorted.get(0).getOccurred());
        assertEquals(5, sorted.get(1).getOccurred());
    }

    @Test
    @DisplayName("the tie-break follows creation order, not content hash")
    void tieBreakFollowsCreationOrderNotContentHash() {
        var entries = entriesCreatedInDescendingHashOrder(2);
        var createdFirst = entries.get(0);
        var createdSecond = entries.get(1);

        assertTrue(createdFirst.compareTo(createdSecond) < 0,
                "the entry created (i.e. configured) first must sort first, even though its content hash is the larger one");
        assertTrue(createdSecond.compareTo(createdFirst) > 0, "the tie-break must be antisymmetric");
    }

    @Test
    @DisplayName("OutputGeneration emits same-occurrence entries in configuration order")
    void outputGenerationPreservesConfigurationOrder() {
        var configurationOrder = entriesCreatedInDescendingHashOrder(5);

        var outputGeneration = new OutputGeneration("en");
        configurationOrder.forEach(outputGeneration::addOutputEntry);

        assertEquals(configurationOrder, outputGeneration.getOutputMapper().get(ACTION),
                "every entry of the highest occurrence is emitted to the user in list order, so the sort must not reshuffle "
                        + "configuration order");
    }

    @Test
    @DisplayName("a null action sorts before a non-null action instead of throwing")
    void nullActionIsOrderedNotFatal() {
        var withoutAction = entry(null, 1, "Hi");
        var withAction = entry("greet", 1, "Hi");

        assertTrue(withoutAction.compareTo(withAction) < 0);
        assertTrue(withAction.compareTo(withoutAction) > 0);
    }
}
