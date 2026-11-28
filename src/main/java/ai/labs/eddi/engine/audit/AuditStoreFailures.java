/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mongodb.MongoWriteException;
import org.bson.BSONException;

import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Set;

/**
 * Tells a store that refused <em>one entry</em> from a store that is not
 * answering.
 * <p>
 * The ledger used to tell them apart by counting: three refusals in a row meant
 * "unavailable", and every remaining entry of the batch was then handed to the
 * retry-and-dead-letter path with the poison one. A single entry the database
 * cannot take (a character PostgreSQL cannot hold, a document MongoDB refuses)
 * therefore cost the entries of unrelated conversations that happened to share
 * its batch. The distinction is made on what the store said instead: a
 * {@link SQLException} whose SQLState is in class 22 (data exception) or 23
 * (integrity constraint), a JSON or BSON serialisation failure, or a MongoDB
 * write error for an invalid document all mean "reached the store, and it
 * refused this content". Everything else - connection failures (class 08),
 * timeouts, resource exhaustion, a failover, an exception nobody classified -
 * is treated as an outage and takes the back-off path.
 */
final class AuditStoreFailures {

    /**
     * MongoDB write error codes that reject a document, not the server: BadValue
     * (2), TypeMismatch (14), DocumentValidationFailure (121), BSONObjectTooLarge
     * (10334).
     */
    private static final Set<Integer> MONGO_REJECTED_DOCUMENT = Set.of(2, 14, 121, 10334);

    private static final int MAX_CAUSE_DEPTH = 10;

    private AuditStoreFailures() {
    }

    /** SQLState of a unique-constraint violation. */
    private static final String UNIQUE_VIOLATION = "23505";

    /** MongoDB's duplicate-key error code. */
    private static final int MONGO_DUPLICATE_KEY = 11000;

    /**
     * Whether the failure says the entry is already in the store: a unique-key
     * violation. Both stores make a single insert idempotent on the entry id (the
     * only unique key of the ledger), so this is not expected; should a store still
     * report it, for instance on a replay of an entry the failed batch had already
     * written, the entry is there and must count as stored, not be dead-lettered as
     * if it were lost. Checked before {@link #isEntryRejected}, which would
     * otherwise classify it with the other constraint violations.
     */
    static boolean isAlreadyStored(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++, current = current.getCause()) {
            if (current instanceof SQLException sql) {
                return UNIQUE_VIOLATION.equals(sql.getSQLState());
            }
            if (current instanceof MongoWriteException write) {
                return write.getError().getCode() == MONGO_DUPLICATE_KEY;
            }
        }
        return false;
    }

    /**
     * Whether the failure says the store reached the entry and refused its content.
     * The first classifiable exception in the cause chain decides.
     */
    static boolean isEntryRejected(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++, current = current.getCause()) {
            if (current instanceof SQLException sql) {
                return rejectsContent(sql);
            }
            if (current instanceof JsonProcessingException || current instanceof BSONException) {
                return true;
            }
            if (current instanceof MongoWriteException write) {
                return MONGO_REJECTED_DOCUMENT.contains(write.getError().getCode());
            }
        }
        return false;
    }

    private static boolean rejectsContent(SQLException sql) {
        if (sql instanceof SQLDataException || sql instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        String state = sql.getSQLState();
        return state != null && (state.startsWith("22") || state.startsWith("23"));
    }

    /**
     * A reason for the dead-letter record that names the kind of refusal and never
     * the driver's message (which quotes the offending value).
     */
    static String describe(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++, current = current.getCause()) {
            if (current instanceof SQLException sql) {
                return "store rejected the entry: " + sql.getClass().getSimpleName()
                        + (sql.getSQLState() == null ? "" : " SQLState " + sql.getSQLState());
            }
            if (current instanceof MongoWriteException write) {
                return "store rejected the entry: MongoWriteException code " + write.getError().getCode();
            }
            if (current instanceof JsonProcessingException || current instanceof BSONException) {
                return "store rejected the entry: " + current.getClass().getSimpleName();
            }
        }
        return "store rejected the entry: " + (failure == null ? "unknown" : failure.getClass().getSimpleName());
    }
}
