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
     * active request (scheduled jobs, triggers, tests) or when the request carried
     * no bearer token — both are normal, and simply mean a {@code ${caller:token}}
     * reference cannot be satisfied for this turn.
     *
     * @return the captured identity, or {@code null}
     */
    public CallerIdentity capture() {
        try {
            if (securityIdentity == null || securityIdentity.isAnonymous()) {
                return null;
            }
            var credential = securityIdentity.getCredential(TokenCredential.class);
            if (credential == null || credential.getToken() == null || credential.getToken().isBlank()) {
                return null;
            }
            var principal = securityIdentity.getPrincipal();
            return new CallerIdentity(credential.getToken(), principal != null ? principal.getName() : null, currentRequestOrigin());
        } catch (Exception e) {
            // No active request context — nothing to capture. Not an error.
            LOGGER.debugf("No caller identity to capture: %s", e.getMessage());
            return null;
        }
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

    /** Bind an explicit identity around work that will run on another thread. */
    public <T> Callable<T> withIdentity(CallerIdentity identity, Callable<T> work) {
        if (identity == null) {
            return work;
        }
        return () -> {
            bind(identity);
            try {
                return work.call();
            } finally {
                clear();
            }
        };
    }

    /** {@link #withIdentity(CallerIdentity, Callable)} for a {@link Runnable}. */
    public Runnable withIdentity(CallerIdentity identity, Runnable work) {
        if (identity == null) {
            return work;
        }
        return () -> {
            bind(identity);
            try {
                work.run();
            } finally {
                clear();
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
        if (identity == null) {
            return work;
        }
        return () -> {
            bind(identity);
            try {
                return work.get();
            } finally {
                clear();
            }
        };
    }
}
