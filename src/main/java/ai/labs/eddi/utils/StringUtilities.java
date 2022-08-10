package ai.labs.eddi.utils;

import java.util.*;

/**
 * @author ginccc
 */
public final class StringUtilities {
    private static final String REGEX_WILDCARD = ".*";

    private StringUtilities() {
        // utility class
    }

    public static String convertToSearchString(String filter) {
        if (filter.startsWith("\"") && filter.endsWith("\"")) {
            if (filter.length() > 1) {
                filter = filter.substring(1, filter.length() - 1);
            } else {
                filter = "";
            }
        } else {
            filter = REGEX_WILDCARD + filter + REGEX_WILDCARD;
        }

        return filter;
    }

    public static String joinStrings(String delimiter, Collection values) {
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
