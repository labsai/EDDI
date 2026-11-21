/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.memory.ConcurrentConversationModificationException;
import ai.labs.eddi.engine.memory.ConversationFencedException;
import ai.labs.eddi.engine.memory.ConversationTooLargeException;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.TurnError;
import com.mongodb.MongoException;
import org.bson.BsonMaximumSizeExceededException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * A turn that ran but whose conversation write failed — and what its caller is
 * told about it.
 * <p>
 * A turn's answer is delivered only after its write (see
 * {@code ConversationService#completeTurn}). When the write fails, the caller
 * gets a {@link TurnError} with code {@value #ERROR_CODE} instead of the
 * answer, and the reason is one of a small, stable set ({@link Reason}). The
 * driver's own text never reaches the caller: a MongoDB or PostgreSQL message
 * can name collections, hosts, SQL or the rejected content.
 */
public final class TurnNotStored {

    /** The {@code error.code} of a turn that ran but was not stored. */
    public static final String ERROR_CODE = "TURN_NOT_STORED";

    /**
     * MongoDB: BSONObjectTooLarge, and "resulting document after update is larger
     * than 16 MB".
     */
    private static final Set<Integer> MONGO_DOCUMENT_TOO_LARGE_CODES = Set.of(10334, 17419);

    /** How deep the cause chain is searched. */
    private static final int MAX_CAUSE_DEPTH = 16;

    private TurnNotStored() {
    }

    /**
     * Why the write failed. {@link #tag()} is the stable client-facing reason (the
     * {@code errorType} of the error, the {@code reason} of the streamed error
     * event, and the {@code reason} tag of {@code eddi_turn_not_stored_total}).
     */
    public enum Reason {
        /**
         * The conversation document reached its size limit (the database's, or
         * {@code eddi.conversations.max-document-bytes}). Every further turn of this
         * conversation fails the same way: start a new conversation.
         */
        CONVERSATION_TOO_LARGE("conversation_too_large", false, null, 507,
                "The turn ran but was not stored: this conversation has reached its size limit. Start a new conversation."),
        /** The database refused the turn's content (PostgreSQL data exception). */
        DATA_REJECTED("data_rejected", false, null, 422,
                "The turn ran but was not stored: the database refused its content. Remove unusual characters and send it again."),
        /** Another writer changed the conversation while the turn ran. */
        CONFLICT("conflict", true, 2000L, 409,
                "The turn ran but was not stored: the conversation was changed by another request while it ran. Reload the conversation and send it again."),
        /** Cluster mode: another node took the conversation over. */
        FENCED("fenced", true, 2000L, 409,
                "The turn ran but was not stored: another node took over the conversation while it ran. Send it again."),
        /**
         * The write was refused because the conversation was ended while the turn ran:
         * the end stands (410, as for a message to an ended conversation).
         */
        CONVERSATION_ENDED("conversation_ended", false, null, 410,
                "The turn ran but was not stored: the conversation was ended while it ran."),
        /**
         * Any other failure of the write — an unavailable database, a socket error, a
         * write-concern timeout, an interrupt. Not definitive: the database may have
         * applied the write before it failed to confirm it, so a blind retry can run
         * the turn twice (S8). Not retryable; read the conversation first.
         */
        STORE_FAILURE("store_failure", false, null, 503,
                "The turn ran, but the database did not confirm its write, so it may not have been stored. Read the conversation "
                        + "before sending it again."),
        /**
         * The turn's outcome was never confirmed: the watchdog gave up on it while it
         * ran or wrote, or it failed after its answer was produced (S1). After the
         * watchdog it may or may not be stored; a turn the runtime failed after its
         * answer never reached its write and is not stored. Both are answered with this
         * one reason, because for an abandoned turn the two backstops race (see
         * {@code ConversationStepRunner#flushDeferredDelivery}).
         */
        OUTCOME_UNKNOWN("outcome_unknown", false, null, 503,
                "The turn ran, but it did not finish in time or failed afterwards, so it may not have been stored. Read the "
                        + "conversation before sending it again.");

        private final String tag;
        private final boolean retryable;
        private final Long retryAfterMs;
        private final int httpStatus;
        private final String message;

        Reason(String tag, boolean retryable, Long retryAfterMs, int httpStatus, String message) {
            this.tag = tag;
            this.retryable = retryable;
            this.retryAfterMs = retryAfterMs;
            this.httpStatus = httpStatus;
            this.message = message;
        }

        public String tag() {
            return tag;
        }

        /** The status the synchronous REST turn answers with. */
        public int httpStatus() {
            return httpStatus;
        }

        /** The caller-facing error (its message is written for API clients). */
        public TurnError error() {
            return new TurnError(ERROR_CODE, retryable, retryAfterMs, message, null, null, tag);
        }

        /**
         * The same outcome in words an end user reads — in a Slack thread, or a tool
         * result an agent relays (F9). Channel-neutral: nothing about reloading or
         * reading "the conversation" through an API.
         */
        public String userMessage() {
            return switch (this) {
                case CONVERSATION_TOO_LARGE -> "This conversation has reached its size limit, so the message was not saved. Please start a "
                        + "new conversation.";
                case DATA_REJECTED -> "The message could not be saved because it contains characters that cannot be stored. Please "
                        + "rephrase it and send it again.";
                case CONFLICT -> "The message was not saved because the conversation changed at the same moment. Please send it again.";
                case FENCED -> "The message was not saved because the conversation was being handled elsewhere. Please send it again.";
                case CONVERSATION_ENDED -> "This conversation has ended, so the message was not saved.";
                case STORE_FAILURE, OUTCOME_UNKNOWN -> "There was a technical problem, so the message may not have been saved. Please "
                        + "check whether it went through before sending it again.";
            };
        }
    }

    /** {@link Reason#userMessage()} of a not-stored error. */
    public static String userMessage(TurnError error) {
        return reasonOf(error).userMessage();
    }

    /**
     * Classifies a failed conversation write. Walks the cause chain, because the
     * stores wrap the driver exception (PostgreSQL: {@code SQLException} in a
     * {@code ResourceStoreException}).
     */
    public static Reason classify(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            Reason reason = classifyOne(current);
            if (reason != null) {
                return reason;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return Reason.STORE_FAILURE;
    }

    private static Reason classifyOne(Throwable t) {
        if (t instanceof ConversationTooLargeException) {
            return Reason.CONVERSATION_TOO_LARGE;
        }
        if (t instanceof ConversationFencedException) {
            return Reason.FENCED;
        }
        if (t instanceof ConcurrentConversationModificationException) {
            return Reason.CONFLICT;
        }
        if (t instanceof BsonMaximumSizeExceededException) {
            return Reason.CONVERSATION_TOO_LARGE;
        }
        if (t instanceof MongoException mongo && MONGO_DOCUMENT_TOO_LARGE_CODES.contains(mongo.getCode())) {
            return Reason.CONVERSATION_TOO_LARGE;
        }
        if (t instanceof SQLException sql && sql.getSQLState() != null) {
            String state = sql.getSQLState();
            // Class 22: data exception (an untranslatable character such as U+0000,
            // a value out of range). Class 54: program limit exceeded (a value or
            // row too large for the server).
            if (state.startsWith("22")) {
                return Reason.DATA_REJECTED;
            }
            if (state.startsWith("54")) {
                return Reason.CONVERSATION_TOO_LARGE;
            }
        }
        return null;
    }

    /**
     * Turns the snapshot of a turn that was not stored into one that carries no
     * answer (S4): state {@code ERROR}, the error set, and no outputs, steps or
     * properties of the unstored turn. Every surface that reads a snapshot — MCP's
     * {@code chat_with_agent}, Slack, the sub-agent tools, group members — then
     * sees a failed turn with nothing to present as its reply, without having to
     * know about this error code.
     */
    public static void withoutAnswer(SimpleConversationMemorySnapshot snapshot, TurnError error) {
        snapshot.setConversationState(ConversationState.ERROR);
        snapshot.setError(error);
        snapshot.setConversationOutputs(new ArrayList<>());
        snapshot.setConversationSteps(new ArrayList<>());
        snapshot.setConversationProperties(new LinkedHashMap<>());
    }

    /**
     * The error of a say refused at admission because the stored conversation is
     * already over {@code eddi.conversations.max-document-bytes} (S6): the turn did
     * not run, nothing was spent on it.
     */
    public static final String CONVERSATION_TOO_LARGE_CODE = "CONVERSATION_TOO_LARGE";

    /** See {@link #CONVERSATION_TOO_LARGE_CODE}. */
    public static TurnError conversationFull() {
        return new TurnError(CONVERSATION_TOO_LARGE_CODE, false, null,
                "This conversation has reached its size limit; the message was not processed. Start a new conversation.");
    }

    /** Whether {@code error} reports a turn that ran but was not stored. */
    public static boolean isNotStored(TurnError error) {
        return error != null && ERROR_CODE.equals(error.code());
    }

    /**
     * The reason of a not-stored error; {@link Reason#STORE_FAILURE} when it names
     * none this version knows.
     */
    public static Reason reasonOf(TurnError error) {
        if (error != null) {
            for (Reason reason : Reason.values()) {
                if (reason.tag.equals(error.errorType())) {
                    return reason;
                }
            }
        }
        return Reason.STORE_FAILURE;
    }

    /**
     * Ends a streamed turn that was not stored: the tokens are already on the wire,
     * so the stream's terminal event is an {@code error} carrying this error
     * instead of {@code done}.
     */
    public static final class TurnNotStoredException extends RuntimeException {
        private final transient TurnError error;

        public TurnNotStoredException(TurnError error) {
            super(error != null ? error.message() : "The turn ran but was not stored");
            this.error = error;
        }

        public TurnError getError() {
            return error;
        }
    }
}
