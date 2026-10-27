/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails.impl;

import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compiles the regular expressions of guardrail parameters once. Keyed by the
 * pattern source, so it holds configuration-derived data only — never anything
 * of a conversation — and the guardrail beans that use it stay stateless.
 * Bounded: past {@link #MAX_ENTRIES} distinct patterns, new ones are compiled
 * per call instead of cached.
 */
final class PatternCache {

    private static final Logger LOGGER = Logger.getLogger(PatternCache.class);
    static final int MAX_ENTRIES = 2_000;

    private static final Map<String, Optional<Pattern>> CACHE = new ConcurrentHashMap<>();

    private PatternCache() {
    }

    /**
     * The compiled pattern (case-insensitive, Unicode-aware), or empty when the
     * source is not a valid regular expression — logged once, never thrown: an
     * author's typo must not fail the turn.
     */
    static Optional<Pattern> compile(String source) {
        Optional<Pattern> cached = CACHE.get(source);
        if (cached != null) {
            return cached;
        }
        Optional<Pattern> compiled;
        try {
            compiled = Optional.of(Pattern.compile(source, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        } catch (PatternSyntaxException e) {
            LOGGER.warnf("Guardrail pattern is not a valid regular expression and is ignored: %s", e.getDescription());
            compiled = Optional.empty();
        }
        if (CACHE.size() < MAX_ENTRIES) {
            CACHE.putIfAbsent(source, compiled);
        }
        return compiled;
    }

    /**
     * The first line of a multi-pattern parameter that is not a valid regular
     * expression, as a deploy-time problem message naming its line number (never
     * the pattern, which may be long); null when every line compiles.
     */
    static String firstInvalidLine(String parameter) {
        if (parameter == null || parameter.isBlank()) {
            return null;
        }
        String[] lines = parameter.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String source = lines[i].strip();
            if (!source.isEmpty() && compile(source).isEmpty()) {
                return "parameter 'patterns' line " + (i + 1) + " is not a valid regular expression";
            }
        }
        return null;
    }

    /**
     * Splits a multi-pattern parameter: one pattern per line (a regular expression
     * may itself contain commas, so commas are not separators). Blank lines are
     * skipped.
     */
    static List<Pattern> compileLines(String parameter) {
        List<Pattern> patterns = new ArrayList<>();
        if (parameter == null || parameter.isBlank()) {
            return patterns;
        }
        for (String line : parameter.split("\\R")) {
            String source = line.strip();
            if (!source.isEmpty()) {
                compile(source).ifPresent(patterns::add);
            }
        }
        return patterns;
    }
}
