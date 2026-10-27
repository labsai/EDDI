/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeFilter;
import org.jsoup.select.NodeTraversor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The {@code <style>}-block half of the mail hidden-content pass: elements a
 * {@code display:none} or {@code visibility:hidden} rule hides.
 * <p>
 * Two properties matter more than precision, because the style block is written
 * by whoever sent the mail:
 * <ul>
 * <li><b>Linear.</b> The CSS is read by one forward scan ({@code indexOf}, no
 * backtracking regex), and elements are matched in one pass against an index of
 * the rules — never one document search per rule.</li>
 * <li><b>Never a bypass.</b> Nothing is truncated or capped in a way that would
 * let a rule placed "after the limit" go unread. Where exactness would cost
 * more than linear time, the match <em>over</em>-approximates: a rule is
 * matched by its rightmost compound selector alone (ancestors ignored;
 * attribute filters by the attribute's presence; {@code :empty}, {@code :root}
 * and the {@code -child} positions exactly, other pseudo-classes ignored), and
 * an element that more than {@link #MAX_CANDIDATES} rules could apply to is
 * treated as hidden. Both can only remove more than a browser would hide, never
 * less.</li>
 * </ul>
 * Rules inside {@code @media} and other at-blocks are skipped (they apply only
 * in some viewports, and responsive mail hides one of two copies of the same
 * content there); state pseudo-classes such as {@code :hover} are skipped,
 * since the element is visible until hovered; and
 * {@code ::before}/{@code ::after} rules hide generated content, not the
 * element.
 */
final class HiddenStylesheetRules {

    /**
     * How many rules may name one element before it counts as hidden outright. A
     * real e-mail has a handful of hiding rules; an element hundreds of them could
     * apply to only exists in a stylesheet built to make matching expensive.
     */
    static final int MAX_CANDIDATES = 64;

    private static final Set<String> STATE_PSEUDO_CLASSES = Set.of("hover", "focus", "active", "visited", "focus-within",
            "focus-visible", "target", "checked", "link", "any-link");

    private HiddenStylesheetRules() {
    }

    /**
     * Where an element sits among its parent's element children: {@code index}
     * counts from 1, {@code total} is the number of element siblings including
     * itself. Counted during the one traversal, so evaluating it is O(1).
     */
    record Position(int index, int total) {
    }

    /**
     * A structural pseudo-class this reader evaluates exactly, all as {@code an+b}
     * positions: {@code :first-child} is {@code NTH 0 1}, {@code :last-child}
     * {@code NTH_LAST 0 1}.
     */
    record Structural(Kind kind, int a, int b) {
        enum Kind {
            NTH, NTH_LAST, EMPTY, ROOT
        }

        boolean matches(Element element, Position position) {
            return switch (kind) {
                case NTH -> isNth(position.index());
                case NTH_LAST -> isNth(position.total() - position.index() + 1);
                case EMPTY -> isEmpty(element);
                case ROOT -> element.parent() == null || element.parent() instanceof Document;
            };
        }

        private boolean isNth(int index) {
            if (a == 0) {
                return index == b;
            }
            int offset = index - b;
            return offset % a == 0 && offset / a >= 0;
        }

        /** CSS {@code :empty}: no child nodes but comments. */
        private static boolean isEmpty(Element element) {
            for (Node child : element.childNodes()) {
                if (!(child instanceof Comment)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * One compound selector: an optional tag, an optional id, classes, the names of
     * the attributes its attribute filters test, and the structural pseudo-classes
     * this reader evaluates. All lower-case.
     * <p>
     * An attribute filter is matched by the attribute's presence alone (its value
     * test is ignored, which widens the match). Pseudo-classes outside
     * {@link Structural} are ignored, as before. Without the presence test, a reset
     * rule such as {@code [hidden]{display:none}} — common in mail templates —
     * reduced to "every element", matched {@code <body>} and emptied the mail.
     */
    record Compound(String tag, String id, Set<String> classes, Set<String> attributes, List<Structural> structural) {
        Compound(String tag, String id, Set<String> classes) {
            this(tag, id, classes, Set.of(), List.of());
        }

        boolean matches(Element element, Set<String> ownClasses, Position position) {
            if (tag != null && !tag.equals(element.normalName())) {
                return false;
            }
            if (id != null && !id.equals(element.id().toLowerCase(Locale.ROOT))) {
                return false;
            }
            if (!classes.isEmpty() && !ownClasses.containsAll(classes)) {
                return false;
            }
            for (String attribute : attributes) {
                if (!element.hasAttr(attribute)) {
                    return false;
                }
            }
            for (Structural pseudo : structural) {
                if (!pseudo.matches(element, position)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Hiding rules indexed by the most specific thing they name. */
    static final class Index {
        final Map<String, List<Compound>> byId = new HashMap<>();
        final Map<String, List<Compound>> byClass = new HashMap<>();
        final Map<String, List<Compound>> byTag = new HashMap<>();
        final List<Compound> universal = new ArrayList<>();

        void add(Compound compound) {
            if (compound.id() != null) {
                byId.computeIfAbsent(compound.id(), k -> new ArrayList<>()).add(compound);
            } else if (!compound.classes().isEmpty()) {
                byClass.computeIfAbsent(compound.classes().iterator().next(), k -> new ArrayList<>()).add(compound);
            } else if (compound.tag() != null) {
                byTag.computeIfAbsent(compound.tag(), k -> new ArrayList<>()).add(compound);
            } else {
                universal.add(compound);
            }
        }

        boolean isEmpty() {
            return byId.isEmpty() && byClass.isEmpty() && byTag.isEmpty() && universal.isEmpty();
        }

        boolean hides(Element element, Position position) {
            List<List<Compound>> candidates = new ArrayList<>();
            int count = 0;
            Set<String> ownClasses = new HashSet<>();
            for (String name : element.classNames()) {
                ownClasses.add(name.toLowerCase(Locale.ROOT));
            }
            String id = element.id();
            if (!id.isEmpty()) {
                count += collect(byId.get(id.toLowerCase(Locale.ROOT)), candidates);
            }
            for (String name : ownClasses) {
                count += collect(byClass.get(name), candidates);
                if (count > MAX_CANDIDATES) {
                    return true;
                }
            }
            count += collect(byTag.get(element.normalName()), candidates);
            count += collect(universal, candidates);
            if (count > MAX_CANDIDATES) {
                return true;
            }
            for (List<Compound> list : candidates) {
                for (Compound compound : list) {
                    if (compound.matches(element, ownClasses, position)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static int collect(List<Compound> list, List<List<Compound>> into) {
            if (list == null || list.isEmpty()) {
                return 0;
            }
            into.add(list);
            return list.size();
        }
    }

    /** Removes the elements the document's own style blocks hide. */
    static int removeMatching(Document doc) {
        Index index = new Index();
        for (Element style : doc.select("style")) {
            readRules(style.data(), index);
        }
        if (index.isEmpty()) {
            return 0;
        }
        List<Element> hidden = new ArrayList<>();
        // Element children seen so far, per parent: the position of each element in
        // O(1), in document order. A hidden element is counted too, so its later
        // siblings keep their real positions.
        Map<Element, int[]> seen = new IdentityHashMap<>();
        NodeTraversor.filter(new NodeFilter() {
            @Override
            public FilterResult head(Node node, int depth) {
                if (node instanceof Element element) {
                    Position position = position(element, seen);
                    if (index.hides(element, position)) {
                        hidden.add(element);
                        return FilterResult.SKIP_ENTIRELY;
                    }
                }
                return FilterResult.CONTINUE;
            }

            @Override
            public FilterResult tail(Node node, int depth) {
                return FilterResult.CONTINUE;
            }
        }, doc.body() == null ? doc : doc.body());
        hidden.forEach(Element::remove);
        return hidden.size();
    }

    private static Position position(Element element, Map<Element, int[]> seen) {
        Element parent = element.parent();
        if (parent == null) {
            return new Position(1, 1);
        }
        // The first child of a parent seen here is normally its first element child
        // (an O(1) lookup); only the traversal root may start mid-list.
        int[] count = seen.computeIfAbsent(parent, p -> new int[]{element.elementSiblingIndex()});
        count[0]++;
        return new Position(count[0], parent.childrenSize());
    }

    /**
     * Reads every top-level rule of a style block in one forward pass and indexes
     * the selectors of those that hide. Comments are skipped, at-blocks skipped
     * whole (nested braces counted), at-statements skipped to their {@code ;}.
     */
    static void readRules(String css, Index index) {
        int length = css.length();
        int i = 0;
        while (i < length) {
            char c = css.charAt(i);
            if (c == '/' && i + 1 < length && css.charAt(i + 1) == '*') {
                int end = css.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else if (c == '@') {
                i = skipAtRule(css, i);
            } else if (c == '}' || Character.isWhitespace(c) || c == ';') {
                i++;
            } else {
                i = readRule(css, i, index);
            }
        }
    }

    /**
     * Skips an at-statement or at-block starting at {@code start}; returns the
     * index after it.
     */
    private static int skipAtRule(String css, int start) {
        int length = css.length();
        int i = start + 1;
        while (i < length) {
            char c = css.charAt(i);
            if (c == ';') {
                return i + 1;
            }
            if (c == '{') {
                return skipBlock(css, i);
            }
            if (c == '/' && i + 1 < length && css.charAt(i + 1) == '*') {
                int end = css.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
                continue;
            }
            i++;
        }
        return length;
    }

    /**
     * Skips a brace-balanced block whose {@code {} is at {@code open}, comments
     * included.
     */
    private static int skipBlock(String css, int open) {
        int length = css.length();
        int depth = 0;
        int i = open;
        while (i < length) {
            char c = css.charAt(i);
            if (c == '/' && i + 1 < length && css.charAt(i + 1) == '*') {
                int end = css.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i + 1;
            }
            i++;
        }
        return length;
    }

    /**
     * Reads one {@code selectors { declarations }} rule from {@code start} and
     * returns the index after it. Each character is visited once: the selector runs
     * to the first {@code {}, the declarations to the first {@code }}.
     */
    private static int readRule(String css, int start, Index index) {
        int length = css.length();
        StringBuilder selectors = new StringBuilder();
        int i = start;
        while (i < length && css.charAt(i) != '{') {
            char c = css.charAt(i);
            if (c == '/' && i + 1 < length && css.charAt(i + 1) == '*') {
                int end = css.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
                continue;
            }
            if (c == '}') {
                // A stray close brace: what came before it was not a selector.
                selectors.setLength(0);
            } else {
                selectors.append(c);
            }
            i++;
        }
        if (i >= length) {
            return length;
        }
        int close = skipBlock(css, i);
        String body = css.substring(i + 1, Math.max(i + 1, close - 1));
        Map<String, String> declarations = HiddenContentFilter.declarations(stripComments(body));
        if ("none".equals(declarations.get("display")) || "hidden".equals(declarations.get("visibility"))
                || "all".equals(declarations.get("mso-hide"))) {
            for (String selector : splitTopLevel(selectors.toString())) {
                Compound compound = rightmostCompound(selector);
                if (compound != null) {
                    index.add(compound);
                }
            }
        }
        return close;
    }

    private static String stripComments(String css) {
        if (!css.contains("/*")) {
            return css;
        }
        StringBuilder out = new StringBuilder(css.length());
        int i = 0;
        while (i < css.length()) {
            int open = css.indexOf("/*", i);
            if (open < 0) {
                out.append(css, i, css.length());
                break;
            }
            out.append(css, i, open);
            int end = css.indexOf("*/", open + 2);
            i = end < 0 ? css.length() : end + 2;
        }
        return out.toString();
    }

    /** Splits a selector list on commas outside parentheses and brackets. */
    static List<String> splitTopLevel(String selectors) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < selectors.length(); i++) {
            char c = selectors.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if ((c == ')' || c == ']') && depth > 0) {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(selectors.substring(from, i));
                from = i + 1;
            }
        }
        parts.add(selectors.substring(from));
        return parts;
    }

    /**
     * The rightmost compound of a complex selector, reduced to tag, id and classes,
     * or null when the rule does not hide the element in its resting state (a state
     * pseudo-class) or hides generated content only.
     */
    static Compound rightmostCompound(String selector) {
        String trimmed = selector.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        int depth = 0;
        int start = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\\') {
                // An escape, including the one space that may end a hex escape, is
                // part of a name, not a combinator.
                int j = i + 1;
                while (j < trimmed.length() && j - i <= 6 && Character.digit(trimmed.charAt(j), 16) >= 0) {
                    j++;
                }
                i = j > i + 1 ? (j < trimmed.length() && Character.isWhitespace(trimmed.charAt(j)) ? j : j - 1) : i + 1;
                continue;
            }
            if (c == '(' || c == '[') {
                depth++;
            } else if ((c == ')' || c == ']') && depth > 0) {
                depth--;
            } else if (depth == 0 && (Character.isWhitespace(c) || c == '>' || c == '+' || c == '~')) {
                start = i + 1;
            }
        }
        String compound = trimmed.substring(start);
        if (compound.isEmpty()) {
            return null;
        }
        String tag = null;
        String id = null;
        Set<String> classes = new LinkedHashSet<>();
        Set<String> attributes = new LinkedHashSet<>();
        List<Structural> structural = new ArrayList<>();
        int i = 0;
        if (compound.charAt(0) == '*') {
            i = 1;
        } else if (isNameStart(compound.charAt(0))) {
            int[] end = new int[1];
            tag = readName(compound, 0, end);
            i = end[0];
        }
        while (i < compound.length()) {
            char c = compound.charAt(i);
            if (c == '.' || c == '#') {
                int[] end = new int[1];
                String name = readName(compound, i + 1, end);
                if (name.isEmpty()) {
                    return null;
                }
                if (c == '.') {
                    classes.add(name);
                } else {
                    id = name;
                }
                i = end[0];
            } else if (c == '[') {
                // Matched by the attribute's presence; the value test is ignored,
                // which only widens the match.
                int close = skipBalanced(compound, i, '[', ']');
                String attribute = attributeName(compound.substring(i + 1, Math.max(i + 1, close - 1)));
                if (attribute != null) {
                    attributes.add(attribute);
                }
                i = close;
            } else if (c == ':') {
                if (i + 1 < compound.length() && compound.charAt(i + 1) == ':') {
                    return null;
                }
                int[] end = new int[1];
                String pseudo = readName(compound, i + 1, end);
                if (STATE_PSEUDO_CLASSES.contains(pseudo)) {
                    return null;
                }
                i = end[0];
                String argument = null;
                if (i < compound.length() && compound.charAt(i) == '(') {
                    int close = skipBalanced(compound, i, '(', ')');
                    argument = compound.substring(i + 1, Math.max(i + 1, close - 1));
                    i = close;
                }
                // Anything else is ignored: that widens the match, never narrows it.
                structural.addAll(structural(pseudo, argument));
            } else {
                // Something this reader does not know: match more, not less.
                i++;
            }
        }
        return new Compound(tag, id, Set.copyOf(classes), Set.copyOf(attributes), List.copyOf(structural));
    }

    /** The attribute name of an attribute filter's contents, lower-cased. */
    private static String attributeName(String filter) {
        int start = 0;
        while (start < filter.length() && Character.isWhitespace(filter.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < filter.length() && "=~|^$*]".indexOf(filter.charAt(end)) < 0 && !Character.isWhitespace(filter.charAt(end))) {
            end++;
        }
        return end > start ? filter.substring(start, end).toLowerCase(Locale.ROOT) : null;
    }

    /**
     * The structural pseudo-classes evaluated exactly; an unknown one, or an
     * {@code an+b} that does not parse (such as {@code 2 of .x}), yields nothing
     * and is ignored.
     */
    private static List<Structural> structural(String pseudo, String argument) {
        return switch (pseudo) {
            case "empty" -> List.of(new Structural(Structural.Kind.EMPTY, 0, 0));
            case "root" -> List.of(new Structural(Structural.Kind.ROOT, 0, 0));
            case "first-child" -> List.of(new Structural(Structural.Kind.NTH, 0, 1));
            case "last-child" -> List.of(new Structural(Structural.Kind.NTH_LAST, 0, 1));
            case "only-child" -> List.of(new Structural(Structural.Kind.NTH, 0, 1), new Structural(Structural.Kind.NTH_LAST, 0, 1));
            case "nth-child" -> nth(Structural.Kind.NTH, argument);
            case "nth-last-child" -> nth(Structural.Kind.NTH_LAST, argument);
            default -> List.of();
        };
    }

    /**
     * {@code odd}, {@code even}, {@code b}, {@code an}, {@code an+b}, {@code -n+b}.
     */
    static List<Structural> nth(Structural.Kind kind, String argument) {
        if (argument == null) {
            return List.of();
        }
        String expression = argument.replace(" ", "").toLowerCase(Locale.ROOT);
        if ("odd".equals(expression)) {
            return List.of(new Structural(kind, 2, 1));
        }
        if ("even".equals(expression)) {
            return List.of(new Structural(kind, 2, 0));
        }
        if (expression.isEmpty() || expression.length() > 20) {
            return List.of();
        }
        try {
            int n = expression.indexOf('n');
            if (n < 0) {
                return List.of(new Structural(kind, 0, Integer.parseInt(expression)));
            }
            String coefficient = expression.substring(0, n);
            int a = switch (coefficient) {
                case "", "+" -> 1;
                case "-" -> -1;
                default -> Integer.parseInt(coefficient);
            };
            String rest = expression.substring(n + 1);
            int b = rest.isEmpty() ? 0 : Integer.parseInt(rest.startsWith("+") ? rest.substring(1) : rest);
            return List.of(new Structural(kind, a, b));
        } catch (NumberFormatException e) {
            return List.of();
        }
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '-' || c == '\\' || c > 0x7F;
    }

    /**
     * A CSS identifier from {@code from}, lower-cased, with escapes decoded
     * ({@code \61} is {@code a}) so an escaped class name cannot slip past.
     */
    private static String readName(String text, int from, int[] end) {
        StringBuilder name = new StringBuilder();
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                int j = i + 1;
                int hexEnd = j;
                while (hexEnd < text.length() && hexEnd - j < 6 && Character.digit(text.charAt(hexEnd), 16) >= 0) {
                    hexEnd++;
                }
                if (hexEnd > j) {
                    int codePoint = Integer.parseInt(text.substring(j, hexEnd), 16);
                    if (Character.isValidCodePoint(codePoint) && codePoint != 0) {
                        name.appendCodePoint(codePoint);
                    }
                    i = hexEnd < text.length() && Character.isWhitespace(text.charAt(hexEnd)) ? hexEnd + 1 : hexEnd;
                } else {
                    name.append(text.charAt(j));
                    i = j + 1;
                }
            } else if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c > 0x7F) {
                name.append(c);
                i++;
            } else {
                break;
            }
        }
        end[0] = i;
        return name.toString().toLowerCase(Locale.ROOT);
    }

    private static int skipBalanced(String text, int open, char opening, char closing) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == opening) {
                depth++;
            } else if (c == closing && --depth == 0) {
                return i + 1;
            }
        }
        return text.length();
    }
}
