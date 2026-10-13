/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import org.jboss.logging.Logger;

import java.util.List;

/**
 * Logs a multi-line startup banner one record per line.
 * <p>
 * A banner passed as a single message never reached an operator as a box: the
 * console filter escapes line breaks inside a record (CWE-117 — a newline in a
 * message could forge a second log record), so the whole box arrived as one
 * line of {@code \n+-----…\n|  SECRETS VAULT…} hundreds of characters wide, in
 * every container log and log aggregator. One record per line keeps the box
 * readable and keeps the escaping guarantee intact.
 */
public final class LogBanner {

    private LogBanner() {
    }

    /** Logs {@code banner} at WARN, one record per non-blank line. */
    public static void warn(Logger logger, String banner) {
        for (String line : lines(banner)) {
            logger.warn(line);
        }
    }

    /** The banner's non-blank lines, trailing whitespace removed. */
    static List<String> lines(String banner) {
        if (banner == null) {
            return List.of();
        }
        return banner.lines().map(String::stripTrailing).filter(line -> !line.isBlank()).toList();
    }
}
