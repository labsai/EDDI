/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
 * Patterns come from agent configuration, so every metacharacter is escaped
 * before the glob wildcards are translated. The draft built its regex with
 * chained {@code String.replace} calls that escaped only {@code .}, so an
 * exclude pattern containing {@code +} or {@code (} threw
 * {@code PatternSyntaxException} from inside the crawl loop, where a blanket
 * {@code catch} logged it as a *fetch* error for the current page and dropped
 * all of that page's links — one bad pattern reduced a crawl to its seed URL.
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
     * Ceiling on pattern length. A glob compiles to a regex with nested {@code .*}
     * runs, which can backtrack catastrophically on a long path; capping the source
     * is simpler and more predictable than trying to detect that after the fact.
     */
    private static final int MAX_PATTERN_LENGTH = 512;

    private final String source;
    private final Pattern compiled;

    private UrlPattern(String source, Pattern compiled) {
        this.source = source;
        this.compiled = compiled;
    }

    /**
     * Compiles a glob, or returns empty when it is unusable. Compilation happens
     * once per crawl rather than once per URL per pattern.
     */
    public static Optional<UrlPattern> compile(String glob) {
        if (glob == null || glob.isBlank() || glob.length() > MAX_PATTERN_LENGTH) {
            return Optional.empty();
        }
        try {
            return Optional.of(new UrlPattern(glob, Pattern.compile(toRegex(glob.trim()))));
        } catch (PatternSyntaxException e) {
            // Unreachable in practice — everything is escaped — but a broken pattern
            // must never propagate into the crawl loop. See the class comment.
            return Optional.empty();
        }
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

    public boolean matches(String path) {
        if (path == null) {
            return false;
        }
        return compiled.matcher(path).matches();
    }

    public String source() {
        return source;
    }

    @Override
    public String toString() {
        return source;
    }

    private static String toRegex(String glob) {
        String pattern = glob;
        // "*.pdf" should match "/docs/report.pdf". Without this an operator has to
        // know to write "**/*.pdf", and gets silence when they do not.
        if (!pattern.startsWith("/") && !pattern.startsWith("**")) {
            pattern = "**/" + pattern;
        }

        StringBuilder regex = new StringBuilder();
        int index = 0;
        while (index < pattern.length()) {
            char character = pattern.charAt(index);
            switch (character) {
                case '*' -> {
                    boolean doubled = index + 1 < pattern.length() && pattern.charAt(index + 1) == '*';
                    if (doubled) {
                        regex.append(".*");
                        index++;
                    } else {
                        regex.append("[^/]*");
                    }
                }
                case '?' -> regex.append("[^/]");
                default -> regex.append(Pattern.quote(String.valueOf(character)));
            }
            index++;
        }
        return regex.toString();
    }
}
