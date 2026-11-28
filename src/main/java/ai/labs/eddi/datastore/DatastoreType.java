/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import java.util.Locale;

/**
 * The value of {@code eddi.datastore.type}, read the one way every reader in
 * EDDI reads it.
 * <p>
 * Each reader used to compare the raw string with
 * {@code "postgres".equals(...)}, so {@code Postgres}, {@code postgresql} or a
 * typo selected MongoDB, silently, against the built-in default connection
 * string. The value is now trimmed and matched case-insensitively against the
 * known names, and anything else is an error that stops the startup with a
 * message naming the accepted values:
 * <ul>
 * <li>{@code mongodb} - MongoDB (the default; also what an unset or blank value
 * means)</li>
 * <li>{@code postgres} - PostgreSQL; {@code postgresql} is accepted as an
 * alias, the name of the JDBC protocol and of the database</li>
 * </ul>
 * The Helm chart is stricter than this (it renders only the two lower-case
 * names); a guard may be stricter than its consumer, never looser.
 */
public enum DatastoreType {
    MONGODB, POSTGRES;

    /**
     * @param raw
     *            the configured value; {@code null} or blank means the default,
     *            {@link #MONGODB}
     * @throws IllegalArgumentException
     *             for any value that is not a known name
     */
    public static DatastoreType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return MONGODB;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "mongodb" -> MONGODB;
            case "postgres", "postgresql" -> POSTGRES;
            default -> throw new IllegalArgumentException("Unknown eddi.datastore.type '" + raw
                    + "'. Use 'mongodb' or 'postgres' ('postgresql' is accepted as "
                    + "an alias of 'postgres'); the value is trimmed and not case-sensitive. EDDI refuses to start rather than guess, because an "
                    + "unrecognised value used to select MongoDB against the default connection string.");
        };
    }

    /**
     * {@code true} for PostgreSQL; throws like {@link #parse} for an unknown value.
     */
    public static boolean isPostgres(String raw) {
        return parse(raw) == POSTGRES;
    }
}
