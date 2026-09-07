/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

/**
 * The one place that builds and escapes the identifiers the workspace model
 * stores: subjects, space ids, and the tokens that make up a descriptor's
 * access index.
 *
 * <h3>Two encodings, both mandatory</h3>
 * <ol>
 * <li><b>Token encoding.</b> The access index is a single string of
 * pipe-delimited tokens, so a principal containing a pipe would forge extra
 * tokens. {@link #encode} percent-escapes {@code %} and the pipe; nothing else
 * needs escaping because tokens are split on the pipe alone.</li>
 * <li><b>Regex escaping.</b> Both storage backends treat a {@code String} query
 * filter as a <em>regular expression</em> —
 * {@code MongoResourceStorage.findResources} builds {@code Filters.regex} and
 * {@code PostgresResourceStorage.findResources} emits {@code ~}. An unescaped
 * predicate for {@code alice} therefore also matches {@code malice}, and an
 * unescaped {@code .} in an email address matches any character.
 * {@link #tokenPattern} produces an anchored, escaped pattern instead.</li>
 * </ol>
 * Only the metacharacters common to PCRE (MongoDB) and POSIX ERE (PostgreSQL)
 * are escaped, and only ever by prefixing a backslash to a character that is a
 * metacharacter in both — escaping an ordinary character is undefined in POSIX
 * ERE and must be avoided.
 *
 * @author ginccc
 */
public final class Subjects {

    /** Prefix for an individual principal. */
    public static final String USER_PREFIX = "user:";

    /** Prefix for a Keycloak group / team. */
    public static final String TEAM_PREFIX = "team:";

    /** Access-index token marking a resource readable by everyone. */
    public static final String TOKEN_ALL = "all";

    /**
     * Access-index token that admits nobody.
     * <p>
     * An index must never be empty — an empty string matches no predicate, but so
     * does {@code |}, and reasoning about which is which invites the wrong fix
     * later. This is the explicit "reachable by no one" marker, and
     * {@link DescriptorAccess#admittingTokens} never emits it, so a resource
     * carrying it is listed by nobody. It is the correct index for a descriptor
     * whose structured fields also grant nobody anything.
     */
    public static final String TOKEN_NONE = "none";

    /**
     * Space id and access-index token for resources created before ownership was
     * recorded. Whether it is actually admitted to a listing is the operator's
     * choice — see {@code eddi.workspaces.legacy-visibility}.
     */
    public static final String LEGACY = "legacy";

    /** Token prefix marking the resource's owner. */
    public static final String OWNER_TOKEN_PREFIX = "owner:";

    /** Token prefix marking the resource's own space. */
    public static final String SPACE_TOKEN_PREFIX = "space:";

    /** Delimiter surrounding every token in the access index. */
    public static final char DELIMITER = '|';

    private static final String ENCODED_DELIMITER = "%7C";
    private static final String ENCODED_PERCENT = "%25";

    private Subjects() {
        // utility class
    }

    /**
     * The subject for an individual principal, or {@code null} for a blank input.
     */
    public static String user(String principal) {
        return principal == null || principal.isBlank() ? null : USER_PREFIX + encode(principal.trim());
    }

    /**
     * The subject for a team / Keycloak group, or {@code null} for a blank input.
     */
    public static String team(String groupPath) {
        if (groupPath == null || groupPath.isBlank()) {
            return null;
        }
        String normalized = normalizeGroup(groupPath);
        return normalized.isEmpty() ? null : TEAM_PREFIX + encode(normalized);
    }

    /**
     * A user's personal space. Deliberately identical to that user's subject: a
     * personal space has exactly one member, so giving it a second identifier would
     * only create two spellings of the same thing.
     */
    public static String personalSpace(String principal) {
        return user(principal);
    }

    /** A team's space. Identical to the team subject, for the same reason. */
    public static String teamSpace(String groupPath) {
        return team(groupPath);
    }

    /**
     * Keycloak reports group membership with a leading slash and nested paths
     * ({@code /engineering/backend}). Normalising the surrounding slashes away
     * keeps a config that names {@code engineering} equivalent to a token claim
     * that says {@code /engineering}; nested paths keep their internal separators
     * and stay distinct.
     */
    public static String normalizeGroup(String groupPath) {
        String trimmed = groupPath.trim();
        int start = 0;
        int end = trimmed.length();
        while (start < end && trimmed.charAt(start) == '/') {
            start++;
        }
        while (end > start && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        return trimmed.substring(start, end);
    }

    /**
     * Percent-escapes the two characters that would otherwise forge index tokens.
     */
    public static String encode(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '%' -> out.append(ENCODED_PERCENT);
                case DELIMITER -> out.append(ENCODED_DELIMITER);
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Inverse of {@link #encode}.
     * <p>
     * The pipe is decoded before the percent so that an input which literally
     * contained the text {@code %7C} — encoded as {@code %257C} — comes back as
     * {@code %7C} rather than being decoded twice into a pipe.
     */
    public static String decode(String encoded) {
        if (encoded == null || encoded.indexOf('%') < 0) {
            return encoded;
        }
        StringBuilder out = new StringBuilder(encoded.length());
        int i = 0;
        while (i < encoded.length()) {
            if (encoded.startsWith(ENCODED_DELIMITER, i) || encoded.regionMatches(true, i, ENCODED_DELIMITER, 0, 3)) {
                out.append(DELIMITER);
                i += 3;
            } else if (encoded.startsWith(ENCODED_PERCENT, i)) {
                out.append('%');
                i += 3;
            } else {
                out.append(encoded.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /**
     * An anchored, escaped pattern matching exactly {@code token} as a whole token
     * inside an access index.
     * <p>
     * The index always writes a delimiter on both sides of every token, so
     * requiring both delimiters is what makes {@code user:al} fail to match
     * {@code |user:alice|}. Without them this is a prefix match and the whole
     * escaping exercise is pointless.
     */
    public static String tokenPattern(String token) {
        return escapeRegex(DELIMITER + token + DELIMITER);
    }

    /**
     * An anchored, escaped pattern for an exact whole-field string match, for
     * fields that hold one value rather than a token list.
     */
    public static String exactPattern(String value) {
        return "^" + escapeRegex(value) + "$";
    }

    /**
     * Escapes the metacharacters that PCRE and POSIX ERE agree on. Characters
     * outside this set are left alone deliberately: POSIX ERE leaves
     * backslash-plus-ordinary-character undefined, so escaping defensively would be
     * less portable, not more.
     */
    public static String escapeRegex(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (isSharedMetacharacter(c)) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isSharedMetacharacter(char c) {
        return switch (c) {
            case '.', '^', '$', '*', '+', '?', '(', ')', '[', ']', '{', '}', '|', '\\' -> true;
            default -> false;
        };
    }
}
