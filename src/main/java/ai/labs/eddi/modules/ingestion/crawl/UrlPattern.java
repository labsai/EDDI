/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * Patterns come from agent configuration, so every character other than the
 * three wildcards is a literal — matching no longer goes through a regex at all
 * (see {@link #matches} for why). The draft built its regex with chained
 * {@code String.replace} calls that escaped only {@code .}, so an exclude
 * pattern containing {@code +} or {@code (} threw
 * {@code PatternSyntaxException} from inside the crawl loop, where a blanket
 * {@code catch} logged it as a *fetch* error for the current page and dropped
 * all of that page's links — one bad pattern reduced a crawl to its seed URL.
 *
 * <h2>Syntax</h2>
 * <ul>
 * <li>{@code *} — any characters except {@code /} (one path segment)</li>
 * <li>{@code **} — any characters including {@code /} (and including a line
 * terminator a decoded path may carry — the regex this replaced did not match
 * one, so {@code /private/**} let {@code /private/a%0Ab} through)</li>
 * <li>{@code ?} — exactly one character other than {@code /}</li>
 * </ul>
 * A pattern that does not start with {@code /} or {@code **} is treated as
 * {@code **}-prefixed, so {@code *.pdf} means what an operator expects.
 */
public final class UrlPattern {

    /**
     * Ceiling on pattern length. Matching costs pattern length times path length
     * (see {@link #matches}), so this bounds the per-URL work together with the
     * path the crawler hands in.
     */
    private static final int MAX_PATTERN_LENGTH = 512;

    /** A literal character; any other value is one of the wildcard tokens. */
    private static final int ONE_SEGMENT_CHAR = -1;
    private static final int SEGMENT_RUN = -2;
    private static final int ANY_RUN = -3;

    private final String source;
    /**
     * The glob as tokens: a character code for a literal, or one of the negative
     * wildcard constants.
     */
    private final int[] tokens;

    private UrlPattern(String source, int[] tokens) {
        this.source = source;
        this.tokens = tokens;
    }

    /**
     * Compiles a glob, or returns empty when it is unusable. Compilation happens
     * once per crawl rather than once per URL per pattern.
     */
    public static Optional<UrlPattern> compile(String glob) {
        if (glob == null || glob.isBlank() || glob.length() > MAX_PATTERN_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(new UrlPattern(glob, tokenize(glob.trim())));
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
     * Whether the glob matches the whole path.
     * <p>
     * A dynamic-programming pass over the tokens, not a regex. This class used to
     * translate the glob into a regex of {@code [^/]*} and {@code .*} runs, and a
     * short pattern such as {@code *a*a*a*a*b} then backtracked through every way
     * of splitting a long path between its stars — work exponential in the number
     * of stars, on a path taken from a fetched page. Here {@code reachable[j]} says
     * whether the tokens consumed so far can match the first {@code j} characters
     * of the path, and each token updates the row once, so the cost is pattern
     * length times path length whatever the input.
     */
    public boolean matches(String path) {
        if (path == null) {
            return false;
        }
        int length = path.length();
        boolean[] reachable = new boolean[length + 1];
        boolean[] next = new boolean[length + 1];
        reachable[0] = true;
        for (int token : tokens) {
            boolean any = false;
            switch (token) {
                case SEGMENT_RUN, ANY_RUN -> {
                    next[0] = reachable[0];
                    for (int j = 1; j <= length; j++) {
                        boolean extend = next[j - 1] && (token == ANY_RUN || path.charAt(j - 1) != '/');
                        next[j] = reachable[j] || extend;
                    }
                }
                case ONE_SEGMENT_CHAR -> {
                    next[0] = false;
                    for (int j = 1; j <= length; j++) {
                        next[j] = reachable[j - 1] && path.charAt(j - 1) != '/';
                    }
                }
                default -> {
                    next[0] = false;
                    for (int j = 1; j <= length; j++) {
                        next[j] = reachable[j - 1] && path.charAt(j - 1) == token;
                    }
                }
            }
            for (int j = 0; j <= length; j++) {
                any |= next[j];
            }
            if (!any) {
                return false;
            }
            boolean[] swap = reachable;
            reachable = next;
            next = swap;
        }
        return reachable[length];
    }

    public String source() {
        return source;
    }

    @Override
    public String toString() {
        return source;
    }

    private static int[] tokenize(String glob) {
        String pattern = glob;
        // "*.pdf" should match "/docs/report.pdf". Without this an operator has to
        // know to write "**/*.pdf", and gets silence when they do not.
        if (!pattern.startsWith("/") && !pattern.startsWith("**")) {
            pattern = "**/" + pattern;
        }

        int[] tokens = new int[pattern.length()];
        int count = 0;
        int index = 0;
        while (index < pattern.length()) {
            char character = pattern.charAt(index);
            switch (character) {
                case '*' -> {
                    boolean doubled = index + 1 < pattern.length() && pattern.charAt(index + 1) == '*';
                    if (doubled) {
                        tokens[count++] = ANY_RUN;
                        index++;
                    } else {
                        tokens[count++] = SEGMENT_RUN;
                    }
                }
                case '?' -> tokens[count++] = ONE_SEGMENT_CHAR;
                // Every other character is a literal, regex metacharacters included —
                // there is no regex any more for them to break. See the class comment.
                default -> tokens[count++] = character;
            }
            index++;
        }
        return Arrays.copyOf(tokens, count);
    }
}
