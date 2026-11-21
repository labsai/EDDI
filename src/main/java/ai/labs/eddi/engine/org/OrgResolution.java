/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;

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
    private final BooleanSupplier requestContextActive;
    private final String resolverName;
    private volatile OrgResolver resolver;

    @Inject
    public OrgResolution(Instance<OrgResolver> resolvers, Instance<SecurityIdentity> identity,
            Instance<CurrentVertxRequest> currentVertxRequest,
            @ConfigProperty(name = "eddi.org.resolver", defaultValue = SingleOrgResolver.NAME) String resolverName) {
        this(resolvers, identity, currentVertxRequest, resolverName, OrgResolution::arcRequestContextActive);
    }

    OrgResolution(Instance<OrgResolver> resolvers, Instance<SecurityIdentity> identity, Instance<CurrentVertxRequest> currentVertxRequest,
            String resolverName, BooleanSupplier requestContextActive) {
        this.resolvers = resolvers;
        this.identity = identity;
        this.currentVertxRequest = currentVertxRequest;
        this.resolverName = normalize(resolverName);
        this.requestContextActive = requestContextActive;
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
            throw new OrgResolutionException("the resolver returned no organization");
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

    /**
     * {@code eddi.org.resolver}, trimmed and lower-cased like
     * {@code eddi.org.mode}.
     */
    String resolverName() {
        return resolverName;
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private RoutingContext routingContext() {
        try {
            return currentVertxRequest.isResolvable() ? currentVertxRequest.get().getCurrent() : null;
        } catch (RuntimeException e) {
            // no active request (a worker thread, a startup task)
            return null;
        }
    }

    private static boolean arcRequestContextActive() {
        ArcContainer container = Arc.container();
        return container != null && container.requestContext().isActive();
    }

    private final class LazyFacts implements OrgRequestFacts {
        private final RoutingContext rc;

        LazyFacts(RoutingContext rc) {
            this.rc = rc;
        }

        @Override
        public SecurityIdentity identity() {
            // The injected identity is a request-scoped client proxy: get() never fails,
            // but its first use would throw ContextNotActiveException outside a request.
            if (!requestContextActive.getAsBoolean()) {
                return QuarkusSecurityIdentity.builder().setAnonymous(true).build();
            }
            return identity.get();
        }

        @Override
        public Optional<String> authority() {
            HostAndPort authority = rc == null ? null : rc.request().authority();
            if (authority == null || authority.host() == null || authority.host().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(authority.host().toLowerCase(Locale.ROOT));
        }

        @Override
        public Optional<String> requestedOrg() {
            if (rc == null) {
                return Optional.empty();
            }
            List<String> values = rc.request().headers().getAll(ORG_HEADER);
            if (values == null || values.isEmpty()) {
                return Optional.empty();
            }
            if (values.size() > 1) {
                throw new OrgResolutionException("more than one " + ORG_HEADER + " header");
            }
            String value = values.get(0);
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
        }
    }
}
