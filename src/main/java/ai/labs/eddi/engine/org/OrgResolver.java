/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

/**
 * Derives the org of a request (plan §9.1), selected by
 * {@code eddi.org.resolver}. This release ships only {@link SingleOrgResolver};
 * the {@code keycloak-organizations} resolver follows in Phase 4.
 * <p>
 * A resolver never falls back to {@link OrgId#DEFAULT} in multi mode: a missing
 * or unknown org is an {@link OrgResolutionException} (a 403).
 */
public interface OrgResolver {

    /** The value of {@code eddi.org.resolver} that selects this resolver. */
    String name();

    /**
     * The org of the request described by {@code facts}.
     *
     * @throws OrgResolutionException
     *             when the request carries no org this resolver accepts
     */
    OrgId resolve(OrgRequestFacts facts);
}
