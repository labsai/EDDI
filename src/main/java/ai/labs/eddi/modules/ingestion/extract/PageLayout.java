/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What one page's laid-out lines say about its layout: the body font size,
 * which lines are headings, whether the text stands in columns, and the reading
 * order. Pure; the lines come from {@link BoundedTextStripper}'s
 * {@link BoundedTextStripper.LineListener}.
 *
 * <p>
 * Heuristics, kept conservative: a page they cannot read gives no headings and
 * no columns, never an error.
 */
final class PageLayout {

    /**
     * A line is a heading when its font is at least this many times the page's body
     * size.
     */
    static final float HEADING_RATIO = 1.2f;

    /** A heading is short: at most this many characters. */
    static final int MAX_HEADING_CHARACTERS = 100;

    /** Lines a page needs before columns are looked for. */
    static final int MIN_COLUMN_LINES = 6;

    /**
     * The empty horizontal space, in points, that must separate two columns: half
     * an inch.
     */
    static final float MIN_COLUMN_GAP = 36f;

    private PageLayout() {
    }

    /**
     * One laid-out line.
     *
     * @param top
     *            distance of the line's top from the page's top, in points
     */
    record Line(String text, float x, float xEnd, float top, float fontSize) {

        /** Characters other than white space. */
        int weight() {
            int count = 0;
            for (int i = 0; i < text.length(); i++) {
                if (!Character.isWhitespace(text.charAt(i))) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * The body font size: the median font size of the page's characters, so the
     * size most of its text is set in. 0 for a page without text.
     */
    static float bodySize(List<Line> lines) {
        List<Line> bySize = new ArrayList<>(lines);
        bySize.sort(Comparator.comparingDouble(Line::fontSize));
        long total = 0;
        for (Line line : bySize) {
            total += line.weight();
        }
        if (total == 0) {
            return 0f;
        }
        long seen = 0;
        for (Line line : bySize) {
            seen += line.weight();
            if (seen * 2 >= total) {
                return line.fontSize();
            }
        }
        return bySize.getLast().fontSize();
    }

    /**
     * A short line with a letter in it, set at least {@link #HEADING_RATIO} times
     * the body size.
     */
    static boolean isHeading(Line line, float bodySize) {
        if (bodySize <= 0 || line.fontSize() < bodySize * HEADING_RATIO) {
            return false;
        }
        String text = line.text().strip();
        return !text.isEmpty() && text.length() <= MAX_HEADING_CHARACTERS && text.chars().anyMatch(Character::isLetter);
    }

    /**
     * Whether the page's body text stands in two or more columns: its lines' left
     * edges fall into two groups at least {@link #MIN_COLUMN_GAP} apart, each with
     * a quarter of the lines and at least three, and every line of the left group
     * ends before the right group begins. An indented paragraph fails the last
     * test, since its lines run across the gap.
     */
    static boolean multiColumn(List<Line> lines, float bodySize) {
        List<Line> body = new ArrayList<>();
        for (Line line : lines) {
            if (!isHeading(line, bodySize) && !line.text().isBlank()) {
                body.add(line);
            }
        }
        if (body.size() < MIN_COLUMN_LINES) {
            return false;
        }
        body.sort(Comparator.comparingDouble(Line::x));
        int least = Math.max(3, (body.size() + 3) / 4);
        // One pass, the left group's right edge carried along: a page may hold
        // many thousands of lines.
        float leftEnd = Float.NEGATIVE_INFINITY;
        for (int split = 1; split <= body.size() - least; split++) {
            leftEnd = Math.max(leftEnd, body.get(split - 1).xEnd());
            if (split < least) {
                continue;
            }
            float rightStart = body.get(split).x();
            if (rightStart - body.get(split - 1).x() >= MIN_COLUMN_GAP && leftEnd < rightStart) {
                return true;
            }
        }
        return false;
    }

    /**
     * The lines in reading order: top to bottom, and left to right among lines at
     * the same height. Lines whose tops lie within half the smaller font size of a
     * row's first line join that row and take its top, so equal heights compare
     * equal.
     */
    static List<Line> readingOrder(List<Line> lines) {
        List<Line> byTop = new ArrayList<>(lines);
        byTop.sort(Comparator.comparingDouble(Line::top).thenComparingDouble(Line::x));
        List<Line> ordered = new ArrayList<>(byTop.size());
        List<Line> row = new ArrayList<>();
        for (Line line : byTop) {
            if (!row.isEmpty()) {
                Line first = row.getFirst();
                float tolerance = Math.min(first.fontSize(), line.fontSize()) / 2;
                if (line.top() - first.top() > tolerance) {
                    flush(row, ordered);
                }
            }
            row.add(line);
        }
        flush(row, ordered);
        return ordered;
    }

    private static void flush(List<Line> row, List<Line> ordered) {
        if (row.isEmpty()) {
            return;
        }
        float top = row.getFirst().top();
        row.sort(Comparator.comparingDouble(Line::x));
        for (Line line : row) {
            ordered.add(new Line(line.text(), line.x(), line.xEnd(), top, line.fontSize()));
        }
        row.clear();
    }
}
