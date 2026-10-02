/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import java.util.*;

/**
 * @author ginccc
 */
public final class StringUtilities {
    private static final String REGEX_SPECIAL_CHARS = ".*+?()[]{}|^$\\";

    private StringUtilities() {
        // utility class
    }

    /**
     * The literal text a search box asks for: the input itself, or what is between
     * the quotes of a quoted one. Both find the text anywhere in a field; the
     * quotes predate substring search and are accepted for compatibility. A lone
     * {@code "} or an empty {@code ""} is an empty search, which matches everything
     * — as it did when this was a regex.
     */
    public static String searchText(String filter) {
        if (filter.startsWith("\"") && filter.endsWith("\"")) {
            return filter.length() > 2 ? filter.substring(1, filter.length() - 1) : "";
        }
        return filter;
    }

    /**
     * Escapes regex meta-characters by prefixing each with a backslash. Compatible
     * with Java, MongoDB, and PostgreSQL POSIX regex engines (unlike
     * {@code Pattern.quote} which uses {@code \Q...\E}).
     */
    public static String escapeRegexChars(String input) {
        StringBuilder sb = new StringBuilder(input.length() * 2);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (REGEX_SPECIAL_CHARS.indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static String joinStrings(String delimiter, Collection<?> values) {
        return joinStrings(delimiter, values.toArray());
    }

    public static String joinStrings(String delimiter, Object... values) {
        StringJoiner stringJoiner = new StringJoiner(delimiter);
        Arrays.stream(values).filter(Objects::nonNull).map(Object::toString).forEach(stringJoiner::add);
        return stringJoiner.toString();
    }

    public static List<String> parseCommaSeparatedString(String commaSeparatedString) {
        List<String> ret = new LinkedList<>();
        StringTokenizer stringTokenizer = new StringTokenizer(commaSeparatedString, ",", false);
        while (stringTokenizer.hasMoreTokens()) {
            ret.add(stringTokenizer.nextToken().trim());
        }

        return ret;
    }
}
