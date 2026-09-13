/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * RFC 6749 wire format and provider misbehaviour.
 * <p>
 * The two properties worth the most here are not the happy path. The first is
 * that a client secret only ever leaves the process towards an origin an
 * operator approved — checked again at this last point, because a connection
 * document can reach the database without passing save-time validation. The
 * second is the terminal-versus-transient split: calling a provider's five
 * minute outage {@code invalid_grant} logs every user of the connection out,
 * and nothing downstream can tell the difference afterwards.
 */
class OAuthTokenClientTest {

    private static final String TOKEN_ORIGIN = "https://auth.example.com";
    private static final String TOKEN_URL = TOKEN_ORIGIN + "/oauth/token";
    private static final String CONNECTION = "drive";
    private static final String CLIENT_SECRET = "s3cr3t";

    private static final String FULL_TOKEN_BODY = """
            {
              "access_token": "at-live",
              "refresh_token": "rt-live",
              "expires_in": 3600,
              "scope": "drive.readonly drive.file"
            }
            """;

    private SafeHttpClient httpClient;
    private OAuthTokenClient client;

    @BeforeEach
    void setUp() {
        httpClient = mock(SafeHttpClient.class);
        client = new OAuthTokenClient(httpClient, new CredentialEndpointAllowlist(Set.of(TOKEN_ORIGIN)));
    }

    private static ConnectionConfiguration connection() {
        var connection = new ConnectionConfiguration();
        connection.setName(CONNECTION);
        connection.setAuthType(AuthType.OAUTH2_AUTHORIZATION_CODE);
        connection.setBinding(Binding.PER_USER);
        connection.setBaseUrlAllowlist(List.of("https://api.example.com"));
        var oauth = new OAuthConfig();
        oauth.setTokenUrl(TOKEN_URL);
        oauth.setAuthorizationUrl(TOKEN_ORIGIN + "/authorize");
        oauth.setClientId("client-id");
        oauth.setClientSecret("${vault:client-secret}");
        connection.setOauth(oauth);
        return connection;
    }

    @SuppressWarnings("unchecked")
    private void respondWith(int statusCode, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        doReturn(statusCode).when(response).statusCode();
        doReturn(body).when(response).body();
        // doReturn rather than when(...): sendValidatedNoRedirect is generic and
        // stubbing it
        // through when() would need the call to type-check against a concrete T.
        doReturn(response).when(httpClient).sendNoRedirect(any(), any());
    }

