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

import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * What a knowledge base's failures are called when they leave the server.
 *
 * <p>
 * An embedding provider's or a database driver's message is not for the caller:
 * pgvector reports the failing {@code INSERT} with its column list, a provider
 * may echo part of the request, and either can carry a host name. The full
 * exception goes to the server log under the knowledge base's id; the caller
 * gets one of a few fixed sentences. Messages EDDI wrote itself for the
 * operator (a refused vector space, a full in-memory store, a connector's own
 * words) are passed through — scrubbed of URL credentials and query strings,
 * because a crawl URL can carry both.
 *
 * <p>
 * An {@link IllegalArgumentException} passes only when its text is the kind the
 * store and model factories write — a parameter name and a rule, never a value:
 * no URL, no cause that quotes the input ({@code URI.create} and
 * {@code Integer.parseInt} do), and short. That is a test of the text rather
 * than of the type, because the factories raise plain
 * {@code IllegalArgumentException} in a few dozen places; the test is
 * deliberately stricter than they are.
 */
public final class RagFailures {

    /** The answer for anything that is not one of the named cases. */
    public static final String GENERIC = "The embedding model or the vector store failed. The details are in the server log.";

    private static final int MAX_CAUSE_DEPTH = 8;
    private static final int MAX_AUTHOR_MESSAGE = 300;

    private static final Pattern URL_USERINFO = Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]*://)[^/\\s@?#]*@");
    private static final Pattern URL_QUERY_OR_FRAGMENT = Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]*://[^\\s?#]*)[?#]\\S*");

    private RagFailures() {
    }

    /** A client-safe sentence for {@code failure}; never null. */
    public static String clientMessage(Throwable failure) {
        if (failure == null) {
            return GENERIC;
        }
        if (failure instanceof VectorSpaceMismatchException || failure instanceof InMemoryStoreFullException
                || failure instanceof InMemoryStoreRetiredException) {
            return scrub(failure.getMessage());
        }
        if (failure instanceof OperatorFailureException) {
            return scrub(failure.getMessage());
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
        if (failure instanceof IllegalArgumentException && isAuthorMessage(failure)) {
            return failure.getMessage();
        }
        return GENERIC;
    }

    /**
     * Removes what a URL in a message can carry: the credentials before the host,
     * and the query string and fragment after the path.
     */
    public static String scrub(String message) {
        if (message == null) {
            return "";
        }
        String noUserinfo = URL_USERINFO.matcher(message).replaceAll("$1");
        return URL_QUERY_OR_FRAGMENT.matcher(noUserinfo).replaceAll("$1");
    }

    private static boolean isAuthorMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank() || message.length() > MAX_AUTHOR_MESSAGE || message.contains("://")) {
            return false;
        }
        for (Throwable t = failure; t != null && !(t.getCause() == t); t = t.getCause()) {
            // A message that quotes its input: URI.create, Integer.parseInt.
            if (t instanceof NumberFormatException || t instanceof URISyntaxException) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a run that hits this failure can only go on failing the same way: the
     * credentials, the model, the vector space or the store are wrong, so every
     * further document would fail too, each costing a provider call.
     */
    public static boolean endsRun(Throwable failure) {
        return failure instanceof VectorSpaceMismatchException || isOrCausedBy(failure, AuthenticationException.class)
                || isOrCausedBy(failure, ModelNotFoundException.class) || isOrCausedBy(failure, SQLException.class);
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
