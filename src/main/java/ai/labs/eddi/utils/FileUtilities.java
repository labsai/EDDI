/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class FileUtilities {

    private FileUtilities() {
        // utility class
    }

    /**
     * Read a whole text file as UTF-8.
     * <p>
     * This was a {@code BufferedReader} loop using {@code while (rd.ready())} as an
     * end-of-file test, which is not what {@code ready()} means: it answers "can I
     * read without blocking", so a file whose next block had not been buffered yet
     * ended the loop early and returned a silently truncated string. It also read
     * with the platform default charset, so the same file decoded differently on
     * different hosts.
     */
    public static String readTextFromFile(File file) throws IOException {
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    /**
     * Join path segments with the platform separator, appending a trailing
     * separator when the result names a directory (its last segment has no dot).
     * <p>
     * Null and empty segments are skipped rather than concatenated, and a single
     * segment is legal. Both used to throw {@link StringIndexOutOfBoundsException}
     * from this method's own internals: {@code buildPath("foo")} found no
     * separator, so {@code lastIndexOf} returned -1 and {@code substring(-1)} blew
     * up, and {@code buildPath("", "x")} did the same via {@code endsWith} on an
     * empty builder. Callers pass values derived from {@code user.dir} and from
     * request data, so neither shape is hypothetical.
     */
    public static String buildPath(String... directories) {
        if (directories == null) {
            return "";
        }
        StringBuilder ret = new StringBuilder();
        for (String directory : directories) {
            if (directory == null || directory.isEmpty()) {
                continue;
            }
            ret.append(directory);
            if (!endsWith(ret, File.separator)) {
                ret.append(File.separator);
            }
        }

        if (ret.isEmpty()) {
            return "";
        }

        if (endsWith(ret, File.separator)) {
            ret.deleteCharAt(ret.length() - 1);
        }

        // lastIndexOf returns -1 when there is no separator at all, and -1 + 1 == 0 is
        // exactly the right start index for "the whole string is the last segment".
        String lastSegment = ret.substring(ret.lastIndexOf(File.separator) + 1);
        if (!lastSegment.contains(".")) {
            ret.append(File.separatorChar);
        }

        return ret.toString();
    }

    private static boolean endsWith(StringBuilder sb, String lookup) {
        return sb.length() >= lookup.length() && sb.substring(sb.length() - lookup.length()).equals(lookup);
    }
}
