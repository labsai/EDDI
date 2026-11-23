/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.Callable;

/**
 * Binds the current request's org around work that runs outside a resource
 * method (plan §6.2): JAX-RS filters, {@code SecurityIdentityAugmentor}s,
 * exception mappers and raw Vert.x routes that touch org data. Resource and MCP
 * classes get the same through {@link OrgBound}.
 */
@ApplicationScoped
public class OrgRequest {

    private final OrgResolution resolution;

    @Inject
    public OrgRequest(OrgResolution resolution) {
        this.resolution = resolution;
    }

    /**
     * Runs {@code work} bound to the request's org.
     * <p>
     * When an org is already bound, it is kept and nothing is resolved. That covers
     * an in-process call into an {@link OrgBound} bean from code that bound its org
     * itself: a background job processing one org's item (plan §7.4), deployment, a
     * nested resource call. Resolving again there would fail on a thread without a
     * request, or name a different org than the one already bound. Inside
     * {@link OrgScope#platform} the request's org is resolved and bound, as
     * platform-to-org is allowed.
     */
    public <T> T callBound(Callable<T> work) throws Exception {
        if (OrgContext.current().isPresent()) {
            return work.call();
        }
        return OrgContext.callAs(resolution.resolve(), work);
    }

    /**
     * {@link #callBound} for work that returns nothing and throws no checked
     * exception.
     */
    public void runBound(Runnable work) {
        if (OrgContext.current().isPresent()) {
            work.run();
            return;
        }
        OrgContext.runAs(resolution.resolve(), work);
    }
}
