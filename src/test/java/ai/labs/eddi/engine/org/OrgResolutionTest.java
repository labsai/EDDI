/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.inject.Instance;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    void singleModeIsAlwaysTheDefaultOrgWithoutTouchingTheRequest() {
        OrgResolution resolution = new OrgResolution(resolvers, identity, vertxRequest, "test");

        assertEquals(OrgId.DEFAULT, resolution.resolve());
        verify(multiResolver, never()).resolve(any());
        verify(vertxRequest, never()).get();
    }

    @Test
    void singleResolverIgnoresTheRequest() {
        assertEquals(OrgId.DEFAULT, new SingleOrgResolver().resolve(null));
        assertEquals("single", new SingleOrgResolver().name());
    }

    @Test
    void multiModeAsksTheConfiguredResolver() {
        OrgContext.configure(OrgMode.MULTI, null);
        when(vertxRequest.isResolvable()).thenReturn(false);
        when(multiResolver.resolve(any())).thenReturn(ACME);

        assertEquals(ACME, new OrgResolution(resolvers, identity, vertxRequest, "test").resolve());
    }

    @Test
    void aResolverThatReturnsNothingIsA403NotAFallback() {
        OrgContext.configure(OrgMode.MULTI, null);
        when(vertxRequest.isResolvable()).thenReturn(false);

        assertThrows(OrgResolutionException.class, () -> new OrgResolution(resolvers, identity, vertxRequest, "test").resolve());
    }

    @Test
    void anUnknownResolverNameFails() {
        assertThrows(IllegalStateException.class, () -> new OrgResolution(resolvers, identity, vertxRequest, "nope").resolver());
    }

    @Test
    void oneRequestResolvesOnceAndTheFactsComeFromTheRequest() {
        OrgContext.configure(OrgMode.MULTI, null);
        RoutingContext rc = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        CurrentVertxRequest current = mock(CurrentVertxRequest.class);
        when(vertxRequest.isResolvable()).thenReturn(true);
        when(vertxRequest.get()).thenReturn(current);
        when(current.getCurrent()).thenReturn(rc);
        when(rc.request()).thenReturn(request);
        when(request.authority()).thenReturn(HostAndPort.create("Acme.Example.COM", 443));
        when(request.getHeader(OrgRequestFacts.ORG_HEADER)).thenReturn("  acme ");
        AtomicReference<OrgRequestFacts> facts = new AtomicReference<>();
        when(multiResolver.resolve(any())).thenAnswer(i -> {
            OrgRequestFacts f = i.getArgument(0);
            facts.set(f);
            return ACME;
        });

        OrgResolution resolution = new OrgResolution(resolvers, identity, vertxRequest, "test");
        assertEquals(ACME, resolution.resolve());

        assertEquals(Optional.of("acme.example.com"), facts.get().authority());
        assertEquals(Optional.of("acme"), facts.get().requestedOrg());
        verify(rc).put(eq(OrgResolution.MEMO_KEY), eq(ACME));

        when(rc.get(OrgResolution.MEMO_KEY)).thenReturn(ACME);
        assertEquals(ACME, resolution.resolve());
        verify(multiResolver).resolve(any());
    }

    @Test
    void interceptorBindsTheResolvedOrgAroundTheMethod() throws Exception {
        OrgContext.configure(OrgMode.MULTI, null);
        OrgResolution resolution = mock(OrgResolution.class);
        when(resolution.resolve()).thenReturn(ACME);
        InvocationContext invocation = mock(InvocationContext.class);
        when(invocation.proceed()).thenAnswer(i -> OrgContext.require());

        Object result = new OrgBoundInterceptor(new OrgRequest(resolution)).bind(invocation);

        assertEquals(ACME, result);
        assertEquals(Optional.empty(), OrgContext.current());
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
