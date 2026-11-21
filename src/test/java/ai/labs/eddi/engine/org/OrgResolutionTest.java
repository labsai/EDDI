/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.inject.Instance;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrgResolutionTest {

    static final OrgId ACME = OrgId.of("acme");

    private Instance<OrgResolver> resolvers;
    private Instance<SecurityIdentity> identity;
    private Instance<CurrentVertxRequest> vertxRequest;
    private OrgResolver multiResolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        resolvers = mock(Instance.class);
        identity = mock(Instance.class);
        vertxRequest = mock(Instance.class);
        multiResolver = mock(OrgResolver.class);
        when(multiResolver.name()).thenReturn("test");
        when(resolvers.stream()).thenAnswer(i -> Stream.of(new SingleOrgResolver(), multiResolver));
    }

    @AfterEach
    void reset() {
        OrgContext.configure(OrgMode.SINGLE, OrgContextObserver.NONE);
    }

    private OrgResolution resolution(boolean requestContextActive) {
        return new OrgResolution(resolvers, identity, vertxRequest, "test", () -> requestContextActive);
    }

    /** A request whose authority and headers the test controls. */
    private RoutingContext request(HostAndPort authority, String... orgHeaders) {
        RoutingContext rc = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        CurrentVertxRequest current = mock(CurrentVertxRequest.class);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        for (String value : orgHeaders) {
            headers.add(OrgRequestFacts.ORG_HEADER, value);
        }
        when(vertxRequest.isResolvable()).thenReturn(true);
        when(vertxRequest.get()).thenReturn(current);
        when(current.getCurrent()).thenReturn(rc);
        when(rc.request()).thenReturn(request);
        when(request.authority()).thenReturn(authority);
        when(request.headers()).thenReturn(headers);
        return rc;
    }

    /**
     * Runs a multi-mode resolution whose resolver applies {@code reader} to the
     * facts.
     */
    private <T> T readFacts(OrgResolution resolution, Function<OrgRequestFacts, T> reader) {
        OrgContext.configure(OrgMode.MULTI, null);
        AtomicReference<T> seen = new AtomicReference<>();
        doAnswer(i -> {
            seen.set(reader.apply(i.getArgument(0)));
            return ACME;
        }).when(multiResolver).resolve(any());
        resolution.resolve();
        return seen.get();
    }

    @Test
    void singleModeIsAlwaysTheDefaultOrgWithoutTouchingTheRequest() {
        assertEquals(OrgId.DEFAULT, resolution(true).resolve());
        verify(multiResolver, never()).resolve(any());
        verify(vertxRequest, never()).get();
    }

    @Test
    void singleResolverIgnoresTheRequest() {
        assertEquals(OrgId.DEFAULT, new SingleOrgResolver().resolve(null));
        assertEquals("single", new SingleOrgResolver().name());
    }

    @Test
    void resolverNameIsNormalisedLikeTheMode() {
        assertEquals("single", new OrgResolution(resolvers, identity, vertxRequest, " Single ").resolverName());
    }

    @Test
    void multiModeAsksTheConfiguredResolver() {
        OrgContext.configure(OrgMode.MULTI, null);
        when(multiResolver.resolve(any())).thenReturn(ACME);

        assertEquals(ACME, resolution(false).resolve());
    }

    @Test
    void aResolverThatReturnsNothingIsA403WithAFixedMessage() {
        OrgContext.configure(OrgMode.MULTI, null);

        OrgResolutionException e = assertThrows(OrgResolutionException.class, () -> resolution(false).resolve());
        assertEquals(OrgResolutionException.CLIENT_MESSAGE, e.getMessage());
    }

    @Test
    void aResolverThatThrowsPropagatesAndMemoisesNothing() {
        OrgContext.configure(OrgMode.MULTI, null);
        RoutingContext rc = request(HostAndPort.create("acme.example.com", 443));
        when(multiResolver.resolve(any())).thenThrow(new OrgResolutionException("claim names globex"));

        OrgResolutionException e = assertThrows(OrgResolutionException.class, () -> resolution(true).resolve());
        assertFalse(e.getMessage().contains("globex"));
        assertEquals("claim names globex", e.detail());
        verify(rc, never()).put(any(), any());
    }

    @Test
    void anUnknownResolverNameFails() {
        assertThrows(IllegalStateException.class, () -> new OrgResolution(resolvers, identity, vertxRequest, "nope").resolver());
    }

    @Test
    void oneRequestResolvesOnceAndTheFactsComeFromTheRequest() {
        RoutingContext rc = request(HostAndPort.create("Acme.Example.COM", 443), "  acme ");
        OrgResolution resolution = resolution(true);

        assertEquals(Optional.of("acme.example.com"), readFacts(resolution, OrgRequestFacts::authority));
        verify(rc).put(eq(OrgResolution.MEMO_KEY), eq(ACME));
        assertEquals(Optional.of("acme"), readFacts(resolution(true), OrgRequestFacts::requestedOrg));

        when(rc.get(OrgResolution.MEMO_KEY)).thenReturn(ACME);
        assertEquals(ACME, resolution.resolve());
        verify(multiResolver, org.mockito.Mockito.times(2)).resolve(any());
    }

    @Test
    void aMissingOrBlankAuthorityIsEmpty() {
        request(null);
        assertEquals(Optional.empty(), readFacts(resolution(true), OrgRequestFacts::authority));
        request(HostAndPort.create("", 80));
        assertEquals(Optional.empty(), readFacts(resolution(true), OrgRequestFacts::authority));
    }

    @Test
    void anAbsentOrBlankOrgHeaderIsEmpty() {
        request(null);
        assertEquals(Optional.empty(), readFacts(resolution(true), OrgRequestFacts::requestedOrg));
        request(null, "   ");
        assertEquals(Optional.empty(), readFacts(resolution(true), OrgRequestFacts::requestedOrg));
    }

    @Test
    void aRepeatedOrgHeaderIsRefused() {
        request(null, "acme", "globex");
        OrgContext.configure(OrgMode.MULTI, null);
        when(multiResolver.resolve(any())).thenAnswer(i -> {
            ((OrgRequestFacts) i.getArgument(0)).requestedOrg();
            return ACME;
        });

        assertThrows(OrgResolutionException.class, () -> resolution(true).resolve());
    }

    @Test
    void outsideARequestContextTheIdentityIsAnonymousWithoutTouchingTheProxy() {
        SecurityIdentity seen = readFacts(resolution(false), OrgRequestFacts::identity);

        assertTrue(seen.isAnonymous());
        verify(identity, never()).get();
    }

    @Test
    void insideARequestContextTheIdentityIsTheInjectedOne() {
        SecurityIdentity caller = mock(SecurityIdentity.class);
        when(identity.get()).thenReturn(caller);

        assertSame(caller, readFacts(resolution(true), OrgRequestFacts::identity));
    }

    // ------------------------------------------------------------ interceptor

    /** Stand-ins whose annotations the interceptor reads. */
    static class Endpoints {
        @Tool(description = "test")
        public String tool() {
            return "ok";
        }

        public String rest() {
            return "ok";
        }
    }

    private static InvocationContext invocation(String method) throws Exception {
        Method m = Endpoints.class.getMethod(method);
        InvocationContext invocation = mock(InvocationContext.class);
        when(invocation.getMethod()).thenReturn(m);
        when(invocation.proceed()).thenAnswer(i -> OrgContext.require());
        return invocation;
    }

    @Test
    void interceptorBindsTheResolvedOrgAroundTheMethod() throws Exception {
        OrgContext.configure(OrgMode.MULTI, null);
        OrgResolution resolution = mock(OrgResolution.class);
        when(resolution.resolve()).thenReturn(ACME);

        Object result = new OrgBoundInterceptor(new OrgRequest(resolution)).bind(invocation("rest"));

        assertEquals(ACME, result);
        assertEquals(Optional.empty(), OrgContext.current());
    }

    @Test
    void anUnresolvedOrgOnAnMcpToolBecomesTheForbiddenToolError() throws Exception {
        OrgResolution resolution = mock(OrgResolution.class);
        when(resolution.resolve()).thenThrow(new OrgResolutionException("claim names globex"));
        OrgBoundInterceptor interceptor = new OrgBoundInterceptor(new OrgRequest(resolution));

        ToolCallException tool = assertThrows(ToolCallException.class, () -> interceptor.bind(invocation("tool")));
        assertEquals(OrgBoundInterceptor.MCP_FORBIDDEN, tool.getMessage());
        assertFalse(tool.getMessage().contains("globex"));

        OrgResolutionException rest = assertThrows(OrgResolutionException.class, () -> interceptor.bind(invocation("rest")));
        assertEquals(OrgResolutionException.CLIENT_MESSAGE, rest.getMessage());
    }

    @Test
    void orgRequestRunBoundBindsTheRequestOrg() {
        OrgResolution resolution = mock(OrgResolution.class);
        when(resolution.resolve()).thenReturn(OrgId.DEFAULT);
        AtomicReference<Optional<OrgId>> seen = new AtomicReference<>();

        new OrgRequest(resolution).runBound(() -> seen.set(OrgContext.current()));

        assertEquals(Optional.of(OrgId.DEFAULT), seen.get());
    }

    @Test
    void quotaKeyIsTheConfiguredTenantInSingleModeAndTheOrgInMulti() throws Exception {
        OrgQuotaKeys keys = new OrgQuotaKeys("tenant-x");
        assertEquals("tenant-x", keys.keyFor(OrgId.DEFAULT));
        assertEquals("tenant-x", OrgContext.callAs(OrgId.DEFAULT, keys::current));

        OrgContext.configure(OrgMode.MULTI, null);
        assertEquals("acme", keys.keyFor(ACME));
        assertEquals("acme", OrgContext.callAs(ACME, keys::current));
    }
}
