/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.rest;

import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
import ai.labs.eddi.connections.ConnectionRegistry;
import ai.labs.eddi.connections.ConnectionsConfig;
import ai.labs.eddi.connections.CredentialReferenceResolver;
import ai.labs.eddi.connections.grants.IConnectionGrantStore;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.connections.oauth.CredentialEndpointAllowlist;
import ai.labs.eddi.connections.oauth.IOAuthStateStore;
import ai.labs.eddi.connections.oauth.OAuthState;
import ai.labs.eddi.connections.oauth.OAuthTokenClient;
import ai.labs.eddi.connections.oauth.OAuthTokenService;
import ai.labs.eddi.connections.oauth.TokenResponse;
import ai.labs.eddi.datastore.IResourceStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The OAuth callback, exercised through the flow that produces it.
 * <p>
 * Every test here starts a real {@code authorize} call and feeds the state row
 * and the {@code Set-Cookie} it actually issued back into {@code callback}.
 * That is deliberate: the defects these tests exist for are all <em>wiring</em>
 * defects — a nonce hash that is never stored, a {@code returnTo} that is never
 * validated, a metric emitted with two different tag shapes — and every one of
 * them survives a test that hand-builds the state row the way the callback
 * happens to want it.
 * <p>
 * The two properties worth stating up front, because most of the file serves
 * them:
 * <ul>
 * <li>nothing that happens <em>after</em> the grant is stored may throw. By the
 * time the success counter is incremented the user's tokens are in the database
 * and their single-use state is consumed, so a 500 there is a link they cannot
 * retry and cannot see.</li>
 * <li>a callback the browser cannot prove it started must persist
 * <em>nothing</em>. The refusal on its own is worth little — code that did
 * nothing at all would also refuse — so each refusal test asserts the absence
 * of a grant next to a positive assertion, and the success test above it is the
 * control proving the same harness does persist when the binding holds.</li>
 * </ul>
 */
class RestConnectionAuthorizationCallbackTest {

    private static final String TENANT = ConnectionReference.DEFAULT_TENANT;
    private static final String CONNECTION_NAME = "drive";
    private static final String PRINCIPAL = "alice";
    private static final String CODE = "provider-authorization-code";
    private static final String CLIENT_SECRET = "resolved-client-secret";
    private static final String REFRESH_TOKEN = "refresh-token-value";
    private static final String CALLBACK_METRIC = "eddi.connection.oauth.callback.count";

    private static final TokenResponse TOKEN = new TokenResponse("access-token-value", REFRESH_TOKEN, Duration.ofHours(1),
            List.of("https://www.googleapis.com/auth/drive.readonly"));

    /** A deployment whose public base URL is well-formed; the ordinary case. */
    private static final ConnectionsConfig CONFIG = new ConnectionsConfig(true, Optional.of("https://eddi.example.com"));

    private ConnectionRegistry connectionRegistry;
    private IOAuthStateStore stateStore;
    private IConnectionGrantStore grantStore;
    private OAuthTokenClient tokenClient;
    private OAuthTokenService tokenService;
    private CredentialEndpointAllowlist endpointAllowlist;
    private SecurityIdentity securityIdentity;
    private Principal principal;
    private CredentialReferenceResolver credentialReferenceResolver;
    private IConnectionStore connectionStore;
    private ConnectionConfiguration connection;

    private static final String CONNECTION_ID = "68a1b2c3d4e5f60718293a4b";

    /** A different connection that later takes the same name. */
    private static final String REPLACEMENT_ID = "79b2c3d4e5f60718293a4b5c";

    /** Which connection id the name resolves to in the store, right now. */
    private final AtomicReference<String> nameHolder = new AtomicReference<>();

    /**
     * The principal each stored grant was filed under, in order. Recorded rather
     * than only verified, so "nothing was stored" is an assertion with a message
     * attached instead of a bare {@code never()}.
     */
    private final List<String> grantsStoredFor = new ArrayList<>();

    /** The authorization codes actually sent to the provider's token endpoint. */
    private final List<String> codesRedeemed = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        grantsStoredFor.clear();
        codesRedeemed.clear();
        connectionRegistry = mock(ConnectionRegistry.class);
        stateStore = mock(IOAuthStateStore.class);
        grantStore = mock(IConnectionGrantStore.class);
        tokenClient = mock(OAuthTokenClient.class);
        tokenService = mock(OAuthTokenService.class);
        endpointAllowlist = mock(CredentialEndpointAllowlist.class);
        securityIdentity = mock(SecurityIdentity.class);
        principal = mock(Principal.class);
        credentialReferenceResolver = mock(CredentialReferenceResolver.class);
        connection = connection();

