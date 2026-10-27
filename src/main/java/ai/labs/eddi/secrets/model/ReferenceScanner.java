/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Finds {@code ${<prefix>:name}} and {@code ${<prefix>:tenant/name}} references
 * in one forward pass. It replaces {@code find()} over the reference regexes
 * ({@link SecretReference#VAULT_PATTERN},
 * {@code ConnectionReference.CONNECTION_PATTERN} and their kin).
 * <p>
 * <b>Why not the regex.</b> {@code find()} retries at every opener. When no
 * closing brace follows, each attempt scans the whole remaining text before it
 * fails, so a value carrying many unterminated vault openers costs time
 * quadratic in its length. The answers below are the regex's answers, match for
 * match (same start, same end, same groups), but the searches for the next
 * closing brace and the next slash are remembered and never re-run over text
 * they have already crossed. Every character is examined a constant number of
 * times.
 * <p>
 * <b>The accepted forms, unchanged from the regex.</b> The body is everything
 * between the opener and the FIRST closing brace after it, and must not be
 * empty. A nested opener inside the body is part of the body: an opener, then
 * {@code x}, then a second opener and {@code y} and one closing brace is ONE
 * reference whose name starts with {@code x$}. That is what the regex has
 * always answered, and an answer that changed here would change which secret a
 * stored configuration names. The tenant is the text before the body's first
 * slash, when that text and the text after the slash are both non-empty;
 * otherwise there is no tenant and the whole body is the name.
 */
public final class ReferenceScanner {

    private final String[] openers;
    private final boolean tenantRequired;

    private ReferenceScanner(boolean tenantRequired, String... prefixes) {
        if (prefixes.length == 0) {
            throw new IllegalArgumentException("A scanner needs at least one prefix");
        }
        this.openers = new String[prefixes.length];
        for (int i = 0; i < prefixes.length; i++) {
            this.openers[i] = "${" + prefixes[i] + ":";
        }
        this.tenantRequired = tenantRequired;
    }

    /** References whose tenant is optional, as the vault and connection regexes. */
    public static ReferenceScanner of(String... prefixes) {
        return new ReferenceScanner(false, prefixes);
    }

    /**
     * Only references that name a tenant: the body must hold a non-empty tenant, a
     * slash and a name.
     */
    public static ReferenceScanner withTenant(String... prefixes) {
        return new ReferenceScanner(true, prefixes);
    }

    /**
     * One reference found in a text.
     *
     * @param start
     *            index of its dollar sign
     * @param end
     *            index just past its closing brace
     * @param text
     *            the whole reference, opener and closing brace included
     * @param tenant
     *            the tenant it names, or {@code null} when it names none
     * @param name
     *            the name after the tenant, or the whole body without one
     */
    public record Match(int start, int end, String text, String tenant, String name) {
    }

    /** Every reference in {@code value}, in order; empty for {@code null}. */
    public List<Match> findAll(String value) {
        List<Match> matches = new ArrayList<>();
        if (value == null) {
            return matches;
        }
        Cursor cursor = new Cursor(value);
        Match match;
        while ((match = cursor.next()) != null) {
            matches.add(match);
        }
        return matches;
    }

    /** The first reference in {@code value}, or {@code null} when there is none. */
    public Match findFirst(String value) {
        return value == null ? null : new Cursor(value).next();
    }

    /** Whether {@code value} carries a reference anywhere. */
    public boolean containsReference(String value) {
        return findFirst(value) != null;
    }

    /** Whether {@code value} is one reference and nothing else. */
    public boolean matchesWhole(String value) {
        Match first = findFirst(value);
        return first != null && first.start() == 0 && first.end() == value.length();
    }

    /**
     * {@code value} with every reference replaced by what {@code replacement}
     * returns for it; text between references is copied as it is.
     */
    public String replaceAll(String value, Function<Match, String> replacement) {
        if (value == null) {
            return null;
        }
        StringBuilder result = null;
        int copied = 0;
        Cursor cursor = new Cursor(value);
        Match match;
        while ((match = cursor.next()) != null) {
            if (result == null) {
                result = new StringBuilder(value.length());
            }
            result.append(value, copied, match.start()).append(replacement.apply(match));
            copied = match.end();
        }
        if (result == null) {
            return value;
        }
        return result.append(value, copied, value.length()).toString();
    }

    /**
     * One pass over one text. The two remembered positions are what make it linear:
     * a later opener lies inside or past the region an earlier search crossed, so
     * it reuses that search's answer instead of repeating it.
     */
    private final class Cursor {
        private static final int UNKNOWN = -2;
        private static final int NONE = -1;

        private final String value;
        private int from;
        private int nextClose = UNKNOWN;
        private int nextSlash = UNKNOWN;

        Cursor(String value) {
            this.value = value;
        }

        Match next() {
            while (from < value.length()) {
                int dollar = value.indexOf("${", from);
                if (dollar < 0) {
                    from = value.length();
                    return null;
                }
                int bodyStart = bodyStart(dollar);
                if (bodyStart < 0) {
                    from = dollar + 1;
                    continue;
                }
                int close = closeAtOrAfter(bodyStart);
                if (close == NONE) {
                    // No closing brace anywhere ahead: no later opener can be closed either.
                    from = value.length();
                    return null;
                }
                Match match = matchAt(dollar, bodyStart, close);
                if (match == null) {
                    // The regex would retry one character on; an opener inside this body
                    // can still match (only possible when a tenant is required).
                    from = dollar + 1;
                    continue;
                }
                from = close + 1;
                return match;
            }
            return null;
        }

        private int bodyStart(int dollar) {
            for (String opener : openers) {
                if (value.startsWith(opener, dollar)) {
                    return dollar + opener.length();
                }
            }
            return -1;
        }

        private Match matchAt(int dollar, int bodyStart, int close) {
            if (close == bodyStart) {
                return null;
            }
            int slash = slashAtOrAfter(bodyStart);
            boolean tenanted = slash != NONE && slash > bodyStart && slash < close - 1;
            if (tenanted) {
                return new Match(dollar, close + 1, value.substring(dollar, close + 1), value.substring(bodyStart, slash),
                        value.substring(slash + 1, close));
            }
            if (tenantRequired) {
                return null;
            }
            return new Match(dollar, close + 1, value.substring(dollar, close + 1), null, value.substring(bodyStart, close));
        }

        private int closeAtOrAfter(int position) {
            if (nextClose == UNKNOWN || (nextClose != NONE && nextClose < position)) {
                nextClose = value.indexOf('}', position);
            }
            return nextClose;
        }

        private int slashAtOrAfter(int position) {
            if (nextSlash == UNKNOWN || (nextSlash != NONE && nextSlash < position)) {
                nextSlash = value.indexOf('/', position);
            }
            return nextSlash;
        }
    }
}