    private HttpRequest sentRequest() throws Exception {
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).sendNoRedirect(captor.capture(), any());
        return captor.getValue();
    }

    /**
     * Drains the request's body publisher, which is the only way to read it back.
     */
    private static String rawBody(HttpRequest request) {
        var publisher = request.bodyPublisher().orElseThrow(() -> new AssertionError("a token request must carry a form body"));
        var collected = new StringBuilder();
        var completed = new CountDownLatch(1);
        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                collected.append(StandardCharsets.UTF_8.decode(item));
            }

            @Override
            public void onError(Throwable throwable) {
                completed.countDown();
            }

            @Override
            public void onComplete() {
                completed.countDown();
            }
        });
        try {
            assertTrue(completed.await(5, TimeUnit.SECONDS), "the form body publisher never completed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        return collected.toString();
    }

    private static Map<String, String> form(HttpRequest request) {
        Map<String, String> parsed = new LinkedHashMap<>();
        String body = rawBody(request);
        if (body.isEmpty()) {
            return parsed;
        }
        for (String pair : body.split("&")) {
            int separator = pair.indexOf('=');
            parsed.put(URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return parsed;
    }

    @Test
    @DisplayName("a client_credentials exchange sends the RFC 6749 form to the configured token endpoint")
    void clientCredentialsSendsTheServiceAccountForm() throws Exception {
        var connection = connection();
        connection.getOauth().setScopes(List.of("drive.readonly", "drive.file"));
        respondWith(200, FULL_TOKEN_BODY);

        client.clientCredentials(connection, CLIENT_SECRET);

        HttpRequest request = sentRequest();
        assertEquals("POST", request.method());
        assertEquals(TOKEN_URL, request.uri().toString());
        assertEquals("application/x-www-form-urlencoded", request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("application/json", request.headers().firstValue("Accept").orElseThrow());
        assertEquals(OAuthTokenClient.DEFAULT_TIMEOUT, request.timeout().orElseThrow());

        Map<String, String> sentForm = form(request);
        assertEquals("client_credentials", sentForm.get("grant_type"));
        assertEquals("drive.readonly drive.file", sentForm.get("scope"), "scopes are space-delimited on the wire");
    }

    @Test
    @DisplayName("an authorization_code exchange carries the code, the redirect URI and the PKCE verifier")
    void authorizationCodeSendsTheCodeAndVerifier() throws Exception {
        respondWith(200, FULL_TOKEN_BODY);

        client.authorizationCode(connection(), CLIENT_SECRET, "the-code", "the-verifier", "https://eddi.example.com/callback");

        Map<String, String> sentForm = form(sentRequest());
        assertEquals("authorization_code", sentForm.get("grant_type"));
        assertEquals("the-code", sentForm.get("code"));
        assertEquals("https://eddi.example.com/callback", sentForm.get("redirect_uri"));
        assertEquals("the-verifier", sentForm.get("code_verifier"));
    }

    @Test
    @DisplayName("a refresh sends the refresh token and the configured scopes")
    void refreshSendsTheRefreshToken() throws Exception {
        var connection = connection();
        connection.getOauth().setScopes(List.of("drive.readonly"));
        respondWith(200, FULL_TOKEN_BODY);

        client.refresh(connection, CLIENT_SECRET, "rt-stored");

        Map<String, String> sentForm = form(sentRequest());
        assertEquals("refresh_token", sentForm.get("grant_type"));
        assertEquals("rt-stored", sentForm.get("refresh_token"));
        assertEquals("drive.readonly", sentForm.get("scope"));
    }

    @Test
    @DisplayName("no scope parameter is sent when the connection configures none")
    void omitsScopeWhenNoneIsConfigured() throws Exception {
        respondWith(200, FULL_TOKEN_BODY);

        client.clientCredentials(connection(), CLIENT_SECRET);

        assertFalse(form(sentRequest()).containsKey("scope"), "an empty scope parameter narrows the grant on some providers");
    }

    @Test
    @DisplayName("no scope parameter is sent when the configured scope list is empty")
    void omitsScopeWhenTheScopeListIsEmpty() throws Exception {
        var connection = connection();
        connection.getOauth().setScopes(List.of());
        respondWith(200, FULL_TOKEN_BODY);

        client.refresh(connection, CLIENT_SECRET, "rt-stored");

        assertFalse(form(sentRequest()).containsKey("scope"));
    }

    @Test
    @DisplayName("HTTP Basic is the default, and both halves are url-encoded so the colon separator survives")
    void basicAuthEncodesEachHalfOfTheCredential() throws Exception {
        var connection = connection();
        connection.getOauth().setClientId("cli ent:id");
        respondWith(200, FULL_TOKEN_BODY);

        client.clientCredentials(connection, "se/cr+et");

        HttpRequest request = sentRequest();
        String header = request.headers().firstValue("Authorization").orElseThrow();
        assertTrue(header.startsWith("Basic "), header);
        String decoded = new String(Base64.getDecoder().decode(header.substring("Basic ".length())), StandardCharsets.UTF_8);
        assertEquals("cli+ent%3Aid:se%2Fcr%2Bet", decoded,
                "an unencoded colon or plus in either half would re-split the credential in the wrong place");
        assertFalse(form(request).containsKey("client_secret"), "the secret belongs in the header for client_secret_basic");
    }

    @Test
    @DisplayName("an unset client auth method still authenticates with Basic rather than sending nothing")
    void treatsAnUnsetClientAuthMethodAsBasic() throws Exception {
        var connection = connection();
        connection.getOauth().setClientAuthMethod(null);
        respondWith(200, FULL_TOKEN_BODY);

        client.clientCredentials(connection, CLIENT_SECRET);

        assertTrue(sentRequest().headers().firstValue("Authorization").isPresent());
    }

    @Test
    @DisplayName("client_secret_post puts the credentials in the body and sends no Authorization header")
    void clientSecretPostMovesTheCredentialIntoTheForm() throws Exception {
        var connection = connection();
        connection.getOauth().setClientAuthMethod(OAuthConfig.CLIENT_AUTH_POST);
        respondWith(200, FULL_TOKEN_BODY);

        client.clientCredentials(connection, CLIENT_SECRET);

        HttpRequest request = sentRequest();
        assertTrue(request.headers().firstValue("Authorization").isEmpty(),
                "sending both is how a provider that checks only one silently ignores the other");
        Map<String, String> sentForm = form(request);
        assertEquals("client-id", sentForm.get("client_id"));
        assertEquals(CLIENT_SECRET, sentForm.get("client_secret"));
    }

    @Test
    @DisplayName("a token URL outside the operator allowlist is refused before the secret leaves the process")
    void refusesATokenUrlTheOperatorNeverApproved() throws Exception {
        var connection = connection();
        connection.getOauth().setTokenUrl("https://attacker.example.com/token");

        var error = assertThrows(ConnectionException.class, () -> client.clientCredentials(connection, CLIENT_SECRET));

        assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, error.getReason());
        verify(httpClient, never()).sendNoRedirect(any(), any());
    }

    @Test
    @DisplayName("a redirect from the token endpoint is never followed and is transient, not a dead grant")
    void redirectIsRefusedRatherThanFollowed() throws Exception {
        // The request that produced this carries the client secret and the refresh
        // token. Following a 307 preserves method and body, so everything would be
        // re-sent to whatever host the Location names — validated only against the
        // SSRF rules, never against the operator's credential-endpoint allowlist.
        for (int status : List.of(301, 302, 303, 307, 308)) {
            respondWith(status, "");

            var error = assertThrows(ConnectionException.class, () -> client.refresh(connection(), CLIENT_SECRET, "rt-stored"),
                    "HTTP " + status);

            assertEquals(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, error.getReason(),
                    "HTTP " + status + ": a redirect is a configuration problem at the provider, not a revoked grant");
            assertTrue(error.getMessage().contains("must not redirect"), error.getMessage());
            assertTrue(error.getMessage().contains("unchanged"), "the grant must be reported untouched: " + error.getMessage());
        }
        // The no-redirect send is the whole guarantee: the ordinary send would have
        // followed the hop with the body intact before this client ever saw a 3xx.
        verify(httpClient, never()).sendValidated(any(), any());
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @DisplayName("an allowlisted token endpoint on a private network is contacted — the operator's allowlist outranks the SSRF address block")
    void allowlistedPrivateNetworkTokenEndpointIsContacted() throws Exception {
        // An on-premises identity provider. sendValidated* would refuse the host
        // before the request left the process, and the operator who listed the exact
        // origin is precisely the person entitled to send a client secret there.
        String privateOrigin = "https://idp.corp.internal";
        client = new OAuthTokenClient(httpClient, new CredentialEndpointAllowlist(Set.of(privateOrigin)));
        var connection = connection();
        connection.getOauth().setTokenUrl(privateOrigin + "/oauth/token");
        respondWith(200, FULL_TOKEN_BODY);

        assertEquals("at-live", client.clientCredentials(connection, CLIENT_SECRET).accessToken());

        assertEquals(privateOrigin + "/oauth/token", sentRequest().uri().toString());
        verify(httpClient, never()).sendValidatedNoRedirect(any(), any());
        verify(httpClient, never()).sendValidated(any(), any());
    }

    @Test
    @DisplayName("the exemption is from the address check only — a non-http scheme or a hostless URL is still refused")
    void schemeAndHostAreStillValidated() {
        for (String tokenUrl : List.of("ftp://auth.example.com/token", "https:///token")) {
            var connection = connection();
            connection.getOauth().setTokenUrl(tokenUrl);
            // The allowlist itself refuses these first (it only accepts bare http(s)
            // origins), which is the layered defence the syntax check backs up.
            assertThrows(RuntimeException.class, () -> client.clientCredentials(connection, CLIENT_SECRET), tokenUrl);
        }
    }

    @Test
    @DisplayName("a tokenUrl the allowlist accepts once trimmed but that will not parse is a configuration error, and nothing is sent")
    void unparseableTokenUrlIsAConfigurationError() throws Exception {
        // The allowlist trims before it judges the origin, so this passes it; the
        // request builder does not, so URI.create threw a raw IllegalArgumentException
        // — which is not a ConnectionException, and on the mint path the MCP failure
        // classifier fed it to the circuit breaker as a server outage.
        var connection = connection();
        connection.getOauth().setTokenUrl(TOKEN_URL + " ");

        var error = assertThrows(ConnectionException.class, () -> client.clientCredentials(connection, CLIENT_SECRET));

        assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, error.getReason(), error.getMessage());
        assertTrue(error.getMessage().contains("oauth.tokenUrl"), "the message must name the field to fix: " + error.getMessage());
        verify(httpClient, never()).sendNoRedirect(any(), any());
    }

    @Test
    @DisplayName("a transport failure is transient — the grant is left alone and the next call retries")
    void transportFailureIsTransient() throws Exception {
        doThrow(new IOException("connection reset")).when(httpClient).sendNoRedirect(any(), any());

        var error = assertThrows(ConnectionException.class, () -> client.refresh(connection(), CLIENT_SECRET, "rt-stored"));

        assertEquals(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, error.getReason());
        assertTrue(error.getMessage().contains("unchanged"), error.getMessage());
        assertTrue(error.getMessage().contains(CONNECTION), "the message has to name which connection to look at: " + error.getMessage());
    }

    @Test
    @DisplayName("an interrupted exchange is transient and hands the interrupt back to the caller")
    void interruptionIsTransientAndRestoresTheInterruptFlag() throws Exception {
        doThrow(new InterruptedException("shutting down")).when(httpClient).sendNoRedirect(any(), any());

        var error = assertThrows(ConnectionException.class, () -> client.refresh(connection(), CLIENT_SECRET, "rt-stored"));

        assertEquals(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, error.getReason());
        // Read-and-clear, so a swallowed interrupt cannot leak into the next test.
        assertTrue(Thread.interrupted(), "swallowing the interrupt leaves a shutdown unable to stop the thread");
    }

    @Test
    @DisplayName("invalid_grant, unauthorized_client and invalid_client are terminal: the user must reconnect")
    void terminalOAuthErrorsDemandAReconnect() throws Exception {
        for (String errorCode : List.of("invalid_grant", "unauthorized_client", "invalid_client")) {
            respondWith(400, "{\"error\":\"" + errorCode + "\"}");

            var error = assertThrows(ConnectionException.class, () -> client.refresh(connection(), CLIENT_SECRET, "rt-stored"));

            assertEquals(ConnectionException.Reason.GRANT_UNUSABLE, error.getReason(), errorCode);
            assertTrue(error.getMessage().contains("reconnect"), error.getMessage());
        }
    }

    @Test
    @DisplayName("any other OAuth error is the provider having a bad day, not a dead grant")
    void otherOAuthErrorsAreTransient() throws Exception {
        respondWith(429, "{\"error\":\"slow_down\"}");

        var error = assertThrows(ConnectionException.class, () -> client.refresh(connection(), CLIENT_SECRET, "rt-stored"));

        assertEquals(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, error.getReason());
        assertTrue(error.getMessage().contains("429"), error.getMessage());
        assertTrue(error.getMessage().contains("slow_down"), error.getMessage());
    }

    @Test
    @DisplayName("an error body that is not JSON is still only worth its status code")
    void nonJsonErrorBodyIsTransient() throws Exception {
        respondWith(502, "<html><body>Bad Gateway</body></html>");

        var error = assertThrows(ConnectionException.class, () -> client.refresh(connection(), CLIENT_SECRET, "rt-stored"));

        assertEquals(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, error.getReason());
        assertTrue(error.getMessage().contains("502"), error.getMessage());
        assertTrue(error.getMessage().contains("unknown_error"), error.getMessage());
    }

    @Test
    @DisplayName("a JSON error body with no error code is transient rather than assumed dead")
    void jsonErrorBodyWithoutAnErrorCodeIsTransient() throws Exception {
        for (String body : List.of("{\"message\":\"try later\"}", "{\"error\":null}")) {
            respondWith(500, body);

            var error = assertThrows(ConnectionException.class, () -> client.refresh(connection(), CLIENT_SECRET, "rt-stored"));

            assertEquals(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, error.getReason(), body);
        }
    }

    @Test
    @DisplayName("the provider's error description is never quoted back into the message")
    void neverEchoesTheProviderErrorBody() throws Exception {
        // This message reaches the logs and, on the callback path, a browser. A token
        // endpoint's error body routinely echoes request material back.
        respondWith(400, """
                {
                  "error": "invalid_grant",
                  "error_description": "token 1//0a-LEAKED-MATERIAL was revoked",
                  "error_uri": "https://auth.example.com/errors/1//0a-LEAKED-MATERIAL"
                }
                """);

        var error = assertThrows(ConnectionException.class, () -> client.refresh(connection(), CLIENT_SECRET, "rt-stored"));

        assertFalse(error.getMessage().contains("LEAKED-MATERIAL"), error.getMessage());
    }

    @Test
    @DisplayName("a successful exchange returns every field the provider stated")
    void parsesACompleteTokenResponse() throws Exception {
        respondWith(200, FULL_TOKEN_BODY);

        var token = client.clientCredentials(connection(), CLIENT_SECRET);

        assertEquals("at-live", token.accessToken());
        assertEquals("rt-live", token.refreshToken());
        assertEquals(Duration.ofHours(1), token.expiresIn());
        assertEquals(List.of("drive.readonly", "drive.file"), token.scopes());
    }

    @Test
    @DisplayName("success is the whole 2xx range, and 300 is already a failure")
    void treatsTheWholeTwoHundredRangeAsSuccess() throws Exception {
        for (int status : List.of(200, 201, 299)) {
            respondWith(status, FULL_TOKEN_BODY);
            assertEquals("at-live", client.clientCredentials(connection(), CLIENT_SECRET).accessToken(), "HTTP " + status);
        }
        for (int status : List.of(199, 300)) {
            respondWith(status, FULL_TOKEN_BODY);
            var error = assertThrows(ConnectionException.class, () -> client.clientCredentials(connection(), CLIENT_SECRET));
            assertEquals(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, error.getReason(), "HTTP " + status);
        }
    }

    @Test
    @DisplayName("a 200 with no usable access token is the endpoint misbehaving, not a dead grant")
    void refusesATwoHundredWithoutAnAccessToken() throws Exception {
        // Only a provider error body naming invalid_grant / invalid_client /
        // unauthorized_client is terminal. A 2xx that is not a token response leaves
        // the grant alone; calling it terminal wrote REFRESH_FAILED for every user of
        // the connection during a provider's bad minute.
        List<String> bodies = List.of("{\"token_type\":\"Bearer\"}", "{\"access_token\":null}", "{\"access_token\":\"\"}",
                "{\"access_token\":\"   \"}");
        for (String body : bodies) {
            respondWith(200, body);

            var error = assertThrows(ConnectionException.class, () -> client.clientCredentials(connection(), CLIENT_SECRET));

            assertEquals(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, error.getReason(), body);
            assertTrue(error.getMessage().contains("no access_token"), error.getMessage());
            assertTrue(error.getMessage().contains("unchanged"), "the grant must be reported untouched: " + error.getMessage());
        }
    }

    @Test
    @DisplayName("a 200 that is not a token response at all — an HTML maintenance page — is transient")
    void refusesAMalformedBody() throws Exception {
        respondWith(200, "<html>we are down for maintenance</html>");

        var error = assertThrows(ConnectionException.class, () -> client.clientCredentials(connection(), CLIENT_SECRET));

        assertEquals(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, error.getReason(),
                "a maintenance page is the endpoint being unavailable; marking the grant dead demands a reconnect for an outage");
        assertTrue(error.getMessage().contains("not a token response"), error.getMessage());
        assertTrue(error.getMessage().contains("unchanged"), "the grant must be reported untouched: " + error.getMessage());
    }

    @Test
    @DisplayName("an empty 200 body is refused rather than turned into a blank token")
    void refusesAnEmptyBody() throws Exception {
        respondWith(200, "");

        var error = assertThrows(ConnectionException.class, () -> client.clientCredentials(connection(), CLIENT_SECRET));

        assertEquals(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, error.getReason());
        assertTrue(error.getMessage().contains("no access_token"), error.getMessage());
    }

    @Test
    @DisplayName("a missing, zero or negative expires_in falls back to the short assumed lifetime")
    void defaultsTheLifetimeWhenTheProviderStatesNoUsableOne() throws Exception {
        List<String> bodies = List.of("{\"access_token\":\"at\"}", "{\"access_token\":\"at\",\"expires_in\":null}",
                "{\"access_token\":\"at\",\"expires_in\":0}", "{\"access_token\":\"at\",\"expires_in\":-60}");
        for (String body : bodies) {
            respondWith(200, body);

            assertEquals(TokenResponse.DEFAULT_LIFETIME, client.clientCredentials(connection(), CLIENT_SECRET).expiresIn(), body);
        }
    }

    @Test
    @DisplayName("a legally short expires_in is passed through exactly as the provider stated it")
    void doesNotRoundUpAShortLifetime() throws Exception {
        // RFC 6749 sets no floor. Rounding 20 seconds up to something comfortable
        // means sending a token the provider has already expired; the service side
        // absorbs it by shrinking its expiry margin instead.
        respondWith(200, "{\"access_token\":\"at\",\"expires_in\":20}");

        assertEquals(Duration.ofSeconds(20), client.clientCredentials(connection(), CLIENT_SECRET).expiresIn());
    }

    @Test
    @DisplayName("granted scopes are split on any run of whitespace, and an absent scope is an empty list")
    void splitsGrantedScopes() throws Exception {
        respondWith(200, "{\"access_token\":\"at\",\"scope\":\"  drive.readonly   drive.file \"}");
        assertEquals(List.of("drive.readonly", "drive.file"), client.clientCredentials(connection(), CLIENT_SECRET).scopes(),
                "a leading space would otherwise produce an empty first scope");

        for (String body : List.of("{\"access_token\":\"at\"}", "{\"access_token\":\"at\",\"scope\":\"\"}",
                "{\"access_token\":\"at\",\"scope\":\"   \"}")) {
            respondWith(200, body);
            assertEquals(List.of(), client.clientCredentials(connection(), CLIENT_SECRET).scopes(), body);
        }
    }

    @Test
    @DisplayName("a provider that does not return a refresh token yields a null one, not an empty string")
    void reportsAnAbsentRefreshTokenAsNull() throws Exception {
        // The service carries its stored refresh token forward on null; an empty
        // string would overwrite it and make the NEXT refresh impossible.
        respondWith(200, "{\"access_token\":\"at\",\"expires_in\":3600}");

        assertNull(client.clientCredentials(connection(), CLIENT_SECRET).refreshToken());
    }

    @Test
    @DisplayName("a zero or negative configured timeout falls back to the default rather than expiring instantly")
    void ignoresANonPositiveConfiguredTimeout() {
        var connection = connection();

        connection.setTimeoutMs(0);
        assertEquals(OAuthTokenClient.DEFAULT_TIMEOUT, OAuthTokenClient.effectiveTimeout(connection));

        connection.setTimeoutMs(-1);
        assertEquals(OAuthTokenClient.DEFAULT_TIMEOUT, OAuthTokenClient.effectiveTimeout(connection));
    }

    @Test
    @DisplayName("a configured timeout reaches the request that is actually sent")
    void appliesTheConfiguredTimeoutToTheRequest() throws Exception {
        var connection = connection();
        connection.setTimeoutMs(7_000);
        respondWith(200, FULL_TOKEN_BODY);

        client.clientCredentials(connection, CLIENT_SECRET);

        assertEquals(Duration.ofSeconds(7), sentRequest().timeout().orElseThrow());
    }
}
