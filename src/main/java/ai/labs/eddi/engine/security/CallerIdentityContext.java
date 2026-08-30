/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.security.credential.TokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Carries the authenticated caller from the request thread to the pipeline
 * worker thread that runs their conversation turn.
 * <p>
 * A conversation turn is built on the request thread but executed later on a
 * pool thread (see {@code IConversationCoordinator#submitInOrder}), where
 * request-scoped beans such as {@link SecurityIdentity} are no longer
 * resolvable. {@link #capture()} therefore reads the identity while the request
 * context is still active, and {@link #bind(CallerIdentity)} re-establishes it
 * around the execution.
 * <p>
 * The binding is a {@link ThreadLocal}, so it must always be cleared in a
 * {@code finally} block — pool threads are reused across conversations and a
 * leaked token would be readable by the next caller's turn.
 *
 * @author ginccc
 */
@ApplicationScoped
public class CallerIdentityContext {

    private static final Logger LOGGER = Logger.getLogger(CallerIdentityContext.class);

    private static final ThreadLocal<CallerIdentity> CURRENT = new ThreadLocal<>();

    /**
     * Header a caller supplies a {@code CALLER_SUPPLIED} connection's credential
     * in, once per connection: {@code <connectionName> <credential value>}.
     * <p>
     * Named for the existing {@code X-EDDI-Conversation-Id} convention.
     */
    public static final String CONNECTION_CREDENTIAL_HEADER = "X-EDDI-Connection-Credential";

    /**
     * How many such headers are read. An agent referencing more than a handful of
     * caller-supplied connections in one turn is a config mistake, and the cap
     * keeps a hostile client from making header parsing the expensive part of a
     * request.
     */
    private static final int MAX_CONNECTION_CREDENTIALS = 16;

    /** Longest credential value read. Generous for a JWT, bounded against abuse. */
    private static final int MAX_CREDENTIAL_CHARS = 8192;

    private final SecurityIdentity securityIdentity;
    private final CurrentVertxRequest currentVertxRequest;

    @Inject
    public CallerIdentityContext(SecurityIdentity securityIdentity, CurrentVertxRequest currentVertxRequest) {
        this.securityIdentity = securityIdentity;
        this.currentVertxRequest = currentVertxRequest;
    }

    /**
     * Read the caller from the active request.
     * <p>
     * Must be called on the request thread. Returns {@code null} when there is no
     * active request (scheduled jobs, triggers, tests) or when the caller is
     * anonymous — normal, and simply means a {@code ${caller:...}} reference cannot
     * be satisfied for this turn.
     * <p>
     * A token is not required: an authenticated request with a principal but no
     * bearer credential yields an identity whose {@link CallerIdentity#hasToken()}
     * is false, so {@code ${caller:userId}} resolves while {@code ${caller:token}}
     * still fails closed.
     *
     * @return the captured identity, or {@code null}
     */
    public CallerIdentity capture() {
        try {
            if (securityIdentity == null || securityIdentity.isAnonymous()) {
                // An anonymous request gets no identity, so any connection credential it
                // carried is dropped — deliberately: a caller that has not authenticated
                // must not be able to make EDDI spend a credential on its behalf. Said out
                // loud, because the symptom otherwise is a CALLER_SUPPLIED connection
                // failing closed while the operator can see the header going out, and
                // nothing anywhere connects the two.
                warnIfConnectionCredentialsDropped();
                return null;
            }
            // Token and principal are captured independently: an authenticated
            // request without a bearer credential still has a usable userId, and
            // requiring both would make ${caller:userId} unavailable there for no
            // reason. ${caller:token} still fails closed via hasToken().
            var credential = securityIdentity.getCredential(TokenCredential.class);
            String token = credential != null && credential.getToken() != null && !credential.getToken().isBlank()
                    ? credential.getToken()
                    : null;
            var principal = securityIdentity.getPrincipal();
            String userId = principal != null ? principal.getName() : null;
            if (token == null && (userId == null || userId.isBlank())) {
                return null;
            }
            return new CallerIdentity(token, userId, currentRequestOrigin(), captureConnectionCredentials());
        } catch (Exception e) {
            // No active request context — nothing to capture. Not an error.
            LOGGER.debugf("No caller identity to capture: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Credentials the caller supplied for {@code CALLER_SUPPLIED} connections, read
     * from repeated {@value #CONNECTION_CREDENTIAL_HEADER} headers.
     * <p>
     * Each header line is {@code <connectionName> <credential value>} — the
     * connection name up to the first space, everything after it the whole header
     * value to send. Splitting on the first space rather than parsing a delimiter
     * keeps values containing spaces ({@code "Bearer abc"}) intact without an
     * escaping scheme.
     * <p>
     * A header rather than a body field because the carrier must be one the
     * conversation store never sees: the request body becomes conversation input.
     * <p>
     * Malformed and duplicate entries are <em>dropped</em>, not guessed at. A
     * duplicate name silently resolving to whichever line iterated last would
     * decide by ordering which of two credentials a call is made with, so both are
     * dropped and the connection fails closed with a message the operator can act
     * on — louder than sending one of them and hoping it was the right one.
     */
    private Map<String, String> captureConnectionCredentials() {
        try {
            return readConnectionCredentials();
        } catch (Exception e) {
            // Its own boundary, not the caller's. capture() answers a failure by
            // returning no identity at all, and letting a header-parsing problem take
            // that path would drop the caller's TOKEN too — turning a bad credential
            // header into a broken ${caller:token} somewhere unrelated.
            LOGGER.warnf("Could not read %s headers: %s", CONNECTION_CREDENTIAL_HEADER, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Say so when an unauthenticated request carried connection credentials. Never
     * logs the values, and never the connection names either — the header is
     * attacker-controllable on an anonymous path, so its content does not belong in
     * a log line.
     */
    private void warnIfConnectionCredentialsDropped() {
        try {
            var current = currentVertxRequest.getCurrent();
            if (current == null) {
                return;
            }
            var lines = current.request().headers().getAll(CONNECTION_CREDENTIAL_HEADER);
            if (lines != null && !lines.isEmpty()) {
                LOGGER.warnf("Ignoring %d %s header(s): the request is not authenticated, and a connection credential is only accepted "
                        + "from an authenticated caller.", lines.size(), CONNECTION_CREDENTIAL_HEADER);
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not inspect %s headers on an anonymous request: %s", CONNECTION_CREDENTIAL_HEADER, e.getMessage());
        }
    }

    private Map<String, String> readConnectionCredentials() {
        var current = currentVertxRequest.getCurrent();
        if (current == null) {
            return Map.of();
        }
        List<String> lines = current.request().headers().getAll(CONNECTION_CREDENTIAL_HEADER);
        if (lines == null || lines.isEmpty()) {
            return Map.of();
        }
        if (lines.size() > MAX_CONNECTION_CREDENTIALS) {
            LOGGER.warnf("Ignoring %s headers: %d supplied, at most %d are read.", CONNECTION_CREDENTIAL_HEADER, lines.size(),
                    MAX_CONNECTION_CREDENTIALS);
            return Map.of();
        }
        var credentials = new HashMap<String, String>();
        var duplicated = new HashSet<String>();
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            int split = line.indexOf(' ');
            if (split <= 0 || split == line.length() - 1) {
                LOGGER.warnf("Ignoring a malformed %s header: expected '<connectionName> <value>'.", CONNECTION_CREDENTIAL_HEADER);
                continue;
            }
            String name = line.substring(0, split).trim();
            String value = line.substring(split + 1).trim();
            if (name.isEmpty() || value.isEmpty() || value.length() > MAX_CREDENTIAL_CHARS) {
                LOGGER.warnf("Ignoring a %s header with an empty name, an empty value, or a value over %d characters.",
                        CONNECTION_CREDENTIAL_HEADER, MAX_CREDENTIAL_CHARS);
                continue;
            }
            if (credentials.put(name, value) != null) {
                duplicated.add(name);
            }
        }
        for (String name : duplicated) {
            // Never resolve a duplicate by ordering — see the note above.
            credentials.remove(name);
            LOGGER.warnf("Ignoring the credentials for connection '%s': it was supplied more than once and only one can be sent.", name);
        }
        return credentials;
    }

    /**
     * The {@code scheme://host[:port]} the caller addressed.
     * <p>
     * Taken from the inbound request rather than configuration so the token is only
     * ever sent back to the exact origin the caller already trusted it to.
     */
    private String currentRequestOrigin() {
        try {
            var current = currentVertxRequest.getCurrent();
            if (current == null) {
                return null;
            }
            var request = current.request();
            return OriginMatcher.normalize(request.scheme(), request.authority() != null ? request.authority().host() : null,
                    request.authority() != null ? request.authority().port() : -1);
        } catch (Exception e) {
            LOGGER.debugf("Could not determine request origin: %s", e.getMessage());
            return null;
        }
    }

    /** Bind an identity to the current thread for the duration of a turn. */
    public void bind(CallerIdentity identity) {
        if (identity == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(identity);
        }
    }

    /**
     * Clear the binding.
     * <p>
     * Always call this in a {@code finally} — the thread goes back to a pool that
     * serves other users.
     */
    public void clear() {
        CURRENT.remove();
    }

    /** The identity bound to this thread, or {@code null}. */
    public CallerIdentity current() {
        return CURRENT.get();
    }

    /**
     * Wrap work that will run on another thread so it keeps the current caller.
     * <p>
     * Used where the pipeline hands work to a further executor — a fire-and-forget
     * batch, for instance — which would otherwise lose the binding and make a
     * {@code ${caller:token}} reference fail closed for no reason the config author
     * could see.
     */
    public <T> Callable<T> propagate(Callable<T> work) {
        return withIdentity(current(), work);
    }

    /** {@link #propagate(Callable)} for work dispatched as a {@link Runnable}. */
    public Runnable propagate(Runnable work) {
        return withIdentity(current(), work);
    }

    /**
     * The caller for work about to be dispatched, wherever we happen to be.
     * <p>
     * Prefers the active request (the dispatch happens on the REST thread, as when
     * a group discussion is kicked off) and falls back to the binding already on
     * this thread (the dispatch happens mid-pipeline, as in a model cascade).
     */
    public CallerIdentity captureOrCurrent() {
        var captured = capture();
        return captured != null ? captured : current();
    }

    /**
     * Bind an explicit identity around work that will run on another thread.
     * <p>
     * A {@code null} identity is <em>not</em> a no-op: it binds null, so work that
     * is deliberately unauthenticated cannot observe a caller left on a pooled
     * thread by whatever ran there before. The previous binding is restored rather
     * than cleared, so a nested wrapper does not wipe the outer one.
     */
    public <T> Callable<T> withIdentity(CallerIdentity identity, Callable<T> work) {
        return () -> {
            final CallerIdentity previous = current();
            bind(identity);
            try {
                return work.call();
            } finally {
                bind(previous);
            }
        };
    }

    /** {@link #withIdentity(CallerIdentity, Callable)} for a {@link Runnable}. */
    public Runnable withIdentity(CallerIdentity identity, Runnable work) {
        return () -> {
            final CallerIdentity previous = current();
            bind(identity);
            try {
                work.run();
            } finally {
                bind(previous);
            }
        };
    }

    /**
     * {@link #withIdentity(CallerIdentity, Callable)} for a {@link Supplier}, as
     * used by {@code CompletableFuture.supplyAsync}.
     * <p>
     * Named apart from the {@code withIdentity} overloads deliberately: a
     * value-returning lambda satisfies both {@link Callable} and {@link Supplier},
     * so same-named overloads are ambiguous at every call site.
     */
    public <T> Supplier<T> withIdentitySupplying(CallerIdentity identity, Supplier<T> work) {
        return () -> {
            final CallerIdentity previous = current();
            bind(identity);
            try {
                return work.get();
            } finally {
                bind(previous);
            }
        };
    }
}
