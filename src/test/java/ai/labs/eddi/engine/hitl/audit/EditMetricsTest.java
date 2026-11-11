/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EditMetrics — how much an approver changed")
class EditMetricsTest {

    @Test
    @DisplayName("identical text is no change at all")
    void identical() {
        EditSummary summary = EditMetrics.ofText(EditSummary.Kind.ANSWER, "Hello world", "Hello world");
        assertFalse(summary.changed());
        assertEquals(0, summary.charsChanged());
        assertEquals(0.0, summary.changeRatio());
        assertEquals(summary.originalSha256(), summary.editedSha256());
    }

    @Test
    @DisplayName("distance counts only the changed middle")
    void prefixSuffixTrimmed() {
        EditSummary summary = EditMetrics.ofText(EditSummary.Kind.ANSWER, "Dear team, the release is Friday. Regards",
                "Dear team, the release is Monday. Regards");
        assertTrue(summary.changed());
        assertEquals(3, summary.charsChanged(), "Fri -> Mon");
        assertFalse(summary.approximate());
        assertEquals("Dear team, the release is Monday. Regards", summary.editedText());
    }

    @Test
    @DisplayName("Levenshtein on the middle: insertion, deletion, substitution")
    void distance() {
        assertEquals(new EditMetrics.Distance(3, false), EditMetrics.distance("kitten", "sitting"));
        assertEquals(new EditMetrics.Distance(5, false), EditMetrics.distance("", "abcde"));
        assertEquals(new EditMetrics.Distance(2, false), EditMetrics.distance("abcdef", "abef"));
    }

    @Test
    @DisplayName("exact up to 4000 code points per middle, approximate above")
    void approximationThreshold() {
        String exactA = "a".repeat(4000);
        String exactB = "b".repeat(4000);
        EditMetrics.Distance exact = EditMetrics.distance(exactA, exactB);
        assertFalse(exact.approximate());
        assertEquals(4000, exact.value());

        EditMetrics.Distance approx = EditMetrics.distance("a".repeat(4001), "b".repeat(4000));
        assertTrue(approx.approximate());
        assertEquals(4001, approx.value());
    }

    @Test
    @DisplayName("change ratio of an empty original divides by one")
    void ratioEmptyOriginal() {
        EditSummary summary = EditMetrics.ofText(EditSummary.Kind.TASK_RESULT, "", "abc");
        assertEquals(3, summary.charsChanged());
        assertEquals(3.0, summary.changeRatio());
        assertEquals(0.3333, EditMetrics.ratio(1, 3));
    }

    @Test
    @DisplayName("lengths and distance count code points, not UTF-16 units")
    void codePoints() {
        EditSummary summary = EditMetrics.ofText(EditSummary.Kind.ANSWER, "ok 😀", "ok 😃");
        assertEquals(4, summary.originalChars());
        assertEquals(1, summary.charsChanged());
    }

    @Test
    @DisplayName("tool arguments: leaf diff, never the arguments themselves")
    void toolArguments() {
        EditSummary summary = EditMetrics.ofArguments("{\"title\":\"a\",\"body\":{\"text\":\"x\",\"tags\":[1,2]}}",
                "{\"title\":\"a\",\"body\":{\"text\":\"y\",\"tags\":[1,3]},\"draft\":true}");
        assertEquals(EditSummary.Kind.TOOL_ARGUMENTS, summary.kind());
        assertEquals(3, summary.fieldsChanged(), "body.text, body.tags[1], draft");
        assertNull(summary.editedText());
        assertFalse(summary.toMap().containsKey("editedText"));
        assertEquals(0, EditMetrics.ofArguments("{\"a\":1}", "{ \"a\" : 1 }").fieldsChanged());
    }

    @Test
    @DisplayName("edited text is secret-redacted and capped at 32 KB")
    void redactedAndCapped() {
        EditSummary withSecret = EditMetrics.ofText(EditSummary.Kind.ANSWER, "x", "{\"password\":\"fixturefixture\",\"n\":1}");
        assertFalse(withSecret.editedText().contains("fixturefixture"));

        EditSummary big = EditMetrics.ofText(EditSummary.Kind.SYNTHESIS, "", "é".repeat(40_000));
        assertTrue(big.editedText().getBytes(StandardCharsets.UTF_8).length <= EditMetrics.EDITED_TEXT_MAX_BYTES);
        assertEquals(40_000, big.editedChars(), "sizes are of the whole edit");
    }

    @Test
    @DisplayName("the stored form round-trips")
    void roundTrip() {
        EditSummary summary = EditMetrics.ofText(EditSummary.Kind.ANSWER, "a", "b");
        assertEquals(summary, EditSummary.fromMap(summary.toMap()));
        assertNull(EditSummary.fromMap("nope"));
    }
}
