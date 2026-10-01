/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import java.util.Locale;

/**
 * Which resources a listing returns by owner: everything reachable, only what
 * the caller owns, or only what somebody else owns and has let them reach.
 * <p>
 * The "Shared with me" filter is the reason this exists. Without it a recipient
 * had no way to find what colleagues had shared among their own work except by
 * reading the owner column row by row — and a client cannot filter a page after
 * the fact without breaking paging.
 */
public enum Ownership {

    /** No narrowing. */
    ANY,

    /** Only resources the caller owns. */
    MINE,

    /** Only resources the caller can reach but does not own. */
    SHARED;

    /**
     * Parses the query-parameter spelling, case-insensitively.
     *
     * @return the value, {@link #ANY} for a blank input, or {@code null} for
     *         anything unrecognised — the caller refuses it, since silently listing
     *         everything for a misspelt "shraed" would look like a working filter
     */
    public static Ownership parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return ANY;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "any", "all" -> ANY;
            case "mine" -> MINE;
            case "shared" -> SHARED;
            default -> null;
        };
    }
}
