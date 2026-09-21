/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Rejects an authenticated identity whose principal has no name, so the request
 * answers 401 instead of running as nobody.
 * <p>
 * Quarkus OIDC names the principal from
 * {@code quarkus.oidc.token.principal-claim} or, unset, the first of
 * {@code upn}, {@code preferred_username} and {@code sub}. A token carrying
 * none of them still authenticates, with a {@code null} name. Everything
 * downstream keys ownership on that name, so letting it through is not neutral:
 * a started conversation was stamped with a random {@code anonymous-<hex>}
 * owner the caller could then not read back, and the ownership checks answered
 * 500. Rejecting at authentication gives one legible failure in place of
 * several confusing ones.
 * <p>
 * Anonymous identities pass untouched — they have no name by design, and
 * {@code @RolesAllowed} and the HTTP permission policies decide what they may
 * do. Background work (schedule fires, group members, sub-agents) never
 * authenticates a request, so it never reaches this augmentor.
 * <p>
 * A misconfigured realm fails every request, so the explanatory WARN is emitted
 * at most once every five minutes; the rejections in between log at DEBUG.
 */
@ApplicationScoped
public class NamelessPrincipalAugmentor implements SecurityIdentityAugmentor {

    private static final Logger LOGGER = Logger.getLogger(NamelessPrincipalAugmentor.class);

    static final long WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(5);
    static final String FAILURE_MESSAGE = "The authenticated identity has no principal name";

    private final Optional<String> principalClaim;
    private final LongSupplier nanoClock;
    /** When the last WARN was emitted; {@code null} until the first. */
    private final AtomicReference<Long> lastWarnNanos = new AtomicReference<>();

    @Inject
    public NamelessPrincipalAugmentor(@ConfigProperty(name = "quarkus.oidc.token.principal-claim") Optional<String> principalClaim) {
        this(principalClaim, System::nanoTime);
    }

    NamelessPrincipalAugmentor(Optional<String> principalClaim, LongSupplier nanoClock) {
        this.principalClaim = principalClaim;
        this.nanoClock = nanoClock;
    }

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity == null || identity.isAnonymous() || OwnershipValidator.principalName(identity) != null) {
            return Uni.createFrom().item(identity);
        }
        String detail = describe(identity);
        if (shouldWarn()) {
            LOGGER.warnf("[SECURITY] Rejected an authenticated request (401): the identity has no principal name. %s", detail);
        } else {
            LOGGER.debugf("Rejected an authenticated request (401): the identity has no principal name. %s", detail);
        }
        return Uni.createFrom().failure(new AuthenticationFailedException(FAILURE_MESSAGE));
    }

    boolean shouldWarn() {
        long now = nanoClock.getAsLong();
        Long last = lastWarnNanos.get();
        if (last != null && now - last < WARN_INTERVAL_NANOS) {
            return false;
        }
        // Identity compare-and-set on the boxed value just read: of two concurrent
        // rejections, exactly one wins the WARN.
        return lastWarnNanos.compareAndSet(last, now);
    }

    /**
     * Names the claims the principal is read from and, for a JWT, which token it
     * was so the operator can find the realm or client that minted it. Only claim
     * names, the issuer and the client id are logged — never the token.
     */
    String describe(SecurityIdentity identity) {
        String missing = principalClaim.filter(c -> !c.isBlank())
                .map(c -> "the configured principal claim '" + sanitize(c) + "' (quarkus.oidc.token.principal-claim)")
                .orElse("any of the claims Quarkus names the principal from: 'upn', 'preferred_username' or 'sub'");
        StringBuilder detail = new StringBuilder("The token does not carry ").append(missing)
                .append(". Add the claim through the identity provider's client scopes or mappers, or point "
                        + "quarkus.oidc.token.principal-claim at a claim the token does carry.");
        if (identity.getPrincipal() instanceof JsonWebToken jwt) {
            detail.append(" Token: issuer='").append(sanitize(safeString(claim(jwt, "iss"))))
                    .append("', client='").append(sanitize(safeString(claim(jwt, "azp")))).append('\'');
        }
        return detail.toString();
    }

    private static String claim(JsonWebToken jwt, String name) {
        try {
            Object value = jwt.getClaim(name);
            return value == null ? null : value.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String safeString(String value) {
        return value == null ? "<none>" : value;
    }
}
