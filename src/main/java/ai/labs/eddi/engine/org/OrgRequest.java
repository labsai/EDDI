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
     * Runs {@code work} bound to the request's org; a no-op rebind when it is
     * already bound.
     */
    public <T> T callBound(Callable<T> work) throws Exception {
        return OrgContext.callAs(resolution.resolve(), work);
    }

    /**
     * {@link #callBound} for work that returns nothing and throws no checked
     * exception.
     */
    public void runBound(Runnable work) {
        OrgContext.runAs(resolution.resolve(), work);
    }
}
