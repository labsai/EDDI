/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectorTextTest {

    private static final List<String> NONE = List.of();

    @Test
    void plainName_isTheWholeFile() {
        assertEquals(new SelectorText.Parsed("report.pdf", null, null), SelectorText.parse("report.pdf", NONE));
    }

    @Test
    void unitSuffix_isParsed() {
        var parsed = SelectorText.parse("report.pdf#u3", NONE);
        assertEquals(new SelectorText.Parsed("report.pdf", "u3", null), parsed);
        assertTrue(parsed.hasPart());
    }

    @Test
    void subUnitSuffix_isParsed() {
        assertEquals(new SelectorText.Parsed("report.pdf", "u3.1.2", null), SelectorText.parse("report.pdf#u3.1.2", NONE));
    }

    @Test
    void pageSuffix_isParsed() {
        assertEquals(new SelectorText.Parsed("report.pdf", null, "4-9"), SelectorText.parse("report.pdf#p4-9", NONE));
    }

    @Test
    void knownNameContainingHash_winsOverTheGrammar() {
        assertEquals(new SelectorText.Parsed("a#u3.pdf", null, null), SelectorText.parse("a#u3.pdf", List.of("a#u3.pdf")));
    }

    @Test
    void knownNameThatLooksLikeAUnit_isTheName() {
        var parsed = SelectorText.parse("report#u3", List.of("report#u3"));
        assertEquals(new SelectorText.Parsed("report#u3", null, null), parsed);
        assertFalse(parsed.hasPart());
    }

    @Test
    void sameTextUnknown_isAUnitOfReport() {
        assertEquals(new SelectorText.Parsed("report", "u3", null), SelectorText.parse("report#u3", List.of("report")));
    }

    @Test
    void splitsAtTheLastHash() {
        assertEquals(new SelectorText.Parsed("a#b.pdf", "u2", null), SelectorText.parse("a#b.pdf#u2", NONE));
    }

    @Test
    void nameWithHashAndNoValidSuffix_isAName() {
        assertEquals(new SelectorText.Parsed("notes#draft.txt", null, null), SelectorText.parse("notes#draft.txt", NONE));
    }

    @Test
    void badSuffixes_areNames() {
        for (String text : List.of("x#chapter3", "x#u", "x#u3.", "x#U3", "x#p4", "x#p4-", "x#p-9", "x#p4-9x", "x#u3#")) {
            var parsed = SelectorText.parse(text, NONE);
            assertFalse(parsed.hasPart(), text);
            assertEquals(text, parsed.name(), text);
        }
    }

    @Test
    void leadingHash_isAName() {
        assertEquals(new SelectorText.Parsed("#u3", null, null), SelectorText.parse("#u3", NONE));
    }

    @Test
    void index_isLeftAsTheName() {
        assertEquals(new SelectorText.Parsed("2", null, null), SelectorText.parse("2", NONE));
        assertEquals(new SelectorText.Parsed("2", "u1", null), SelectorText.parse("2#u1", NONE));
    }

    @Test
    void whitespaceIsIgnored_andNullIsEmpty() {
        assertEquals(new SelectorText.Parsed("report.pdf", null, null), SelectorText.parse("  report.pdf \n", NONE));
        assertEquals(new SelectorText.Parsed("", null, null), SelectorText.parse(null, null));
    }
}
