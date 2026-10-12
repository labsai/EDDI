/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * The id contract of the PostgreSQL stores whose key column is a {@code UUID}:
 * an id that is not a UUID names nothing.
 * <p>
 * Ids arrive from outside — a URL path segment, an archive exported by a
 * MongoDB deployment (24-hex ObjectIds), a typo. Bound into {@code ?::uuid},
 * such an id made PostgreSQL raise {@code 22P02 invalid_text_representation},
 * which the stores wrapped in a {@link RuntimeException} and the REST layer
 * answered with a 500 — on seven endpoints where the MongoDB backend answers
 * 404. Both backends now follow one rule: <b>an id the backend cannot store is
 * an unknown id</b>. Every lookup answers "not found" for it, and every delete
 * of it is a no-op, so REST answers 404 exactly as for a well-formed id that
 * does not exist. The MongoDB counterpart is {@code ObjectId.isValid}.
 * <p>
 * The check runs before the statement, so no connection is spent and no
 * transaction is aborted on a value that can never match. A {@code 22P02} that
 * still occurs (PostgreSQL also accepts braces and hyphen-less forms, which are
 * rejected here; any other syntax it refuses) is recognised by its SQLSTATE,
 * not by the localised English message text, which differs with
 * {@code lc_messages}.
 */
public final class PostgresIds {

    /** SQLSTATE {@code invalid_text_representation}. */
    static final String INVALID_TEXT_REPRESENTATION = "22P02";

    private static final Pattern CANONICAL_UUID = Pattern
            .compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private PostgresIds() {
    }

    /**
     * Whether {@code id} can name a row keyed by a {@code UUID} column: the
     * canonical 8-4-4-4-12 hex form, in either case — the only form EDDI ever
     * generates.
     */
    public static boolean isStorableId(String id) {
        return id != null && id.length() == 36 && CANONICAL_UUID.matcher(id).matches();
    }

    /**
     * Whether {@code e} (or a chained cause) is PostgreSQL refusing a value's text
     * form — for an id column, "this id cannot exist".
     */
    static boolean isInvalidTextRepresentation(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && INVALID_TEXT_REPRESENTATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
