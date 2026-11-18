/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

/**
 * Makes a string storable in PostgreSQL.
 * <p>
 * PostgreSQL cannot hold the character U+0000 in a {@code TEXT}/{@code VARCHAR}
 * column, and its {@code jsonb} type additionally rejects the JSON escape
 * escape for U+0000 (backslash, u, four zeros) and any unpaired surrogate
 * escape (a high one with no low one after it). MongoDB stores all of them. An
 * unfiltered value therefore failed the whole statement on PostgreSQL: a user
 * message containing one NUL lost its turn, and a log batch or an audit batch
 * containing one such row was lost with it.
 * <p>
 * The rule applied everywhere is: <b>U+0000, and every unpaired UTF-16
 * surrogate, becomes U+FFFD (the Unicode replacement character)</b>. It is
 * replaced rather than removed so that the length and the positions of the rest
 * of the text stay intact and {@code "ab"} and {@code "a<NUL>b"} stay distinct.
 * It is not injective: {@code "a<NUL>"}, a literal {@code "a\uFFFD"} and {@code
 * "a"} followed by an unpaired surrogate all end up as the same stored value,
 * so two ids that differ only in such characters address one row. The
 * replacement is applied to the value that is stored and to the value that is
 * looked up, so a key containing the character still finds its own row.
 * Everything else round-trips unchanged.
 * <p>
 * Two entry points exist because a JSON document carries the problem in two
 * spellings. {@link #text(String)} handles plain text, where only the raw
 * character matters (the six characters backslash, u, 0, 0, 0, 0 typed into a
 * message are ordinary text and stay as they are). {@link #json(String)}
 * additionally handles the escaped spelling inside JSON text, honouring the
 * escape rules: in the JSON text for a value that is a backslash followed by
 * u0000, the backslash is itself escaped, the value is six ordinary characters,
 * and nothing is rewritten.
 * <p>
 * Applied at the storage boundary by {@link PostgresSanitizingDataSource}, so
 * every binder of every PostgreSQL store is covered without each call site
 * having to remember it.
 */
public final class PostgresText {

    /** The character a rejected one is replaced with. */
    public static final char REPLACEMENT = '�';

    private PostgresText() {
    }

    /**
     * Plain text: replaces a raw U+0000 and unpaired surrogates. Returns the same
     * instance when nothing needs replacing.
     */
    public static String text(String value) {
        if (value == null) {
            return null;
        }
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c == 0) {
                return replaceText(value, i);
            }
            if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++; // a valid pair stays
            } else if (Character.isSurrogate(c)) {
                return replaceText(value, i);
            }
        }
        return value;
    }

    private static String replaceText(String value, int firstSuspect) {
        int length = value.length();
        StringBuilder out = new StringBuilder(length);
        out.append(value, 0, firstSuspect);
        for (int i = firstSuspect; i < length; i++) {
            char c = value.charAt(i);
            if (c == 0) {
                out.append(REPLACEMENT);
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                    out.append(c).append(value.charAt(++i));
                } else {
                    out.append(REPLACEMENT);
                }
            } else if (Character.isLowSurrogate(c)) {
                out.append(REPLACEMENT);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * JSON text bound to a {@code json}/{@code jsonb} parameter: everything
     * {@link #text(String)} does for raw characters, plus the escaped spellings the
     * escaped U+0000 and unpaired surrogate escapes. Returns the same instance when
     * nothing needs replacing.
     */
    public static String json(String value) {
        if (value == null) {
            return null;
        }
        int problem = firstJsonProblem(value);
        return problem < 0 ? value : rewriteJson(value, problem);
    }

    /**
     * Index of the first escape or character PostgreSQL would refuse, or -1. Reads
     * only: typical JSON (quotes, newlines, emoji) is returned as the same instance
     * without a copy.
     */
    private static int firstJsonProblem(String value) {
        int length = value.length();
        int i = 0;
        while (i < length) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < length) {
                char next = value.charAt(i + 1);
                if (next == 'u' && hasHexQuad(value, i + 2)) {
                    int unit = Integer.parseInt(value, i + 2, i + 6, 16);
                    if (unit == 0) {
                        return i;
                    } else if (unit >= 0xD800 && unit <= 0xDBFF) {
                        if (!isLowSurrogateEscape(value, i + 6)) {
                            return i;
                        }
                        i += 12;
                    } else if (unit >= 0xDC00 && unit <= 0xDFFF) {
                        return i;
                    } else {
                        i += 6;
                    }
                } else {
                    i += 2;
                }
            } else if (c == 0) {
                return i;
            } else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                i += 2;
            } else if (Character.isSurrogate(c)) {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    private static String rewriteJson(String value, int from) {
        int length = value.length();
        StringBuilder out = new StringBuilder(length);
        out.append(value, 0, from);
        int i = from;
        while (i < length) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < length) {
                char next = value.charAt(i + 1);
                if (next == 'u' && hasHexQuad(value, i + 2)) {
                    int unit = Integer.parseInt(value, i + 2, i + 6, 16);
                    if (unit == 0) {
                        out.append("\\uFFFD");
                        i += 6;
                    } else if (unit >= 0xD800 && unit <= 0xDBFF) {
                        if (isLowSurrogateEscape(value, i + 6)) {
                            out.append(value, i, i + 12);
                            i += 12;
                        } else {
                            out.append("\\uFFFD");
                            i += 6;
                        }
                    } else if (unit >= 0xDC00 && unit <= 0xDFFF) {
                        out.append("\\uFFFD");
                        i += 6;
                    } else {
                        out.append(value, i, i + 6);
                        i += 6;
                    }
                } else {
                    // any other escape, including an escaped backslash: both characters belong
                    // together, so a following "u0000" is plain text, not an escape
                    out.append(c).append(next);
                    i += 2;
                }
            } else if (c == 0) {
                out.append(REPLACEMENT);
                i++;
            } else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                out.append(c).append(value.charAt(i + 1));
                i += 2;
            } else if (Character.isSurrogate(c)) {
                out.append(REPLACEMENT);
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean hasHexQuad(String value, int from) {
        if (from + 4 > value.length()) {
            return false;
        }
        for (int i = from; i < from + 4; i++) {
            char c = value.charAt(i);
            // ASCII only, as JSON defines the escape: Character.digit would also accept
            // full-width and other Unicode digits
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLowSurrogateEscape(String value, int at) {
        if (at + 6 > value.length() || value.charAt(at) != '\\' || value.charAt(at + 1) != 'u' || !hasHexQuad(value, at + 2)) {
            return false;
        }
        int unit = Integer.parseInt(value, at + 2, at + 6, 16);
        return unit >= 0xDC00 && unit <= 0xDFFF;
    }
}
