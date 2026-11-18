/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.modules.llm.impl.InMemoryStoreFullException;
import ai.labs.eddi.modules.llm.impl.InMemoryStoreRetiredException;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;

import java.sql.SQLException;

/**
 * What a knowledge base's failures are called when they leave the server.
 *
 * <p>
 * An embedding provider's or a database driver's message is not for the caller:
 * pgvector reports the failing {@code INSERT} with its column list, a provider
 * may echo part of the request, and either can carry a host name. The full
 * exception goes to the server log under the knowledge base's id; the caller
 * gets one of a few fixed sentences. Messages EDDI wrote itself for the
 * operator (a refused vector space, a full in-memory store, a rejected store
 * parameter) are passed through, because they are the answer.
 */
public final class RagFailures {

    /** The answer for anything that is not one of the named cases. */
    public static final String GENERIC = "The embedding model or the vector store failed. The details are in the server log.";

    private static final int MAX_CAUSE_DEPTH = 8;

    private RagFailures() {
    }

    /** A client-safe sentence for {@code failure}; never null. */
    public static String clientMessage(Throwable failure) {
        if (failure == null) {
            return GENERIC;
        }
        if (failure instanceof VectorSpaceMismatchException || failure instanceof OperatorFailureException
                || failure instanceof InMemoryStoreFullException || failure instanceof InMemoryStoreRetiredException) {
            return failure.getMessage();
        }
        if (isOrCausedBy(failure, AuthenticationException.class)) {
            return "The embedding provider rejected the configured credentials. Check the apiKey of the "
                    + "knowledge base's embeddingParameters.";
        }
        if (isOrCausedBy(failure, ModelNotFoundException.class)) {
            return "The embedding provider does not know the configured model. Check embeddingParameters.model.";
        }
        if (isOrCausedBy(failure, RateLimitException.class)) {
            return "The embedding provider is limiting requests right now. Try again later.";
        }
        if (isOrCausedBy(failure, SQLException.class)) {
            return "The vector store reported an error. The details are in the server log.";
        }
        if (failure instanceof IllegalArgumentException && failure.getMessage() != null && !failure.getMessage().isBlank()) {
            // Raised by the store and model factories and by validation, whose messages
            // name the parameter and never echo its value.
            return failure.getMessage();
        }
        return GENERIC;
    }

    /**
     * Whether a run that hits this failure can only go on failing the same way: the
     * credentials, the model or the vector space are wrong, so every further
     * document would fail too, each costing a provider call.
     */
    public static boolean endsRun(Throwable failure) {
        return failure instanceof VectorSpaceMismatchException || isOrCausedBy(failure, AuthenticationException.class)
                || isOrCausedBy(failure, ModelNotFoundException.class);
    }

    private static boolean isOrCausedBy(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }
}
