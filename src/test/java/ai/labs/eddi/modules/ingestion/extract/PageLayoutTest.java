/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import ai.labs.eddi.modules.ingestion.extract.PageLayout.Line;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PageLayout")
class PageLayoutTest {

    private static Line line(String text, float x, float top, float size) {
        return new Line(text, x, x + text.length() * size / 2, top, size);
    }

    @Test
    @DisplayName("takes the size most characters are set in as the body size")
    void bodySize() {
        List<Line> lines = List.of(line("A heading", 50, 10, 18), line("Body text that runs long enough", 50, 40, 10),
                line("More body text that runs on", 50, 55, 10), line("small", 50, 70, 7));

        assertEquals(10f, PageLayout.bodySize(lines));
        assertEquals(0f, PageLayout.bodySize(List.of()));
        assertEquals(0f, PageLayout.bodySize(List.of(line("   ", 50, 10, 12))));
    }

    @Test
    @DisplayName("takes a short line set 1.2 times the body size for a heading, nothing less")
    void heading() {
        assertTrue(PageLayout.isHeading(line("Chapter 1", 50, 10, 12), 10));
        assertFalse(PageLayout.isHeading(line("Chapter 1", 50, 10, 11.9f), 10), "below the ratio");
        assertFalse(PageLayout.isHeading(line("x".repeat(101), 50, 10, 18), 10), "too long");
        assertFalse(PageLayout.isHeading(line("2026", 50, 10, 18), 10), "no letter");
        assertFalse(PageLayout.isHeading(line("Chapter 1", 50, 10, 18), 0), "a page without body text");
    }

    @Test
    @DisplayName("finds two columns whose lines do not cross the gap between them")
    void columns() {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            lines.add(line("Left column text", 50, 100 + i * 14, 10));
            lines.add(line("Right column text", 320, 100 + i * 14, 10));
        }

        assertTrue(PageLayout.multiColumn(lines, 10));
    }

    @Test
    @DisplayName("does not take indented paragraphs, a centred heading or a short page for columns")
    void notColumns() {
        List<Line> indented = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            // First lines of paragraphs start 120 pt in, but every line runs across.
            indented.add(line("A line of body text that runs across the page from margin to margin", i % 2 == 0
                    ? 50
                    : 170, 100 + i * 14, 10));
        }
        assertFalse(PageLayout.multiColumn(indented, 10));

        List<Line> centred = new ArrayList<>();
        centred.add(line("Title", 280, 40, 24));
        for (int i = 0; i < 8; i++) {
            centred.add(line("A line of body text that runs across the page", 50, 100 + i * 14, 10));
        }
        assertFalse(PageLayout.multiColumn(centred, 10), "a heading is not a column");

        List<Line> few = List.of(line("Left", 50, 100, 10), line("Right", 320, 100, 10), line("Left", 50, 114, 10),
                line("Right", 320, 114, 10));
        assertFalse(PageLayout.multiColumn(few, 10), "too few lines to tell");
    }

    @Test
    @DisplayName("orders lines top to bottom, and left to right at the same height, giving them one top")
    void readingOrder() {
        List<Line> lines = List.of(line("right 2", 320, 113.7f, 10), line("left 1", 50, 100, 10),
                line("right 1", 320, 99.8f, 10), line("left 2", 50, 114, 10));

        List<Line> ordered = PageLayout.readingOrder(lines);

        assertEquals(List.of("left 1", "right 1", "left 2", "right 2"), ordered.stream().map(Line::text).toList());
        assertEquals(ordered.get(0).top(), ordered.get(1).top());
        assertEquals(ordered.get(2).top(), ordered.get(3).top());
    }
}
