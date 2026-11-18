/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract.document;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Fits a document's sections to a token budget and numbers the result.
 *
 * <p>
 * Pure: it is given the sections a strategy found (outline chapters, or page
 * windows) and an estimate per page, and returns units. Four steps, in order:
 * <ol>
 * <li><b>Merge the tiny.</b> A section estimated below
 * {@link SplitConfig#minUnitTokens()} is merged into the section after it, or,
 * when it is the last of its list, into the one before. Its pages join the
 * neighbour's first (or last) part, and the neighbour's title names both. A
 * document with no text at all is not merged — every estimate is zero, and
 * merging would leave one unit of the whole file.</li>
 * <li><b>Split the large.</b> A section with no parts that is estimated above
 * {@link SplitConfig#maxUnitTokens()} is cut at page boundaries into parts as
 * full as the budget allows: a part ends only where the next page would not
 * fit, so a short last part cannot be merged into the one before. A single page
 * above the budget becomes a part of its own, and the result says so.</li>
 * <li><b>Cap the count.</b> While there are more leaf units than
 * {@link SplitConfig#maxUnits()}, the last leaf is merged into the leaf before
 * it, whatever that does to the budget; the result says so.</li>
 * <li><b>Number.</b> Top-level units are {@code u1, u2, …} in document order,
 * the parts of {@code u3} are {@code u3.1, u3.2, …}, and so on down.</li>
 * </ol>
 *
 * <p>
 * Every step keeps two invariants, which the caller's sections must already
 * satisfy: the top-level sections cover pages {@code 1..pageCount} exactly once
 * in order, and a section's parts cover exactly its pages. So the leaf units
 * always cover the document once. The same input always gives the same units.
 */
public final class UnitBudgetFitter {

    /** The longest title a unit is given, in code points, before it is cut. */
    static final int MAX_TITLE_CODE_POINTS = 200;

    private UnitBudgetFitter() {
    }

    /**
     * A part of the document as a strategy found it.
     *
     * @param title
     *            the section's title, or null to describe it by its pages
     * @param children
     *            its parts, covering exactly its pages; empty when it has none
     */
    public record Section(String title, int firstPage, int lastPage, List<Section> children) {

        public Section {
            children = children == null ? List.of() : List.copyOf(children);
        }

        /** A section with no parts. */
        public static Section of(String title, int firstPage, int lastPage) {
            return new Section(title, firstPage, lastPage, List.of());
        }
    }

    /**
     * @param units
     *            parents before their children, in document order
     * @param warnings
     *            what the fitting had to give up, in plain language
     */
    public record Result(List<DocumentUnit> units, List<String> warnings) {

        public Result {
            units = List.copyOf(units);
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * @param sections
     *            covering pages {@code 1..pageTokens.length} exactly once, in
     *            order; a section's children cover exactly its pages
     * @param pageTokens
     *            the estimate for each page, page 1 first
     * @throws IllegalArgumentException
     *             when the sections do not cover the pages as described
     */
    public static Result fit(List<Section> sections, int[] pageTokens, SplitConfig config) {
        Objects.requireNonNull(config, "config");
        if (pageTokens == null || pageTokens.length == 0) {
            throw new IllegalArgumentException("A document has at least one page");
        }
        requireCover(sections, 1, pageTokens.length, "the document");

        var fitter = new Fitting(pageTokens, config);
        List<Node> top = new ArrayList<>();
        for (Section section : sections) {
            top.add(Node.of(section));
        }
        for (Node node : top) {
            collapseSingleParts(node);
        }
        if (config.minUnitTokens() > 0 && fitter.prefix[pageTokens.length] > 0) {
            fitter.mergeTiny(top);
        }
        fitter.splitLarge(top);
        fitter.reportPagesOverBudget();
        fitter.capCount(top);

        List<DocumentUnit> units = new ArrayList<>();
        fitter.number(top, null, 1, units);
        return new Result(units, fitter.warnings);
    }

    private static void requireCover(List<Section> sections, int from, int to, String what) {
        if (sections == null || sections.isEmpty()) {
            throw new IllegalArgumentException("No sections for " + what);
        }
        int next = from;
        for (Section section : sections) {
            if (section.firstPage() != next || section.lastPage() < section.firstPage()) {
                throw new IllegalArgumentException("Sections must cover pages " + from + "–" + to + " of " + what
                        + " in order, once each; page " + next + " is not where a section starts");
            }
            if (!section.children().isEmpty()) {
                requireCover(section.children(), section.firstPage(), section.lastPage(), "a section");
            }
            next = section.lastPage() + 1;
        }
        if (next != to + 1) {
            throw new IllegalArgumentException("Sections must cover pages " + from + "–" + to + " of " + what
                    + "; they end at page " + (next - 1));
        }
    }

    /** A section with one part is that part's parts. Applied all the way down. */
    private static void collapseSingleParts(Node node) {
        while (node.children.size() == 1) {
            node.children = node.children.get(0).children;
        }
        for (Node child : node.children) {
            collapseSingleParts(child);
        }
    }

    /** A section under construction. Mutable, and private to one fitting. */
    private static final class Node {
        String firstTitle;
        String lastTitle;
        int first;
        int last;
        List<Node> children = new ArrayList<>();

        static Node of(Section section) {
            var node = new Node();
            node.firstTitle = section.title();
            node.lastTitle = section.title();
            node.first = section.firstPage();
            node.last = section.lastPage();
            for (Section child : section.children()) {
                node.children.add(of(child));
            }
            return node;
        }

        static Node leaf(String title, int first, int last) {
            var node = new Node();
            node.firstTitle = title;
            node.lastTitle = title;
            node.first = first;
            node.last = last;
            return node;
        }

        boolean isLeaf() {
            return children.isEmpty();
        }

        /** Takes over the pages of {@code tiny}, which came just before this. */
        void absorbFromBefore(Node tiny) {
            firstTitle = tiny.firstTitle;
            for (Node node = this; node != null; node = node.isLeaf() ? null : node.children.get(0)) {
                node.first = tiny.first;
            }
        }

        /** Takes over the pages of {@code tail}, which came just after this. */
        void absorbFromAfter(Node tail) {
            lastTitle = tail.lastTitle;
            for (Node node = this; node != null; node = node.isLeaf() ? null : node.children.getLast()) {
                node.last = tail.last;
            }
        }
    }

    private static final class Fitting {
        private final long[] prefix;
        private final SplitConfig config;
        private final List<String> warnings = new ArrayList<>();
        private int pagesOverBudget;

        Fitting(int[] pageTokens, SplitConfig config) {
            this.config = config;
            this.prefix = new long[pageTokens.length + 1];
            for (int i = 0; i < pageTokens.length; i++) {
                prefix[i + 1] = prefix[i] + Math.max(0, pageTokens[i]);
            }
        }

        long tokens(int first, int last) {
            return prefix[last] - prefix[first - 1];
        }

        long tokens(Node node) {
            return tokens(node.first, node.last);
        }

        void mergeTiny(List<Node> list) {
            int i = 0;
            while (list.size() > 1 && i < list.size()) {
                Node node = list.get(i);
                if (tokens(node) >= config.minUnitTokens()) {
                    i++;
                } else if (i + 1 < list.size()) {
                    list.get(i + 1).absorbFromBefore(node);
                    list.remove(i);
                } else {
                    list.get(i - 1).absorbFromAfter(node);
                    list.remove(i);
                    i--;
                }
            }
            for (Node node : list) {
                if (!node.isLeaf()) {
                    mergeTiny(node.children);
                    collapseSingleParts(node);
                }
            }
        }

        void splitLarge(List<Node> list) {
            for (Node node : list) {
                if (!node.isLeaf()) {
                    splitLarge(node.children);
                    continue;
                }
                if (tokens(node) <= config.maxUnitTokens()) {
                    continue;
                }
                List<int[]> parts = cut(node.first, node.last);
                if (parts.size() < 2) {
                    pagesOverBudget++;
                    continue;
                }
                String title = display(node);
                for (int k = 0; k < parts.size(); k++) {
                    int[] part = parts.get(k);
                    if (part[0] == part[1] && tokens(part[0], part[1]) > config.maxUnitTokens()) {
                        pagesOverBudget++;
                    }
                    node.children.add(Node.leaf(title + " (part " + (k + 1) + " of " + parts.size() + ")",
                            part[0], part[1]));
                }
            }
        }

        /** Page ranges as full as the budget allows; a page alone may exceed it. */
        private List<int[]> cut(int first, int last) {
            List<int[]> parts = new ArrayList<>();
            int start = first;
            long sum = 0;
            for (int page = first; page <= last; page++) {
                long pageTokens = tokens(page, page);
                if (page > start && sum + pageTokens > config.maxUnitTokens()) {
                    parts.add(new int[]{start, page - 1});
                    start = page;
                    sum = 0;
                }
                sum += pageTokens;
            }
            parts.add(new int[]{start, last});
            return parts;
        }

        void reportPagesOverBudget() {
            if (pagesOverBudget > 0) {
                warnings.add(pagesOverBudget + (pagesOverBudget == 1 ? " page is" : " pages are")
                        + " each estimated above maxUnitTokens (" + config.maxUnitTokens()
                        + ") on their own, and form units larger than the budget.");
            }
        }

        void capCount(List<Node> top) {
            int leaves = countLeaves(top);
            if (leaves <= config.maxUnits()) {
                return;
            }
            int before = leaves;
            while (leaves > config.maxUnits()) {
                mergeLastLeaf(top);
                leaves--;
            }
            String overBudget = tokens(lastLeaf(top)) > config.maxUnitTokens()
                    ? " The last unit is now estimated above maxUnitTokens (" + config.maxUnitTokens() + ")."
                    : "";
            warnings.add("The document would be cut into " + before + " units, more than maxUnits ("
                    + config.maxUnits() + "); the last units were merged." + overBudget);
        }

        private static int countLeaves(List<Node> list) {
            int count = 0;
            for (Node node : list) {
                count += node.isLeaf() ? 1 : countLeaves(node.children);
            }
            return count;
        }

        private static Node lastLeaf(List<Node> list) {
            Node node = list.getLast();
            while (!node.isLeaf()) {
                node = node.children.getLast();
            }
            return node;
        }

        /**
         * Merges the last leaf into the leaf before it, one fewer leaf in all. The last
         * leaf always has a sibling before it: a list of parts has two or more.
         */
        private static void mergeLastLeaf(List<Node> top) {
            List<Node> list = top;
            Deque<Node> owners = new ArrayDeque<>();
            while (!list.getLast().isLeaf()) {
                owners.push(list.getLast());
                list = list.getLast().children;
            }
            Node tail = list.removeLast();
            list.getLast().absorbFromAfter(tail);
            if (!owners.isEmpty()) {
                collapseSingleParts(owners.peek());
            }
        }

        void number(List<Node> list, String parentId, int level, List<DocumentUnit> out) {
            for (int i = 0; i < list.size(); i++) {
                Node node = list.get(i);
                String id = parentId == null ? "u" + (i + 1) : parentId + "." + (i + 1);
                long estimate = tokens(node);
                out.add(new DocumentUnit(id, display(node), level, parentId, node.first, node.last,
                        (int) Math.min(Integer.MAX_VALUE, estimate), 0, 0, 0));
                number(node.children, id, level + 1, out);
            }
        }

        private static String display(Node node) {
            String first = node.firstTitle;
            String last = node.lastTitle;
            String title;
            if (first == null && last == null) {
                title = node.first == node.last ? "Page " + node.first : "Pages " + node.first + "–" + node.last;
            } else if (first == null || last == null || first.equals(last)) {
                title = first == null ? last : first;
            } else {
                title = first + " – " + last;
            }
            return capped(title);
        }

        private static String capped(String title) {
            if (title.codePointCount(0, title.length()) <= MAX_TITLE_CODE_POINTS) {
                return title;
            }
            return title.substring(0, title.offsetByCodePoints(0, MAX_TITLE_CODE_POINTS - 1)) + "…";
        }
    }
}
