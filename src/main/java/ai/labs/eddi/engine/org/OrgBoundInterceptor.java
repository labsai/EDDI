/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.Logger;

/**
 * Binds the request's org around a method of an {@link OrgBound} bean.
 * <p>
 * It runs at {@code PLATFORM_BEFORE}, outermost, so every other interceptor and
 * the method itself already see the binding. When an org is already bound, it
 * is kept and nothing is resolved ({@link OrgRequest#callBound}). A method that
 * carries {@link PlatformPlane} itself, a {@code @Scheduled} job on an
 * org-plane resource, runs without a binding. Only that method: ArC intercepts
 * self-invocation, so a non-private method of the same bean that the job calls
 * is bound as usual.
 * <p>
 * The binding ends when the method returns. Work that outlives it
 * ({@code AsyncResponse}, {@code StreamingOutput}, SSE, LLM streaming
 * callbacks) must capture it with {@link OrgPropagation} (plan §6.2).
 * <p>
 * <b>Ordering relative to security.</b> For JAX-RS, Quarkus REST's eager
 * security check runs before any CDI interceptor, so role checks still answer
 * first. For CDI beans such as MCP tool classes, this interceptor runs
 * <em>before</em> {@code RolesAllowedInterceptor} (priority 150) and outside
 * {@code @ActivateRequestContext} ({@code PLATFORM_BEFORE + 100}) and
 * {@code McpErrorResultInterceptor} ({@code PLATFORM_AFTER}). Two things
 * follow:
 * <ul>
 * <li>an {@link OrgResolutionException} on an MCP {@code @Tool} method is
 * turned into the FORBIDDEN tool error here, because the MCP error mapping
 * never sees it;</li>
 * <li>in multi mode, a caller without an org gets that 403 before the role
 * check. A multi-mode resolver must therefore treat an anonymous identity as
 * "no org", and must not rely on a request context the method activates itself;
 * resource and MCP calls already run inside one.</li>
 * </ul>
 * In single mode resolution reads nothing from the request, so none of this
 * applies.
 */
@OrgBound
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE)
public class OrgBoundInterceptor {

    private static final Logger LOGGER = Logger.getLogger(OrgBoundInterceptor.class);
    static final String MCP_FORBIDDEN = "{\"error\":\"" + OrgResolutionException.CLIENT_MESSAGE + "\",\"errorCode\":\"FORBIDDEN\"}";

    private final OrgRequest orgRequest;

    @Inject
    public OrgBoundInterceptor(OrgRequest orgRequest) {
        this.orgRequest = orgRequest;
    }

    @AroundInvoke
    Object bind(InvocationContext context) throws Exception {
        if (context.getMethod().isAnnotationPresent(PlatformPlane.class)) {
            // not request work, such as a @Scheduled job
            return context.proceed();
        }
        try {
            return orgRequest.callBound(() -> {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debugf("Org %s bound for %s.%s", OrgContext.current().map(OrgId::value).orElse("-"),
                            context.getMethod().getDeclaringClass().getSimpleName(), context.getMethod().getName());
                }
                return context.proceed();
            });
        } catch (OrgResolutionException e) {
            LOGGER.warnf("[SECURITY] org resolution refused %s.%s: %s", context.getMethod().getDeclaringClass().getSimpleName(),
                    context.getMethod().getName(), e.detail());
            if (context.getMethod().isAnnotationPresent(Tool.class)) {
                throw new ToolCallException(MCP_FORBIDDEN);
            }
            throw e;
        }
    }
}
