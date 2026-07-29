/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model;

import ai.labs.eddi.modules.output.IOutputFilter;
import ai.labs.eddi.modules.output.impl.OutputGeneration;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OutputEntry#compareTo(OutputEntry)} orders by {@code occurred} only,
 * so that the stable sort in {@code OutputGeneration.addOutputEntry} preserves
 * the order in which entries were declared in {@code output.json} — that order
 * is the order of the chat bubbles the end user sees.
 * <p>
 * Any additional tie-breaker (action, content hash, …) silently re-orders
 * user-visible output; these tests pin that it does not happen.
 */
@DisplayName("OutputEntry — ordering")
class OutputEntryComparisonTest {

    private static OutputEntry entry(String action, int occurred, String text) {
        return new OutputEntry(action, occurred, List.of(new OutputValue(List.<OutputItem>of(new TextOutputItem(text)))), List.of());
    }

    private static IOutputFilter filter(String action, int occurred) {
        return new IOutputFilter() {
            @Override
            public String getAction() {
                return action;
            }

            @Override
            public int getOccurred() {
                return occurred;
            }
        };
    }

    private static String textOf(OutputEntry entry) {
        return ((TextOutputItem) entry.getOutputs().get(0).getValueAlternatives().get(0)).getText();
    }

    @Test
    @DisplayName("entries of the same action and occurrence keep their declaration order through the output pipeline")
    void sameOccurrenceKeepsDeclarationOrder() {
        // "Hello there".hashCode() based OutputEntry hash is *greater* than the one of
        // "How can I help?", so a hashCode tie-breaker would swap these two bubbles.
        var outputGeneration = new OutputGeneration("en");
        outputGeneration.addOutputEntry(entry("greet", 0, "Hello there"));
        outputGeneration.addOutputEntry(entry("greet", 0, "How can I help?"));

        var outputs = outputGeneration.getOutputs(List.of(filter("greet", 0)));

        var entries = outputs.get("greet");
        assertEquals(2, entries.size());
        assertEquals("Hello there", textOf(entries.get(0)));
        assertEquals("How can I help?", textOf(entries.get(1)));
    }

    @Test
    @DisplayName("compareTo treats same-occurrence entries as ties so the sort stays stable")
    void sameOccurrenceComparesAsTie() {
        var first = entry("greet", 1, "Hello there");
        var second = entry("greet", 1, "How can I help?");
        var otherAction = entry("bye", 1, "Bye");

        assertEquals(0, first.compareTo(second), "different content must still be a tie, otherwise the stable sort re-orders bubbles");
        assertEquals(0, second.compareTo(first));
        assertEquals(0, first.compareTo(otherAction), "the action must not become a sort key");
    }

    @Test
    @DisplayName("a list of same-occurrence entries survives repeated sorting unchanged")
    void repeatedSortingIsIdempotent() {
        var sorted = new ArrayList<>(
                List.of(entry("greet", 0, "Anything else?"), entry("greet", 0, "Hello there"), entry("greet", 0, "How can I help?")));

        Collections.sort(sorted);
        Collections.sort(sorted);

        assertEquals(List.of("Anything else?", "Hello there", "How can I help?"), sorted.stream().map(OutputEntryComparisonTest::textOf).toList());
    }

    @Test
    @DisplayName("'occurred' remains the primary — and only — sort key")
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
    @DisplayName("a null action is ordered, not fatal")
    void nullActionIsOrderedNotFatal() {
        var withoutAction = entry(null, 1, "Hi");
        var withAction = entry("greet", 1, "Hi");

        assertEquals(0, withoutAction.compareTo(withAction));
        assertEquals(0, withAction.compareTo(withoutAction));
    }

    @Test
    @DisplayName("equals still distinguishes entries that compare as ties")
    void equalsStillDistinguishesTiedEntries() {
        var first = entry("greet", 1, "Hello there");
        var second = entry("greet", 1, "How can I help?");

        assertEquals(0, first.compareTo(second));
        assertNotEquals(first, second, "compareTo is deliberately inconsistent with equals; equals must stay content-based");
        assertEquals(entry("greet", 1, "Hello there"), first);
    }
}
