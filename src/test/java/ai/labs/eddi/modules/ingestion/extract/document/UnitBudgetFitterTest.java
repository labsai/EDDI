/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract.document;

import ai.labs.eddi.modules.ingestion.extract.document.UnitBudgetFitter.Result;
import ai.labs.eddi.modules.ingestion.extract.document.UnitBudgetFitter.Section;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnitBudgetFitterTest {

    private static SplitConfig config(int maxTokens, int minTokens, int maxUnits) {
        return new SplitConfig(SplitStrategy.OUTLINE, 10, maxTokens, minTokens, maxUnits);
    }

    private static int[] tokens(int pages, int each) {
        int[] tokens = new int[pages];
        Arrays.fill(tokens, each);
        return tokens;
    }

    private static String shape(Result result) {
        return result.units().stream().map(u -> u.id() + ":" + u.firstPage() + "-" + u.lastPage())
                .collect(Collectors.joining(" "));
    }

    @Nested
    @DisplayName("merging tiny units")
    class MergeTiny {

        @Test
        @DisplayName("merges a unit below the minimum into the one after it, naming both")
        void mergesForward() {
            var sections = List.of(Section.of("Preface", 1, 1), Section.of("Chapter 1", 2, 5),
                    Section.of("Chapter 2", 6, 9));

            Result result = UnitBudgetFitter.fit(sections, tokens(9, 100), config(10_000, 200, 60));

            assertEquals("u1:1-5 u2:6-9", shape(result));
            assertEquals("Preface – Chapter 1", result.units().get(0).title());
            assertEquals(500, result.units().get(0).estimatedTokens());
        }

        @Test
        @DisplayName("merges the last unit backwards, since nothing follows it")
        void mergesTheLastBackwards() {
            var sections = List.of(Section.of("Chapter 1", 1, 4), Section.of("Index", 5, 5));

            Result result = UnitBudgetFitter.fit(sections, tokens(5, 100), config(10_000, 200, 60));

            assertEquals("u1:1-5", shape(result));
            assertEquals("Chapter 1 – Index", result.units().get(0).title());
        }

        @Test
        @DisplayName("keeps merging a run of tiny units until the merged one reaches the minimum")
        void mergesARun() {
            List<Section> sections = new ArrayList<>();
            for (int page = 1; page <= 6; page++) {
                sections.add(Section.of("Chapter " + page, page, page));
            }

            Result result = UnitBudgetFitter.fit(sections, tokens(6, 100), config(10_000, 300, 60));

            assertEquals("u1:1-3 u2:4-6", shape(result));
            assertEquals("Chapter 1 – Chapter 3", result.units().get(0).title());
            assertEquals("Chapter 4 – Chapter 6", result.units().get(1).title());
        }

        @Test
        @DisplayName("gives a merged chapter's pages to the first part of the chapter that absorbs it")
        void extendsTheFirstPart() {
            var sections = List.of(Section.of("Preface", 1, 1), new Section("Chapter 1", 2, 9,
                    List.of(Section.of("1.1", 2, 5), Section.of("1.2", 6, 9))));

            Result result = UnitBudgetFitter.fit(sections, tokens(9, 100), config(10_000, 200, 60));

            assertEquals("u1:1-9 u1.1:1-5 u1.2:6-9", shape(result));
        }

        @Test
        @DisplayName("does not merge a document with no text, which would leave one unit of the whole file")
        void leavesATextlessDocumentAlone() {
            var sections = List.of(Section.of(null, 1, 10), Section.of(null, 11, 20), Section.of(null, 21, 25));

            Result result = UnitBudgetFitter.fit(sections, tokens(25, 0), config(10_000, 500, 60));

            assertEquals("u1:1-10 u2:11-20 u3:21-25", shape(result));
        }

        @Test
        @DisplayName("neither merges pages with no text nor merges into them, so a scan stays in windows")
        void textlessSectionsStayApart() {
            int[] tokens = new int[50];
            tokens[0] = 10;
            tokens[45] = 600;
            var windows = List.of(Section.of(null, 1, 10), Section.of(null, 11, 20), Section.of(null, 21, 30),
                    Section.of(null, 31, 40), Section.of(null, 41, 50));

            Result result = UnitBudgetFitter.fit(windows, tokens, config(10_000, 500, 60));

            assertEquals("u1:1-10 u2:11-20 u3:21-30 u4:31-40 u5:41-50", shape(result));
        }

        @Test
        @DisplayName("keeps a tiny last unit apart rather than merge it into pages with no text")
        void tinyAfterTextless() {
            int[] tokens = new int[20];
            tokens[15] = 10;
            var windows = List.of(Section.of(null, 1, 10), Section.of(null, 11, 20));

            Result result = UnitBudgetFitter.fit(windows, tokens, config(10_000, 500, 60));

            assertEquals("u1:1-10 u2:11-20", shape(result));
        }

        @Test
        @DisplayName("never merges with a minimum of zero")
        void zeroMinimumNeverMerges() {
            var sections = List.of(Section.of("a", 1, 1), Section.of("b", 2, 2));

            Result result = UnitBudgetFitter.fit(sections, tokens(2, 1), config(10_000, 0, 60));

            assertEquals("u1:1-1 u2:2-2", shape(result));
        }
    }

    @Nested
    @DisplayName("splitting large units")
    class SplitLarge {

        @Test
        @DisplayName("cuts a unit over the budget at page boundaries into parts as full as the budget allows")
        void cutsAtPageBoundaries() {
            var sections = List.of(Section.of("Intro", 1, 2), Section.of("Giant", 3, 32), Section.of("End", 33, 34));

            Result result = UnitBudgetFitter.fit(sections, tokens(34, 300), config(2_000, 500, 60));

            assertEquals("u1:1-2 u2:3-32 u2.1:3-8 u2.2:9-14 u2.3:15-20 u2.4:21-26 u2.5:27-32 u3:33-34",
                    shape(result));
            assertEquals("Giant (part 2 of 5)", result.units().get(3).title());
            assertEquals(2, result.units().get(3).level());
            assertEquals("u2", result.units().get(3).parentId());
            assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        }

        @Test
        @DisplayName("names the parts of an untitled window by their own pages")
        void untitledParts() {
            Result result = UnitBudgetFitter.fit(List.of(Section.of(null, 1, 10)), tokens(10, 300),
                    config(2_000, 0, 60));

            assertEquals("u1:1-10 u1.1:1-6 u1.2:7-10", shape(result));
            assertEquals(List.of("Pages 1–10", "Pages 1–6", "Pages 7–10"),
                    result.units().stream().map(DocumentUnit::title).toList());
        }

        @Test
        @DisplayName("fills each part before starting the next, so a short last page joins the part before")
        void fillsEachPart() {
            int[] tokens = {900, 900, 900, 900, 100};
            Result result = UnitBudgetFitter.fit(List.of(Section.of("All", 1, 5)), tokens, config(2_000, 500, 60));

            assertEquals("u1:1-5 u1.1:1-2 u1.2:3-5", shape(result));
        }

        @Test
        @DisplayName("lets a page above the budget stand alone, and says so")
        void aPageAboveTheBudget() {
            int[] tokens = {100, 5_000, 100};
            Result result = UnitBudgetFitter.fit(List.of(Section.of("All", 1, 3)), tokens, config(1_000, 0, 60));

            assertEquals("u1:1-3 u1.1:1-1 u1.2:2-2 u1.3:3-3", shape(result));
            assertEquals(1, result.warnings().size(), result.warnings().toString());
            assertTrue(result.warnings().get(0).startsWith("1 page is"), result.warnings().get(0));
        }

        @Test
        @DisplayName("says so when a one-page unit is above the budget and cannot be cut")
        void aOnePageUnitAboveTheBudget() {
            var sections = List.of(Section.of("Small", 1, 1), Section.of("Dense", 2, 2));

            Result result = UnitBudgetFitter.fit(sections, new int[]{100, 5_000}, config(1_000, 0, 60));

            assertEquals("u1:1-1 u2:2-2", shape(result));
            assertEquals(1, result.warnings().size(), result.warnings().toString());
            assertTrue(result.warnings().get(0).startsWith("1 page is"), result.warnings().get(0));
        }

        @Test
        @DisplayName("splits a part of a chapter that is over the budget, one level further down")
        void splitsAPart() {
            var sections = List.of(new Section("Chapter", 1, 6,
                    List.of(Section.of("Short", 1, 1), Section.of("Long", 2, 6))));

            Result result = UnitBudgetFitter.fit(sections, tokens(6, 400), config(1_000, 0, 60));

            assertEquals("u1:1-6 u1.1:1-1 u1.2:2-6 u1.2.1:2-3 u1.2.2:4-5 u1.2.3:6-6", shape(result));
            assertEquals(3, result.units().get(3).level());
        }
    }

    @Nested
    @DisplayName("capping the count")
    class CapCount {

        @Test
        @DisplayName("merges the last units until no more than maxUnits are left, and says so")
        void mergesTheTail() {
            List<Section> windows = new ArrayList<>();
            for (int first = 1; first <= 25; first += 2) {
                windows.add(Section.of(null, first, Math.min(25, first + 1)));
            }

            Result result = UnitBudgetFitter.fit(windows, tokens(25, 10), config(10_000, 0, 5));

            assertEquals("u1:1-2 u2:3-4 u3:5-6 u4:7-8 u5:9-25", shape(result));
            assertEquals("Pages 9–25", result.units().get(4).title());
            assertEquals(1, result.warnings().size());
            assertTrue(result.warnings().get(0).contains("13 units"), result.warnings().get(0));
        }

        @Test
        @DisplayName("merges parts first, then the chapter they belong to collapses into one unit")
        void collapsesAParent() {
            var sections = List.of(Section.of("One", 1, 2), new Section("Two", 3, 8,
                    List.of(Section.of("2.1", 3, 4), Section.of("2.2", 5, 6), Section.of("2.3", 7, 8))));

            Result result = UnitBudgetFitter.fit(sections, tokens(8, 10), config(10_000, 0, 2));

            assertEquals("u1:1-2 u2:3-8", shape(result));
            assertEquals("Two", result.units().get(1).title());
        }

        @Test
        @DisplayName("names the merged chapter in the title of the part that now holds it")
        void mergedIntoAPartRenamesThePart() {
            var sections = List.of(new Section("Ch1", 1, 4, List.of(Section.of("1.1", 1, 2), Section.of("1.2", 3, 4))),
                    Section.of("Ch2", 5, 6));

            Result result = UnitBudgetFitter.fit(sections, tokens(6, 10), config(10_000, 0, 2));

            assertEquals("u1:1-6 u1.1:1-2 u1.2:3-6", shape(result));
            assertEquals(List.of("Ch1 – Ch2", "1.1", "1.2 – Ch2"),
                    result.units().stream().map(DocumentUnit::title).toList());
        }

        @Test
        @DisplayName("says when the merged last unit is now over the budget")
        void namesTheBudgetItBreaks() {
            var sections = List.of(Section.of("a", 1, 1), Section.of("b", 2, 2), Section.of("c", 3, 3));

            Result result = UnitBudgetFitter.fit(sections, tokens(3, 800), config(1_000, 0, 1));

            assertEquals("u1:1-3", shape(result));
            assertTrue(result.warnings().get(0).contains("above maxUnitTokens"), result.warnings().get(0));
        }
    }

    @Nested
    @DisplayName("titles and input")
    class TitlesAndInput {

        @Test
        @DisplayName("describes an untitled unit by its pages")
        void untitled() {
            Result result = UnitBudgetFitter.fit(List.of(Section.of(null, 1, 1), Section.of(null, 2, 3)),
                    tokens(3, 1), config(10_000, 0, 60));

            assertEquals("Page 1", result.units().get(0).title());
            assertEquals("Pages 2–3", result.units().get(1).title());
        }

        @Test
        @DisplayName("cuts a long title")
        void capsTitles() {
            Result result = UnitBudgetFitter.fit(List.of(Section.of("x".repeat(500), 1, 1)), tokens(1, 1),
                    config(10_000, 0, 60));

            String title = result.units().get(0).title();
            assertEquals(UnitBudgetFitter.MAX_TITLE_CODE_POINTS, title.codePointCount(0, title.length()));
            assertTrue(title.endsWith("…"));
        }

        @Test
        @DisplayName("keeps the part number when a long title is cut, so parts stay distinguishable")
        void longTitlesKeepTheirPartNumber() {
            Result result = UnitBudgetFitter.fit(List.of(Section.of("y".repeat(300), 1, 3)), tokens(3, 800),
                    config(1_000, 0, 60));

            List<String> parts = result.units().stream().filter(u -> u.parentId() != null)
                    .map(DocumentUnit::title).toList();
            assertEquals(3, parts.size());
            for (int k = 0; k < parts.size(); k++) {
                String title = parts.get(k);
                assertTrue(title.endsWith("… (part " + (k + 1) + " of 3)"), title);
                assertEquals(UnitBudgetFitter.MAX_TITLE_CODE_POINTS, title.codePointCount(0, title.length()));
            }
        }

        @Test
        @DisplayName("refuses sections that leave a page out, overlap, or run past the end")
        void refusesBadCover() {
            int[] five = tokens(5, 1);
            SplitConfig any = config(10_000, 0, 60);
            assertThrows(IllegalArgumentException.class,
                    () -> UnitBudgetFitter.fit(List.of(Section.of("a", 1, 2), Section.of("b", 4, 5)), five, any));
            assertThrows(IllegalArgumentException.class,
                    () -> UnitBudgetFitter.fit(List.of(Section.of("a", 1, 3), Section.of("b", 3, 5)), five, any));
            assertThrows(IllegalArgumentException.class,
                    () -> UnitBudgetFitter.fit(List.of(Section.of("a", 1, 6)), five, any));
            assertThrows(IllegalArgumentException.class, () -> UnitBudgetFitter.fit(
                    List.of(new Section("a", 1, 5, List.of(Section.of("a1", 2, 5)))), five, any));
            assertThrows(IllegalArgumentException.class, () -> UnitBudgetFitter.fit(List.of(), five, any));
        }

        @Test
        @DisplayName("treats a chapter with a single part as having none")
        void singlePartIsNoPart() {
            var sections = List.of(new Section("a", 1, 5, List.of(Section.of("only", 1, 5))));

            Result result = UnitBudgetFitter.fit(sections, tokens(5, 1), config(10_000, 0, 60));

            assertEquals("u1:1-5", shape(result));
            assertNull(result.units().get(0).parentId());
        }
    }

    /**
     * Random sections, estimates and budgets, many times over: whatever the input,
     * the leaves cover the document once, parents cover their parts, ids are well
     * formed and unique, the count cap holds, and the same input gives the same
     * units.
     */
    @Test
    @DisplayName("the leaves always cover every page once, whatever the input")
    void leavesPartitionThePages() {
        var random = new Random(20261118L);
        for (int run = 0; run < 2_000; run++) {
            int pages = 1 + random.nextInt(80);
            int[] tokens = new int[pages];
            boolean textless = random.nextInt(10) == 0;
            for (int i = 0; i < pages; i++) {
                tokens[i] = textless ? 0 : random.nextInt(4) == 0 ? random.nextInt(8_000) : random.nextInt(600);
            }
            List<Section> sections = randomSections(random, 1, pages, 0);
            var config = new SplitConfig(SplitStrategy.AUTO, 10, 200 + random.nextInt(5_000), random.nextInt(800),
                    1 + random.nextInt(30));
            String context = "run " + run + " pages " + pages + " " + config;

            Result result = UnitBudgetFitter.fit(sections, tokens, config);

            assertInvariants(result, pages, config, context);
            assertEquals(result, UnitBudgetFitter.fit(sections, tokens, config), context);
        }
    }

    private static List<Section> randomSections(Random random, int from, int to, int depth) {
        List<Section> sections = new ArrayList<>();
        int first = from;
        while (first <= to) {
            int last = Math.min(to, first + random.nextInt(Math.max(1, (to - from + 1) / 2 + 1)));
            List<Section> children = depth < 2 && last > first && random.nextBoolean()
                    ? randomSections(random, first, last, depth + 1)
                    : List.of();
            sections.add(new Section(random.nextBoolean() ? null : "s" + first, first, last, children));
            first = last + 1;
        }
        return sections;
    }

    /** What every fitted result must satisfy. Shared with the extractor's tests. */
    static void assertInvariants(Result result, int pages, SplitConfig config, String context) {
        List<DocumentUnit> units = result.units();
        Map<String, DocumentUnit> byId = units.stream()
                .collect(Collectors.toMap(DocumentUnit::id, Function.identity()));
        assertEquals(units.size(), byId.size(), "ids are unique: " + context);

        var manifest = new DocumentManifest("ref", "sha", pages, SplitStrategy.PAGES, units, List.of(), List.of());
        List<DocumentUnit> leaves = manifest.leaves();
        int next = 1;
        for (DocumentUnit leaf : leaves) {
            assertEquals(next, leaf.firstPage(), "leaves are contiguous: " + context);
            assertTrue(leaf.lastPage() >= leaf.firstPage(), context);
            next = leaf.lastPage() + 1;
        }
        assertEquals(pages + 1, next, "leaves end at the last page: " + context);
        assertTrue(leaves.size() <= config.maxUnits(), "at most maxUnits leaves: " + context);

        for (DocumentUnit unit : units) {
            String expectedPattern = unit.parentId() == null ? "u\\d+" : "\\Q" + unit.parentId() + "\\E\\.\\d+";
            assertTrue(unit.id().matches(expectedPattern), unit.id() + ": " + context);
            assertEquals(unit.id().split("\\.").length, unit.level(), context);
            List<DocumentUnit> parts = units.stream().filter(u -> unit.id().equals(u.parentId())).toList();
            assertFalse(parts.size() == 1, "a unit never has a single part: " + context);
            if (!parts.isEmpty()) {
                assertEquals(unit.firstPage(), parts.getFirst().firstPage(), context);
                assertEquals(unit.lastPage(), parts.getLast().lastPage(), context);
                assertEquals(unit.estimatedTokens(), parts.stream().mapToInt(DocumentUnit::estimatedTokens).sum(),
                        context);
            }
            if (unit.parentId() != null) {
                assertTrue(byId.containsKey(unit.parentId()), context);
            }
        }
    }
}
