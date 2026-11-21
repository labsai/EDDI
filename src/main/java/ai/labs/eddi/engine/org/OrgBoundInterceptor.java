/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * Binds the request's org around a method of an {@link OrgBound} bean.
 * <p>
 * It runs at {@code PLATFORM_BEFORE}, outermost, so every other interceptor
 * (including {@code McpErrorResultInterceptor} at {@code PLATFORM_AFTER}) and
 * the method itself already see the binding. When the same org is already bound
 * it adds nothing.
 * <p>
 * The binding ends when the method returns. Work that outlives it
 * ({@code AsyncResponse}, {@code StreamingOutput}, SSE, LLM streaming
 * callbacks) must capture it with {@link OrgPropagation} (plan §6.2).
 * <p>
 * At {@code PLATFORM_BEFORE} it also runs outside
 * {@code @ActivateRequestContext} ({@code PLATFORM_BEFORE + 100}). That is
 * harmless in single mode, which reads nothing from the request. A multi-mode
 * resolver must not rely on a request context the annotated method activates
 * itself: resource and MCP calls already run inside one.
 */
@OrgBound
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE)
public class OrgBoundInterceptor {

    private final OrgRequest orgRequest;

    @Inject
    public OrgBoundInterceptor(OrgRequest orgRequest) {
        this.orgRequest = orgRequest;
    }

    @AroundInvoke
    Object bind(InvocationContext context) throws Exception {
        return orgRequest.callBound(context::proceed);
    }
}
