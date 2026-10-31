/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownTableTest {

    @Test
    @DisplayName("one huge string behind every header cell is not copied past the character limit")
    void repeatedHugeCellStopsAtTheLimit() {
        // A workbook's shared string is stored once and may be referenced by every
        // cell; the header row used to be written in full before any check.
        String huge = "x".repeat(1_000_000);
        List<String> header = Collections.nCopies(64, huge);
        var limits = new ExtractionLimits(1_000, 10, 100, 64, 1024 * 1024);

        StringBuilder markdown = new StringBuilder();
        MarkdownTable.append(markdown, List.of(header, List.of("a", "b")), limits);

        assertTrue(markdown.length() < 10_000, "rendered " + markdown.length() + " characters for a 1,000 limit");
        assertEquals(limits.maxCharacters(), Extraction.capped(markdown.toString(), limits.maxCharacters()).length());
    }

    @Test
    @DisplayName("tables within the limit render exactly as before")
    void smallTablesAreUnchanged() {
        StringBuilder markdown = new StringBuilder();
        MarkdownTable.append(markdown, List.of(List.of("city", "people"), List.of("Berlin", "3 | 4")),
                ExtractionLimits.defaults());

        assertEquals("| city | people |\n| --- | --- |\n| Berlin | 3 \\| 4 |\n", markdown.toString());
    }

    @Test
    @DisplayName("a cut cell never ends on half a surrogate pair")
    void cutRespectsSurrogatePairs() {
        // Built from code points: the formatter turns a unicode escape into the raw
        // character.
        String emoji = Character.toString(0x1F600);
        String cut = MarkdownTable.fitting("a" + emoji.repeat(100), 0);

        assertTrue(cut.length() <= 64, cut);
        assertTrue(!Character.isHighSurrogate(cut.charAt(cut.length() - 1)), "ends on a lone high surrogate");
        assertEquals("short", MarkdownTable.fitting("short", 1));
    }
}