        doReturn(principal).when(securityIdentity).getPrincipal();
        doReturn(PRINCIPAL).when(principal).getName();
        doReturn(connection).when(connectionRegistry).require(new ConnectionReference(TENANT, CONNECTION_NAME));
        doReturn(CLIENT_SECRET).when(credentialReferenceResolver).resolveRequired(anyString(), anyString(), anyString());
        // Stubbed permissively so a wrong argument shows up as a failed verify below
        // rather than as a NullPointerException three frames away.
        doAnswer(invocation -> {
            codesRedeemed.add(invocation.getArgument(2));
            return TOKEN;
        }).when(tokenClient).authorizationCode(any(), any(), any(), any(), any());
        doAnswer(invocation -> {
            grantsStoredFor.add(invocation.getArgument(2));
            return null;
        }).when(tokenService).persistNew(any(), any(), any(), any(), any());
        // The uncached reads — at authorize, before the exchange and after the grant
        // is written: by default the name stays with the connection the flow was
        // started for, which is every test's ordinary case.
        connectionStore = mock(IConnectionStore.class);
        nameHolder.set(CONNECTION_ID);
        doAnswer(invocation -> nameHolder.get()).when(connectionStore).idOfName(TENANT, CONNECTION_NAME);
        doReturn(currentVersion(1)).when(connectionStore).getCurrentResourceId(CONNECTION_ID);
        doReturn(connection).when(connectionStore).read(CONNECTION_ID, 1);
    }

    private static IResourceStore.IResourceId currentVersion(int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return CONNECTION_ID;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    private static ConnectionConfiguration connection() {
        var connection = new ConnectionConfiguration();
        connection.setName(CONNECTION_NAME);
        connection.setTenantId(TENANT);
        connection.setAuthType(AuthType.OAUTH2_AUTHORIZATION_CODE);
        connection.setBinding(Binding.PER_USER);
        connection.setBaseUrlAllowlist(List.of("https://www.googleapis.com"));
        var oauth = new OAuthConfig();
        oauth.setAuthorizationUrl("https://accounts.example.com/authorize");
        oauth.setTokenUrl("https://accounts.example.com/token");
        oauth.setClientId("client-id");
        oauth.setClientSecret("${vault:drive-client-secret}");
        oauth.setScopes(List.of("https://www.googleapis.com/auth/drive.readonly"));
        connection.setOauth(oauth);
        return connection;
    }

    private RestConnectionAuthorization resource(MeterRegistry meterRegistry) {
        return resource(meterRegistry, CONFIG);
    }

    private RestConnectionAuthorization resource(MeterRegistry meterRegistry, ConnectionsConfig connectionsConfig) {
        return new RestConnectionAuthorization(connectionRegistry, stateStore, grantStore, tokenClient, tokenService, endpointAllowlist,
                connectionsConfig, securityIdentity, credentialReferenceResolver, meterRegistry, connectionStore);
    }

    /**
     * The state row {@code authorize} wrote and the cookie it handed the browser.
     */
    private record StartedFlow(OAuthState row, NewCookie cookie) {
    }

    /**
     * Runs a real authorization request and wires its state into the store, so the
     * callback under test consumes exactly what the same class just issued.
     */
    private StartedFlow startFlow(RestConnectionAuthorization resource, String returnTo) {
        Response response = resource.authorize(CONNECTION_NAME, returnTo);
        assertEquals(200, response.getStatus(), "authorize must hand the browser an authorization URL before any callback can be tested");

        var captor = ArgumentCaptor.forClass(OAuthState.class);
        verify(stateStore, atLeastOnce()).create(captor.capture());
        OAuthState row = captor.getValue();

        NewCookie cookie = response.getCookies().get(RestConnectionAuthorization.COOKIE_PREFIX + row.getState());
        assertNotNull(cookie, "authorize must set the browser-binding cookie for this state; without it no legitimate callback can ever be "
                + "accepted, and the flow is broken for every user");
        assertNotNull(row.getNonceHash(), "the state row must carry the nonce hash, or the cookie the browser was just given proves nothing");

        when(stateStore.claim(row.getState())).thenReturn(Optional.of(row));
        return new StartedFlow(row, cookie);
    }

    private static HttpHeaders browserWith(NewCookie issued) {
        return browserWith(issued.getName(), issued.getValue());
    }

    private static HttpHeaders browserWith(String cookieName, String cookieValue) {
        HttpHeaders headers = mock(HttpHeaders.class);
        doReturn(Map.of(cookieName, new Cookie.Builder(cookieName).value(cookieValue).build())).when(headers).getCookies();
        return headers;
    }

    /** A browser that never held the nonce — a link opened somewhere else. */
    private static HttpHeaders browserWithNoCookies() {
        HttpHeaders headers = mock(HttpHeaders.class);
        doReturn(Map.of()).when(headers).getCookies();
        return headers;
    }

    /**
     * Runs {@code meanwhile} as the grant is written — after the pre-exchange
     * check, before the post-write re-read — which is the window the re-read exists
     * for.
     */
    private void onGrantStored(Runnable meanwhile) {
        doAnswer(invocation -> {
            grantsStoredFor.add(invocation.getArgument(2));
            meanwhile.run();
            return null;
        }).when(tokenService).persistNew(any(), any(), any(), any(), any());
    }

    private void assertNothingWasStored(String because) {
        assertEquals(List.of(), codesRedeemed, because + " — the authorization code must not even be redeemed");
        assertEquals(List.of(), grantsStoredFor, because);
    }

    // ── the metric crash ─────────────────────────────────────────────────────

    @Test
    @DisplayName("the callback counter carries the same tag keys whether or not a connection was resolved")
    void callbackMetricHasOneTagShape() {
        // A meter name registered with two different tag KEY sets is a registration
        // failure in Prometheus, which is the registry Quarkus builds. The callback
        // emits this meter both with a connection in hand (success) and without one
        // (an unknown state), and those two shapes are exactly what collided.
        var registry = new SimpleMeterRegistry();
        RestConnectionAuthorization resource = resource(registry);

        StartedFlow flow = startFlow(resource, "/manage/connections");
        resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

        when(stateStore.claim("state-nobody-issued")).thenReturn(Optional.empty());
        resource.callback(CODE, "state-nobody-issued", null, browserWithNoCookies());

        Set<String> outcomes = registry.getMeters().stream().filter(meter -> CALLBACK_METRIC.equals(meter.getId().getName()))
                .map(meter -> meter.getId().getTag("outcome")).collect(Collectors.toSet());
        assertEquals(Set.of("success", "bad_state"), outcomes,
                "both callback outcomes must actually be recorded, or the tag-shape check below is comparing nothing");

        Set<Set<String>> tagShapes = registry.getMeters().stream().filter(meter -> CALLBACK_METRIC.equals(meter.getId().getName()))
                .map(meter -> meter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet())).collect(Collectors.toSet());
        assertEquals(Set.of(Set.of("outcome", "authType")), tagShapes,
                "every emission of '" + CALLBACK_METRIC + "' must use one set of tag keys; a second shape is a Prometheus registration "
                        + "failure that only ever fires in production, once both paths have been taken by the same process");
    }

    @Test
    @DisplayName("a failure after the state is claimed that is not a ConnectionException still ends in the documented 303")
    void unexpectedExchangeFailureStillRedirects() {
        // UrlValidationUtils throws IllegalArgumentException for a private token host,
        // a grant store throws IllegalStateException when it cannot write. Both used
        // to escape as a 500 to a browser the provider just redirected — with the
        // single-use state consumed, so the user could not even retry.
        for (RuntimeException failure : List.of(new IllegalArgumentException("URL resolves to a private/internal address"),
                new IllegalStateException("Failed to write connection grant"))) {
            var registry = new SimpleMeterRegistry();
            RestConnectionAuthorization resource = resource(registry);
            StartedFlow flow = startFlow(resource, "/manage/connections");
            doAnswer(invocation -> {
                throw failure;
            }).when(tokenClient).authorizationCode(any(), any(), any(), any(), any());

            Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

            assertEquals(303, response.getStatus(), failure.getClass().getSimpleName() + " must not become a 500 on a permit path");
            assertEquals(URI.create("/manage/connections?error=exchange_failed"), response.getLocation(),
                    "the browser must be told the exchange failed, exactly as for a provider refusal");
            Counter counted = registry.find(CALLBACK_METRIC).tag("outcome", "exchange_failed").counter();
            assertTrue(counted != null && counted.count() == 1, "the failure must be counted as exchange_failed");
            assertEquals(List.of(), grantsStoredFor, "nothing was stored");
        }
    }

    @Test
    @DisplayName("a grant store that throws after the provider minted a token still redirects rather than 500ing")
    void storeFailureAfterMintingStillRedirects() {
        RestConnectionAuthorization resource = resource(new SimpleMeterRegistry());
        StartedFlow flow = startFlow(resource, "/manage/connections");
        doAnswer(invocation -> {
            throw new IllegalStateException("Failed to write connection grant");
        }).when(tokenService).persistNew(any(), any(), any(), any(), any());

        Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

        assertEquals(List.of(CODE), codesRedeemed, "the token WAS minted at the provider before the store failed");
        assertEquals(303, response.getStatus());
        assertEquals(URI.create("/manage/connections?error=exchange_failed"), response.getLocation(),
                "the user has to be told to start again — the minted token was never stored");
    }

    // ── a connection that changes shape while an account is being linked ─────

    @Test
    @DisplayName("an update that landed before the post-write re-read: the grant just stored is deleted and the link reports exchange_failed")
    void grantStoredUnderAShapeTheConnectionNoLongerHasIsDeleted() throws Exception {
        // The interleaving the update cannot see: its second count ran before this
        // grant was written, so its own write preceded the re-read here, and the
        // re-read is what has to catch it.
        var registry = new SimpleMeterRegistry();
        RestConnectionAuthorization resource = resource(registry);
        StartedFlow flow = startFlow(resource, "/manage/connections");
        ConnectionConfiguration reshaped = connection();
        reshaped.setAuthType(AuthType.OAUTH2_CLIENT_CREDENTIALS);
        reshaped.setBinding(Binding.SERVICE);
        doReturn(reshaped).when(connectionStore).read(CONNECTION_ID, 1);

        Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

        var order = inOrder(tokenService, connectionStore, grantStore);
        order.verify(tokenService).persistNew(any(), any(), any(), any(), any());
        order.verify(connectionStore).read(CONNECTION_ID, 1);
        order.verify(grantStore).delete(TENANT, CONNECTION_NAME, PRINCIPAL);
        assertEquals(303, response.getStatus());
        assertEquals(URI.create("/manage/connections?error=exchange_failed"), response.getLocation(),
                "an existing error code the Manager already maps — the link did not produce a usable account");
        Counter failed = registry.find(CALLBACK_METRIC).tag("outcome", "exchange_failed").counter();
        assertTrue(failed != null && failed.count() == 1, "the discarded link must be counted as exchange_failed");
        assertEquals(null, registry.find(CALLBACK_METRIC).tag("outcome", "success").counter(), "and not as a success");
    }

    @Test
    @DisplayName("a connection deleted while the account was being linked: the grant just stored is deleted")
    void grantForADeletedConnectionIsDeleted() throws Exception {
        RestConnectionAuthorization resource = resource(new SimpleMeterRegistry());
        StartedFlow flow = startFlow(resource, "/manage/connections");
        onGrantStored(() -> nameHolder.set(null));

        Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

        verify(grantStore).delete(TENANT, CONNECTION_NAME, PRINCIPAL);
        assertEquals(URI.create("/manage/connections?error=exchange_failed"), response.getLocation());
    }

    @Test
    @DisplayName("a re-read that fails cannot vouch for the grant, so the grant is deleted rather than left unreadable")
    void grantIsDeletedWhenTheReReadFails() throws Exception {
        RestConnectionAuthorization resource = resource(new SimpleMeterRegistry());
        StartedFlow flow = startFlow(resource, "/manage/connections");
        // The pre-exchange read succeeds; the post-write re-read is the one that fails.
        doReturn(connection).doThrow(new IResourceStore.ResourceStoreException("blinked")).when(connectionStore).read(CONNECTION_ID, 1);

        Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

        assertEquals(List.of(PRINCIPAL), grantsStoredFor, "the grant was stored before the re-read failed");
        verify(grantStore).delete(TENANT, CONNECTION_NAME, PRINCIPAL);
        assertEquals(URI.create("/manage/connections?error=exchange_failed"), response.getLocation());
    }

    @Test
    @DisplayName("a re-read that still finds the same PER_USER authorization-code connection under the name keeps the grant")
    void grantIsKeptWhenTheConnectionIsUnchanged() throws Exception {
        var registry = new SimpleMeterRegistry();
        RestConnectionAuthorization resource = resource(registry);
        StartedFlow flow = startFlow(resource, "/manage/connections");

        Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

        // Once at authorize, once before the exchange, once after the grant is written.
        verify(connectionStore, times(3)).idOfName(TENANT, CONNECTION_NAME);
        verify(connectionStore, times(2)).read(CONNECTION_ID, 1);
        verify(grantStore, never()).delete(anyString(), anyString(), anyString());
        assertEquals(URI.create("/manage/connections?connected=drive"), response.getLocation());
        Counter succeeded = registry.find(CALLBACK_METRIC).tag("outcome", "success").counter();
        assertTrue(succeeded != null && succeeded.count() == 1, "an unchanged binding is an ordinary success");
    }

    // ── a connection replaced under the same name while an account is being linked

    @Test
    @DisplayName("authorize binds the state to the resource id of the connection it was started for")
    void authorizeBindsTheStateToTheConnectionId() {
        StartedFlow flow = startFlow(resource(new SimpleMeterRegistry()), "/manage/connections");

        assertEquals(CONNECTION_ID, flow.row().getConnectionId(),
                "without the id, a connection re-created under the same name is indistinguishable from the original at callback time");
    }

    @Test
    @DisplayName("authorize refuses a connection the uncached store no longer has, and issues no state")
    void authorizeRefusesAConnectionTheStoreNoLongerHas() {
        RestConnectionAuthorization resource = resource(new SimpleMeterRegistry());
        nameHolder.set(null);

        assertThrows(NotFoundException.class, () -> resource.authorize(CONNECTION_NAME, "/manage/connections"));
        verify(stateStore, never()).create(any());
    }

    @Test
    @DisplayName("a connection deleted, or replaced under the same name, before the callback: the code is not redeemed")
    void replacementBeforeTheExchangeRedeemsNothing() throws Exception {
        for (String holder : Arrays.asList(null, REPLACEMENT_ID)) {
            nameHolder.set(CONNECTION_ID);
            var registry = new SimpleMeterRegistry();
            RestConnectionAuthorization resource = resource(registry);
            StartedFlow flow = startFlow(resource, "/manage/connections");
            // The replacement is exactly the kind of connection that would accept the
            // grant: only its id tells it apart from the original.
            nameHolder.set(holder);
            doReturn(currentVersion(1)).when(connectionStore).getCurrentResourceId(REPLACEMENT_ID);
            doReturn(connection()).when(connectionStore).read(REPLACEMENT_ID, 1);

            Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

            assertNothingWasStored("the name now belongs to " + holder + ", not to the connection the flow was started for");
            assertEquals(URI.create("/manage/connections?error=connection_removed"), response.getLocation(), "holder " + holder);
            Counter failed = registry.find(CALLBACK_METRIC).tag("outcome", "exchange_failed").counter();
            assertTrue(failed != null && failed.count() == 1, "the refusal must be counted, holder " + holder);
        }
        verify(connectionStore, never()).read(REPLACEMENT_ID, 1);
        verify(grantStore, never()).delete(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a connection replaced under the same name after the grant is written: the grant is discarded")
    void replacementAfterTheGrantIsStoredDiscardsIt() throws Exception {
        var registry = new SimpleMeterRegistry();
        RestConnectionAuthorization resource = resource(registry);
        StartedFlow flow = startFlow(resource, "/manage/connections");
        // Same name, same shape — PER_USER authorization-code — so the shape check
        // alone would keep the grant. Only the id catches it.
        doReturn(currentVersion(1)).when(connectionStore).getCurrentResourceId(REPLACEMENT_ID);
        doReturn(connection()).when(connectionStore).read(REPLACEMENT_ID, 1);
        onGrantStored(() -> nameHolder.set(REPLACEMENT_ID));

        Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

        assertEquals(List.of(CODE), codesRedeemed, "the pre-exchange check passed: the replacement landed afterwards");
        var order = inOrder(tokenService, grantStore);
        order.verify(tokenService).persistNew(any(), any(), any(), any(), any());
        order.verify(grantStore).delete(TENANT, CONNECTION_NAME, PRINCIPAL);
        assertEquals(URI.create("/manage/connections?error=exchange_failed"), response.getLocation());
        Counter failed = registry.find(CALLBACK_METRIC).tag("outcome", "exchange_failed").counter();
        assertTrue(failed != null && failed.count() == 1, "the discarded link must be counted as exchange_failed");
        assertEquals(null, registry.find(CALLBACK_METRIC).tag("outcome", "success").counter(), "and not as a success");
    }

    @Test
    @DisplayName("a state row written before states carried a connection id is refused as an invalid state")
    void stateWithoutAConnectionIdIsRefused() throws Exception {
        var registry = new SimpleMeterRegistry();
        RestConnectionAuthorization resource = resource(registry);
        StartedFlow flow = startFlow(resource, "/manage/connections");
        flow.row().setConnectionId(null);

        Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

        assertEquals(URI.create("/manage/connections?error=invalid_state"), response.getLocation(),
                "answered as any other unusable state; the user starts again");
        assertNothingWasStored("a row that cannot say which connection it was started for must not produce a grant");
        Counter bad = registry.find(CALLBACK_METRIC).tag("outcome", "bad_state").counter();
        assertTrue(bad != null && bad.count() == 1, "counted as bad_state");
        verify(connectionStore, never()).getCurrentResourceId(anyString());
    }

    @Test
    @DisplayName("a metrics registry that throws cannot abort a callback that has already stored the grant")
    void meterFailureCannotUndoACompletedLink() {
        // The original defect threw AFTER persistNew: the tokens were in the database
        // and the user got a 500 with a consumed state, so retrying was impossible and
        // nothing on their screen said the link had in fact worked.
        RestConnectionAuthorization resource = resource(new ExplodingMeterRegistry());
        StartedFlow flow = startFlow(resource, "/manage/connections");

        Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

        assertEquals(List.of(PRINCIPAL), grantsStoredFor, "the grant is stored before the counter is touched, which is what makes a "
                + "throwing registry unrecoverable rather than merely untidy");
        verify(tokenService).persistNew(same(connection), eq(TENANT), eq(PRINCIPAL), same(TOKEN), eq(REFRESH_TOKEN));
        assertEquals(303, response.getStatus(), "an instrumentation failure must not stop the browser being redirected");
        assertEquals(URI.create("/manage/connections?connected=drive"), response.getLocation(),
                "the user must still land on the page that shows the account they just linked");
    }

    // ── the browser binding ──────────────────────────────────────────────────

    @Test
    @DisplayName("a callback carrying the issued nonce cookie stores the grant under the principal named in the claimed row")
    void matchingCookieCompletesTheFlow() {
        RestConnectionAuthorization resource = resource(new SimpleMeterRegistry());
        StartedFlow flow = startFlow(resource, "/manage/connections");

        // The identity the callback request happens to carry is deliberately somebody
        // else: the redirect is a permit path, and reading the owner from anywhere but
        // the claimed row is how one user's tokens end up filed under another's name.
        doReturn("mallory").when(principal).getName();

        Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

        assertEquals(List.of(PRINCIPAL), grantsStoredFor,
                "the grant must be filed under the principal in the claimed row (alice), not under whoever the callback request looks like "
                        + "(mallory) — the row is the only trustworthy identity on a permit path");
        verify(tokenClient).authorizationCode(same(connection), eq(CLIENT_SECRET), eq(CODE), eq(flow.row().getCodeVerifier()),
                eq(flow.row().getRedirectUri()));
        verify(tokenService).persistNew(same(connection), eq(TENANT), eq(PRINCIPAL), same(TOKEN), eq(REFRESH_TOKEN));
        assertEquals(303, response.getStatus(), "a completed link redirects the browser back to the page it came from");
        assertEquals(URI.create("/manage/connections?connected=drive"), response.getLocation(),
                "the outcome must be reported on the returnTo the flow was started with");

        NewCookie cleared = response.getCookies().get(RestConnectionAuthorization.COOKIE_PREFIX + flow.row().getState());
        assertNotNull(cleared, "the binding cookie must be expired once its flow is over, win or lose");
        assertEquals(0, cleared.getMaxAge(), "a nonce cookie left behind outlives the single-use state it was bound to");
    }

    @Test
    @DisplayName("the binding cookie is scoped so a real browser sends it back on the provider's redirect")
    void bindingCookieIsUsableByABrowser() {
        // Every other test in this file hands the cookie back by hand, so none of them
        // would notice a cookie a browser would refuse to send — and the symptom of
        // that is not a test failure, it is every legitimate link failing in
        // production with "invalid_state".
        RestConnectionAuthorization resource = resource(new SimpleMeterRegistry());
        NewCookie issued = startFlow(resource, "/manage/connections").cookie();

        assertTrue(issued.isHttpOnly(), "a nonce readable by script is not evidence of anything");
        assertTrue(issued.isSecure(), "this deployment's base URL is https, so the nonce must never travel in clear");
        assertEquals("/connections", issued.getPath(), "the cookie is scoped to the flow that needs it and nothing else");
        assertEquals(NewCookie.SameSite.LAX, issued.getSameSite(),
                "the callback arrives as a top-level GET from the provider's origin: Strict would suppress the cookie on every legitimate "
                        + "redirect, so the flow would fail for everyone while looking like an attack in the log");
        assertEquals((int) RestConnectionAuthorization.STATE_TTL.toSeconds(), issued.getMaxAge(),
                "the cookie must outlive the consent screen it is waiting for, and no longer than the state it is bound to");

        // Secure follows the deployment's own base URL rather than being hardcoded,
        // or a plain-HTTP development instance never receives the cookie at all and
        // account linking cannot be tried locally.
        var devResource = resource(new SimpleMeterRegistry(), new ConnectionsConfig(true, Optional.of("http://localhost:7070")));
        assertFalse(startFlow(devResource, "/manage/connections").cookie().isSecure(),
                "a plain-HTTP development deployment must still be able to complete a flow");
    }

    @Test
    @DisplayName("a callback with no binding cookie is refused and stores nothing")
    void missingCookieStoresNothing() {
        RestConnectionAuthorization resource = resource(new SimpleMeterRegistry());
        StartedFlow flow = startFlow(resource, "/manage/connections");

        Response response = resource.callback(CODE, flow.row().getState(), null, browserWithNoCookies());

        assertEquals(303, response.getStatus(), "the browser is redirected rather than shown an error page");
        assertEquals(URI.create("/manage/connections?error=invalid_state"), response.getLocation(),
                "an unbound callback must be reported as an invalid state and nothing more specific");
        // The refusal alone would pass against code that did nothing at all; the
        // control is matchingCookieCompletesTheFlow(), which persists through this
        // very same harness.
        assertNothingWasStored("a callback the browser cannot prove it started must not redeem the code or file a grant");
        // Claimed anyway, and deliberately: the state is single-use whichever way the
        // binding check goes, so a failed binding cannot be retried with it.
        verify(stateStore).claim(flow.row().getState());
    }

    @Test
    @DisplayName("a callback carrying somebody else's nonce is refused and stores nothing")
    void wrongCookieStoresNothing() {
        RestConnectionAuthorization resource = resource(new SimpleMeterRegistry());
        StartedFlow flow = startFlow(resource, "/manage/connections");

        // Right cookie name, wrong value: what an attacker can arrange by setting a
        // cookie of their own choosing, having never seen the one EDDI issued.
        Response response = resource.callback(CODE, flow.row().getState(), null,
                browserWith(flow.cookie().getName(), "a-nonce-the-attacker-picked"));

        assertEquals(303, response.getStatus(), "the browser is redirected rather than shown an error page");
        assertEquals(URI.create("/manage/connections?error=invalid_state"), response.getLocation(),
                "a mismatched nonce must be reported exactly as an invalid state is");
        assertNothingWasStored("a nonce that does not match the one issued must not produce a grant");
        assertNotEquals(flow.cookie().getValue(), "a-nonce-the-attacker-picked",
                "the test is only meaningful while the guessed value differs from the issued one");
    }

    @Test
    @DisplayName("a state row with no stored nonce hash is refused rather than grandfathered")
    void legacyRowWithoutNonceHashIsRefused() {
        // A flow started before the binding existed lives at most one state TTL past
        // an upgrade. Accepting it buys ten minutes of convenience and leaves the hole
        // open on precisely the deployments that just patched it.
        RestConnectionAuthorization resource = resource(new SimpleMeterRegistry());
        StartedFlow flow = startFlow(resource, "/manage/connections");
        flow.row().setNonceHash(null);

        Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

        assertEquals(URI.create("/manage/connections?error=invalid_state"), response.getLocation(),
                "an unbindable row must be refused, not accepted on the strength of the cookie alone");
        assertNothingWasStored("a row that cannot be bound to a browser must not produce a grant");
    }

    @Test
    @DisplayName("unknown, expired, already-used and unbound callbacks are answered identically")
    void refusalsAreIndistinguishable() {
        // The store collapses unknown, expired and already-used into one empty
        // Optional precisely so the callback cannot tell them apart; the binding
        // mismatch is the fourth answer that has to look the same, or a probe learns
        // which states exist.
        RestConnectionAuthorization resource = resource(new SimpleMeterRegistry());

        when(stateStore.claim("state-nobody-issued")).thenReturn(Optional.empty());
        Response unknown = resource.callback(CODE, "state-nobody-issued", null, browserWithNoCookies());

        // Started with no returnTo, so this flow's destination is the same default
        // page the unknown-state answer uses; anything else would distinguish the two
        // by URL alone.
        StartedFlow flow = startFlow(resource, null);
        Response unbound = resource.callback(CODE, flow.row().getState(), null, browserWithNoCookies());

        assertEquals(URI.create(CONFIG.defaultReturnTo() + "?error=invalid_state"), unknown.getLocation(),
                "an unknown state must be reported as an invalid state on the default page");
        assertEquals(unknown.getStatus(), unbound.getStatus(), "a valid-but-unbound state must not be answered with a different status");
        assertEquals(unknown.getLocation(), unbound.getLocation(),
                "a valid-but-unbound state must not be answered with a different destination or error code, or the callback becomes an "
                        + "oracle for which state values exist");
    }

    // ── returnTo, from authorize through to the redirect ─────────────────────

    @Test
    @DisplayName("every returnTo authorize accepts can still be turned into a redirect by the callback")
    void acceptedReturnToSurvivesIntoTheRedirect() {
        // The two halves are written in different classes — ConnectionsConfig decides
        // what is allowed, RestConnectionAuthorization decides what can be built — and
        // the callback is where a disagreement surfaces, after the state is consumed
        // and the user can no longer retry.
        for (String returnTo : List.of("/manage/connections", "/manage/connections?tab=linked", "/manage/connections#linked",
                "https://eddi.example.com/manage/connections")) {
            assertTrue(CONFIG.isAllowedReturnTo(returnTo), "this test only covers values authorize actually accepts: " + returnTo);

            RestConnectionAuthorization resource = resource(new SimpleMeterRegistry());
            StartedFlow flow = startFlow(resource, returnTo);
            assertEquals(returnTo, flow.row().getReturnTo(), "an accepted returnTo must be stored verbatim: " + returnTo);

            Response response = resource.callback(CODE, flow.row().getState(), null, browserWithNoCookies());

            String base = returnTo.contains("#") ? returnTo.substring(0, returnTo.indexOf('#')) : returnTo;
            assertEquals(303, response.getStatus(), "an accepted returnTo must produce a redirect: " + returnTo);
            assertEquals(URI.create(base + (base.contains("?") ? "&" : "?") + "error=invalid_state"), response.getLocation(),
                    "the outcome must be appended to the accepted destination, not silently replaced by the default page: " + returnTo);
        }
    }

    @Test
    @DisplayName("a returnTo pointing at another host never reaches the state row")
    void foreignReturnToIsReplacedAtAuthorizeTime() {
        // The redirect lands on the user the instant they finish authenticating at the
        // provider — the moment they are least likely to read the address bar, and so
        // the moment an open redirect is worth the most.
        RestConnectionAuthorization resource = resource(new SimpleMeterRegistry());
        StartedFlow flow = startFlow(resource, "https://evil.example.com/collect");

        assertEquals(CONFIG.defaultReturnTo(), flow.row().getReturnTo(),
                "a returnTo that is not a page of this deployment must be replaced with the default before it is ever stored");

        Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

        assertEquals(URI.create(CONFIG.defaultReturnTo() + "?connected=drive"), response.getLocation(),
                "the callback must send the browser to this deployment's own page, never to a host the request named");
    }

    @Test
    @DisplayName("a stored returnTo that will not parse still ends in a redirect, because the grant already exists by then")
    void unparseableStoredReturnToFallsBackToTheDefault() {
        RestConnectionAuthorization resource = resource(new SimpleMeterRegistry());
        StartedFlow flow = startFlow(resource, "/manage/connections");
        // What authorize would never store, but an older release or a direct database
        // write leaves behind: an unencoded space, which URI.create refuses.
        flow.row().setReturnTo("/manage/my connections");

        Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

        assertEquals(List.of(PRINCIPAL), grantsStoredFor, "the exchange still has to happen; this is the case where the destination fails "
                + "AFTER the tokens are safely stored");
        assertEquals(303, response.getStatus(),
                "the grant is already stored at this point, so failing to build the destination must not become a 500 the user cannot retry");
        assertEquals(URI.create(CONFIG.defaultReturnTo() + "?connected=drive"), response.getLocation(),
                "an unusable destination degrades to the default page rather than throwing");
    }

    @Test
    @DisplayName("a public base URL that cannot be turned into a URI still yields a redirect")
    void unparseableDefaultPageFallsBackToRoot() {
        // A misconfigured base URL takes the default page down with it, and there is
        // still a browser waiting for an answer.
        RestConnectionAuthorization resource = resource(new SimpleMeterRegistry(), new ConnectionsConfig(true, Optional.of("ht tp://eddi")));
        when(stateStore.claim("state-nobody-issued")).thenReturn(Optional.empty());

        Response response = resource.callback(CODE, "state-nobody-issued", null, browserWithNoCookies());

        assertEquals(303, response.getStatus(), "there is a browser waiting; a misconfigured base URL must not turn into a 500");
        assertEquals(URI.create("/"), response.getLocation(), "the last resort is this deployment's own root, resolved relatively");
    }

    @Test
    @DisplayName("the callback answers 404 while the feature is disabled, without touching the state store")
    void disabledFeatureAnswers404() {
        var disabled = new ConnectionsConfig(false, Optional.of("https://eddi.example.com"));
        RestConnectionAuthorization resource = resource(new SimpleMeterRegistry(), disabled);

        Response response = resource.callback(CODE, "any-state", null, browserWithNoCookies());

        assertEquals(404, response.getStatus(), "a disabled feature answers 404 here and on every other route of this resource");
        verifyNoInteractions(stateStore);
    }

    /**
     * A registry that fails every counter call, the way a Prometheus registry built
     * with {@code throwExceptionOnRegistrationFailure} fails one.
     */
    private static final class ExplodingMeterRegistry extends SimpleMeterRegistry {

        @Override
        public Counter counter(String name, String... tags) {
            throw new IllegalArgumentException("registration failure for meter '" + name + "'");
        }
    }
}
