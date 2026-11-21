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
     * The request authority as Quarkus' proxy-aware request reports it,
     * lower-cased, never a raw {@code X-Forwarded-Host}. Empty outside a request.
     */
    Optional<String> authority();

    /** The trimmed {@link #ORG_HEADER} value, or empty. */
    Optional<String> requestedOrg();
}
