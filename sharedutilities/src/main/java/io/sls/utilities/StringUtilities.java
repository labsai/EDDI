package io.sls.utilities;

import java.util.LinkedList;
import java.util.List;
import java.util.StringTokenizer;

/**
 * Copyright by Spoken Language System. All rights reserved.
 * User: jarisch
 * Date: 19.11.12
 * Time: 18:45
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

    public static String[] extractPaths(String paths) {
        List<String> extractedPaths = new LinkedList<String>();
        StringTokenizer token = new StringTokenizer(paths, ";");

        while (token.hasMoreElements()) {
            String path = token.nextToken();
            if (!path.isEmpty()) {
                extractedPaths.add(path);
            }
        }

        return extractedPaths.toArray(new String[extractedPaths.size()]);
    }

    public static String convertSkipPermissionURIs(String skipPermissionCheckOnURIs) {
        return skipPermissionCheckOnURIs.replaceAll("\\*", ".*");
    }
}
