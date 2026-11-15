/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.audit;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the decision audit records about a reviewer's edit: the distance, the
 * changed JSON fields, the hashes and a capped copy of the edited text.
 */
class EditMetricsBranchTest {

    @Test
    void distanceTrimsTheCommonPrefixAndSuffix() {
        assertEquals(new EditMetrics.Distance(0, false), EditMetrics.distance("same", "same"));
        assertEquals(new EditMetrics.Distance(3, false), EditMetrics.distance("abc", ""));
        assertEquals(new EditMetrics.Distance(2, false), EditMetrics.distance("ab", "abcd"), "a pure insertion at the end");
        assertEquals(new EditMetrics.Distance(1, false), EditMetrics.distance("kitten", "sitten"));
        assertEquals(new EditMetrics.Distance(3, false), EditMetrics.distance("kitten", "sitting"));
        assertEquals(new EditMetrics.Distance(1, false), EditMetrics.distance("a😀b", "a😁b"), "an emoji is one code point");
    }

    @Test
    void aMiddlePartBeyondTheLimitIsApproximated() {
        String a = "x" + "a".repeat(EditMetrics.EXACT_DISTANCE_LIMIT + 1) + "y";
        String b = "x" + "b".repeat(10) + "y";
        var distance = EditMetrics.distance(a, b);
        assertTrue(distance.approximate());
        assertEquals(EditMetrics.EXACT_DISTANCE_LIMIT + 1, distance.value());
        assertTrue(EditMetrics.distance(b, a).approximate(), "either side may be the long one");
    }

    @Test
    void theRatioIsRoundedAndNeverDividesByZero() {
        assertEquals(0.3333, EditMetrics.ratio(1, 3));
        assertEquals(5.0, EditMetrics.ratio(5, 0));
    }

    @Test
    void changedFieldsAreCountedPerLeafPath() {
        assertEquals(0, EditMetrics.fieldsChanged("{\"a\":1}", "{\"a\":1}"));
        assertEquals(1, EditMetrics.fieldsChanged("{\"a\":1,\"b\":2}", "{\"a\":1,\"b\":3}"));
        assertEquals(2, EditMetrics.fieldsChanged("{\"to\":[\"x\",\"y\"]}", "{\"to\":[\"x\",\"z\",\"w\"]}"));
        assertEquals(2, EditMetrics.fieldsChanged("{\"n\":{\"deep\":true}}", "{\"n\":{}}"), "the removed leaf and the emptied object both count");
        assertEquals(2, EditMetrics.fieldsChanged("", "{\"added\":1}"), "a blank side is an empty object: its root leaf and the added field");
        assertEquals(0, EditMetrics.fieldsChanged("", " "));
    }

    @Test
    void unparseableArgumentsCountAsOneChangeWhenTheyDiffer() {
        assertEquals(1, EditMetrics.fieldsChanged("{broken", "{\"a\":1}"));
        assertEquals(0, EditMetrics.fieldsChanged("{broken", "{broken"));
    }

    @Test
    void argumentSummariesNeverCarryTheArguments() {
        var summary = EditMetrics.ofArguments("{\"to\":\"a@x\"}", "{\"to\":\"b@x\"}");
        assertEquals(EditSummary.Kind.TOOL_ARGUMENTS, summary.kind());
        assertNull(summary.editedText());
        assertEquals(1, summary.fieldsChanged());
        assertTrue(summary.changed());

        var untouched = EditMetrics.ofArguments(null, null);
        assertFalse(untouched.changed());
        assertEquals(0, untouched.fieldsChanged());
        assertEquals(EditMetrics.sha256Hex(""), untouched.originalSha256());
    }

    @Test
    void textSummariesKeepTheEditedTextCapped() {
        var summary = EditMetrics.ofText(EditSummary.Kind.ANSWER, null, "new answer");
        assertEquals(0, summary.originalChars());
        assertEquals(10, summary.editedChars());
        assertEquals("new answer", summary.editedText());
        assertEquals(10.0, summary.changeRatio());

        var cleared = EditMetrics.ofText(EditSummary.Kind.ANSWER, "old", null);
        assertEquals("", cleared.editedText());
        assertEquals(1.0, cleared.changeRatio());
    }

    @Test
    void theEditedTextIsCutAtTheByteCapOnACodePointBoundary() {
        String big = "€".repeat(EditMetrics.EDITED_TEXT_MAX_BYTES); // three bytes each
        String capped = EditMetrics.cappedRedacted(big);
        int bytes = capped.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= EditMetrics.EDITED_TEXT_MAX_BYTES, "within the cap");
        assertTrue(bytes > EditMetrics.EDITED_TEXT_MAX_BYTES - 3, "and as full as whole characters allow");
        assertEquals(capped.length() * 3, bytes, "no character is split");
        assertEquals("short", EditMetrics.cappedRedacted("short"));
        assertNull(EditMetrics.cappedRedacted(null));
    }

    @Test
    void theHashIsLowerCaseHexOfUtf8() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", EditMetrics.sha256Hex(null));
        assertEquals(64, EditMetrics.sha256Hex("x").length());
    }
}
