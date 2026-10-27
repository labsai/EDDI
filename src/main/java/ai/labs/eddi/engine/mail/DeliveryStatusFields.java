/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the body of a {@code message/delivery-status} part (RFC 3464 §2.1):
 * groups of header-style fields separated by blank lines — the per-message
 * group first, then one group per recipient.
 */
final class DeliveryStatusFields {

    /** Groups read — a bounce lists recipients, not thousands of them. */
    private static final int MAX_GROUPS = 100;

    private DeliveryStatusFields() {
    }

    /** Field groups, each a list of {@code [lower-case name, value]}. */
    static List<List<String[]>> parse(String body) {
        List<List<String[]>> groups = new ArrayList<>();
        List<String[]> current = new ArrayList<>();
        for (String line : body.replace("\r\n", "\n").split("\n", -1)) {
            if (line.isBlank()) {
                if (!current.isEmpty()) {
                    groups.add(current);
                    if (groups.size() >= MAX_GROUPS) {
                        return groups;
                    }
                    current = new ArrayList<>();
                }
                continue;
            }
            if ((line.startsWith(" ") || line.startsWith("\t")) && !current.isEmpty()) {
                String[] last = current.get(current.size() - 1);
                last[1] = last[1] + " " + line.strip();
                continue;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                current.add(new String[]{line.substring(0, colon).strip().toLowerCase(Locale.ROOT), line.substring(colon + 1).strip()});
            }
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    /** The first value of a field in a group, or null. */
    static String value(List<String[]> group, String name) {
        for (String[] field : group) {
            if (field[0].equals(name)) {
                return field[1];
            }
        }
        return null;
    }

    /** {@code rfc822; alice@example.com} → {@code alice@example.com}. */
    static String afterType(String value) {
        int semicolon = value.indexOf(';');
        return (semicolon >= 0 ? value.substring(semicolon + 1) : value).strip();
    }
}
