package ai.labs.utilities;

import java.util.Arrays;
import java.util.Collection;
import java.util.StringJoiner;

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
        Arrays.stream(values).map(Object::toString).forEach(stringJoiner::add);
        return stringJoiner.toString();
    }
}
