/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Optional;

/**
 * Glob patterns for excluding URLs from a crawl.
 *
 * <p>
 * Matched against the URL's <b>path</b>, not the whole URL. The draft this
 * replaces matched the entire lowercased URL with {@code String.matches}, which
 * made the pattern its own documentation advertised — {@code *.pdf} — unable to
 * match anything at all, because {@code *} does not cross {@code /} and the
 * scheme and host always contain slashes. An exclusion that silently excludes
 * nothing is worse than none: the operator believes the PDFs are skipped.
 *
 * <p>
 * Patterns come from agent configuration, and every character other than the
 * three wildcards is literal. The draft built a regex with chained
 * {@code String.replace} calls that escaped only {@code .}, so an exclude
 * pattern containing {@code +} or {@code (} threw
 * {@code PatternSyntaxException} from inside the crawl loop, where a blanket
 * {@code catch} logged it as a *fetch* error for the current page and dropped
 * all of that page's links — one bad pattern reduced a crawl to its seed URL.
 *
 * <p>
 * Matching does not use a regex at all. A glob translated to one gets a
 * backtracking repetition per wildcard, and a pattern with several of them —
 * ordinary in exclusion lists — can take combinatorial time on a path from a
 * crawled page that almost matches. The path is matched instead by stepping the
 * set of reachable pattern positions forward one character at a time: work is
 * bounded by pattern length times path length, whatever the input.
 *
 * <h2>Syntax</h2>
 * <ul>
 * <li>{@code *} — any characters except {@code /} (one path segment)</li>
 * <li>{@code **} — any characters including {@code /}</li>
 * <li>{@code ?} — exactly one character other than {@code /}</li>
 * </ul>
 * A pattern that does not start with {@code /} or {@code **} is treated as
 * {@code **}-prefixed, so {@code *.pdf} means what an operator expects.
 */
public final class UrlPattern {

    /**
     * Ceiling on pattern length, which bounds the work per matched character.
     */
    private static final int MAX_PATTERN_LENGTH = 512;

    private static final byte LITERAL = 0;
    /** {@code *}: any run of characters other than {@code /}. */
    private static final byte SEGMENT = 1;
    /** {@code **}: any run of characters. */
    private static final byte ANYTHING = 2;
    /** {@code ?}: one character other than {@code /}. */
    private static final byte ONE = 3;

    private final String source;
    private final byte[] kinds;
    /** Code points, so that {@code ?} consumes a whole supplementary character. */
    private final int[] literals;

    private UrlPattern(String source, byte[] kinds, int[] literals) {
        this.source = source;
        this.kinds = kinds;
        this.literals = literals;
    }

    /**
     * Compiles a glob, or returns empty when it is unusable. Compilation happens
     * once per crawl rather than once per URL per pattern.
     */
    public static Optional<UrlPattern> compile(String glob) {
        if (glob == null || glob.isBlank() || glob.length() > MAX_PATTERN_LENGTH) {
            return Optional.empty();
        }
        String pattern = glob.trim();
        // "*.pdf" should match "/docs/report.pdf". Without this an operator has to
        // know to write "**/*.pdf", and gets silence when they do not.
        if (!pattern.startsWith("/") && !pattern.startsWith("**")) {
            pattern = "**/" + pattern;
        }
        byte[] kinds = new byte[pattern.length()];
        int[] literals = new int[pattern.length()];
        int count = 0;
        for (int index = 0; index < pattern.length(); index += Character.charCount(pattern.codePointAt(index))) {
            int character = pattern.codePointAt(index);
            if (character == '*') {
                boolean doubled = index + 1 < pattern.length() && pattern.charAt(index + 1) == '*';
                if (doubled) {
                    index++;
                }
                kinds[count] = doubled ? ANYTHING : SEGMENT;
            } else if (character == '?') {
                kinds[count] = ONE;
            } else {
                kinds[count] = LITERAL;
                literals[count] = character;
            }
            count++;
        }
        return Optional.of(new UrlPattern(glob, Arrays.copyOf(kinds, count), Arrays.copyOf(literals, count)));
    }

    /** Compiles many globs, skipping the unusable ones. */
    public static List<UrlPattern> compileAll(List<String> globs) {
        List<UrlPattern> patterns = new ArrayList<>();
        if (globs == null) {
            return patterns;
        }
        for (String glob : globs) {
            compile(glob).ifPresent(patterns::add);
        }
        return patterns;
    }

    /** Whether any pattern matches the path. */
    public static boolean anyMatches(List<UrlPattern> patterns, String path) {
        for (UrlPattern pattern : patterns) {
            if (pattern.matches(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the whole path matches, in time proportional to pattern length times
     * path length.
     */
    public boolean matches(String path) {
        if (path == null) {
            return false;
        }
        int length = kinds.length;
        // reachable[i]: the path read so far can end with the pattern at position i.
        boolean[] reachable = new boolean[length + 1];
        boolean[] next = new boolean[length + 1];
        reachable[0] = true;
        skipEmptyWildcards(reachable);
        // By code point, as the regex this replaced did: "?" is one character of the
        // path, and a supplementary character is one character, not two.
        for (int at = 0; at < path.length(); at += Character.charCount(path.codePointAt(at))) {
            int character = path.codePointAt(at);
            Arrays.fill(next, false);
            boolean any = false;
            for (int i = 0; i < length; i++) {
                if (reachable[i] && accepts(i, character)) {
                    // A wildcard run stays where it is; anything else moves on.
                    next[kinds[i] == SEGMENT || kinds[i] == ANYTHING ? i : i + 1] = true;
                    any = true;
                }
            }
            if (!any) {
                return false;
            }
            skipEmptyWildcards(next);
            boolean[] swap = reachable;
            reachable = next;
            next = swap;
        }
        return reachable[length];
    }

    private boolean accepts(int position, int character) {
        return switch (kinds[position]) {
            case LITERAL -> literals[position] == character;
            case ANYTHING -> true;
            default -> character != '/';
        };
    }

    /** A wildcard run may be empty, so reaching it also reaches what follows it. */
    private void skipEmptyWildcards(boolean[] reachable) {
        for (int i = 0; i < kinds.length; i++) {
            if (reachable[i] && (kinds[i] == SEGMENT || kinds[i] == ANYTHING)) {
                reachable[i + 1] = true;
            }
        }
    }

    public String source() {
        return source;
    }

    @Override
    public String toString() {
        return source;
    }

}
