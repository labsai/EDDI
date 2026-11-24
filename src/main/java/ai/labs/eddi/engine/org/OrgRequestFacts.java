/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import io.quarkus.security.identity.SecurityIdentity;

import java.util.Optional;

/**
 * What a resolver may look at. The accessors are lazy: a resolver that ignores
 * them, as {@link SingleOrgResolver} does, costs nothing, and the identity is
 * not touched outside a request.
 */
public interface OrgRequestFacts {

    /** The header a caller in several orgs uses to select one (plan §9.1). */
    String ORG_HEADER = "X-EDDI-Org";

    /**
     * The verified caller identity; an anonymous identity outside a request
     * context.
     */
    SecurityIdentity identity();

    /**
     * The request host as Quarkus' proxy-aware request reports it, lower-cased,
     * without the port. Empty outside a request.
     * <p>
     * It reflects a forwarded host header only as far as
     * {@code quarkus.http.proxy.*} allows. With {@code enable-forwarded-host} on
     * and no {@code trusted-proxies}, any client can set it. A resolver that routes
     * on it (host binding, plan §7.1) therefore needs trusted proxies, and the
     * multi-mode startup check will require them.
     */
    Optional<String> authority();

    /**
     * The trimmed {@link #ORG_HEADER} value, or empty.
     *
     * @throws OrgResolutionException
     *             when the header is sent more than once, which would be ambiguous
     */
    Optional<String> requestedOrg();
}
