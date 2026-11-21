/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Locale;
import java.util.Optional;

/**
 * The org of the current request (plan §6.2).
 * <p>
 * In {@link OrgMode#SINGLE} it is always {@link OrgId#DEFAULT}, without looking
 * at the request. Otherwise the resolver named by {@code eddi.org.resolver}
 * decides, and the result is memoised on the Vert.x {@link RoutingContext}, so
 * one request resolves once however many beans ask.
 */
@ApplicationScoped
public class OrgResolution {

    static final String MEMO_KEY = OrgResolution.class.getName();

    private final Instance<OrgResolver> resolvers;
    private final Instance<SecurityIdentity> identity;
    private final Instance<CurrentVertxRequest> currentVertxRequest;
    private final String resolverName;
    private volatile OrgResolver resolver;

    @Inject
    public OrgResolution(Instance<OrgResolver> resolvers, Instance<SecurityIdentity> identity,
            Instance<CurrentVertxRequest> currentVertxRequest,
            @ConfigProperty(name = "eddi.org.resolver", defaultValue = SingleOrgResolver.NAME) String resolverName) {
        this.resolvers = resolvers;
        this.identity = identity;
        this.currentVertxRequest = currentVertxRequest;
        this.resolverName = resolverName;
    }

    /**
     * The org of the current request.
     *
     * @throws OrgResolutionException
     *             in multi mode, when the request carries no acceptable org
     */
    public OrgId resolve() {
        if (OrgContext.mode() == OrgMode.SINGLE) {
            return OrgId.DEFAULT;
        }
        RoutingContext rc = routingContext();
        if (rc != null && rc.get(MEMO_KEY) instanceof OrgId memoised) {
            return memoised;
        }
        OrgId org = resolver().resolve(new LazyFacts(rc));
        if (org == null) {
            throw new OrgResolutionException("the request names no organization");
        }
        if (rc != null) {
            rc.put(MEMO_KEY, org);
        }
        return org;
    }

    /** The resolver {@code eddi.org.resolver} names; fails when none matches. */
    OrgResolver resolver() {
        OrgResolver selected = resolver;
        if (selected == null) {
            selected = resolvers.stream().filter(r -> r.name().equals(resolverName)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("eddi.org.resolver=" + resolverName + " names no known resolver"));
            resolver = selected;
        }
        return selected;
    }

    String resolverName() {
        return resolverName;
    }

    private RoutingContext routingContext() {
        try {
            return currentVertxRequest.isResolvable() ? currentVertxRequest.get().getCurrent() : null;
        } catch (RuntimeException e) {
            // no active request (a worker thread, a startup task)
            return null;
        }
    }

    private final class LazyFacts implements OrgRequestFacts {
        private final RoutingContext rc;

        LazyFacts(RoutingContext rc) {
            this.rc = rc;
        }

        @Override
        public SecurityIdentity identity() {
            try {
                return identity.get();
            } catch (ContextNotActiveException e) {
                // no request context: a worker thread or a startup task
                return QuarkusSecurityIdentity.builder().setAnonymous(true).build();
            }
        }

        @Override
        public Optional<String> authority() {
            if (rc == null || rc.request().authority() == null) {
                return Optional.empty();
            }
            return Optional.of(rc.request().authority().host().toLowerCase(Locale.ROOT));
        }

        @Override
        public Optional<String> requestedOrg() {
            if (rc == null) {
                return Optional.empty();
            }
            String value = rc.request().getHeader(ORG_HEADER);
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
        }
    }
}
