/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.RedirectPolicy;
import ai.labs.eddi.engine.httpclient.outbound.TestOutbound;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The wire format of the two signed-JWT flows: what reaches the token endpoint,
 * and — as important — what does not. Neither flow may send a client secret,
 * and neither may send anything at all to an endpoint the operator never
 * approved.
 */
class OAuthTokenClientSignedJwtTest {

    private static final String TOKEN_BODY = """
            {"access_token": "ya29.live", "expires_in": 3599, "token_type": "Bearer"}
            """;

    private OutboundHttp httpClient;
    private OAuthTokenClient client;

    @BeforeAll
    static void key() throws Exception {
        JwtAssertionsTest.generateKey();
    }

    @BeforeEach
    void setUp() {
        httpClient = mock(OutboundHttp.class);
        client = new OAuthTokenClient(httpClient,
                new CredentialEndpointAllowlist(Set.of("https://oauth2.googleapis.com", "https://login.microsoftonline.com")));
    }

    private void respondWith(int status, String body) throws Exception {
        doReturn(TestOutbound.response(status, body, false)).when(httpClient).send(any(), any());
    }

    private OutboundRequest sentRequest() throws Exception {
        var captor = ArgumentCaptor.forClass(OutboundRequest.class);
        verify(httpClient).send(captor.capture(), any());
        return captor.getValue();
    }

    private static Map<String, String> form(OutboundRequest request) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String pair : new String(request.body().toBytes(), StandardCharsets.UTF_8).split("&")) {
            int separator = pair.indexOf('=');
            parsed.put(URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return parsed;
    }

    private static boolean hasHeader(OutboundRequest request, String name) {
        return request.headers().keySet().stream().anyMatch(name::equalsIgnoreCase);
    }

    @Test
    @DisplayName("JWT bearer: grant_type and a verifiable assertion, no client authentication, no redirect following")
    void jwtBearerExchange() throws Exception {
        respondWith(200, TOKEN_BODY);

        TokenResponse token = client.jwtBearer(JwtAssertionsTest.jwtBearerConnection(), JwtAssertionsTest.pem);

        assertEquals("ya29.live", token.accessToken());
        OutboundRequest request = sentRequest();
        assertEquals("POST", request.method());
        assertEquals(JwtAssertionsTest.TOKEN_URL, request.uri().toString());
        assertEquals(RedirectPolicy.NEVER, request.redirectOverride());
        Map<String, String> sent = form(request);
        assertEquals(Set.of("grant_type", "assertion"), sent.keySet(), "exactly the RFC 7523 §2.1 parameters");
        assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", sent.get("grant_type"));
        JsonNode claims = JwtAssertionsTest.verify(sent.get("assertion"), JwtAssertionsTest.keyPair)[1];
        assertEquals(JwtAssertionsTest.SERVICE_ACCOUNT, claims.get("iss").asText());
        assertFalse(hasHeader(request, "Authorization"), "the assertion is the proof; no Basic header rides along");
        assertFalse(new String(request.body().toBytes(), StandardCharsets.UTF_8).contains("PRIVATE KEY"));
    }

    @Test
    @DisplayName("JWT bearer: the key file's token_uri is ignored — the connection's tokenUrl is where the request goes")
    void keyFileCannotRedirectTheRequest() throws Exception {
        respondWith(200, TOKEN_BODY);

        client.jwtBearer(JwtAssertionsTest.jwtBearerConnection(),
                JwtAssertionsTest.jsonKeyFile(JwtAssertionsTest.pem, JwtAssertionsTest.SERVICE_ACCOUNT, "kid-1"));

        assertEquals(JwtAssertionsTest.TOKEN_URL, sentRequest().uri().toString());
    }

    @Test
    @DisplayName("JWT bearer: a token URL off the credential-endpoint allowlist is refused before anything is signed or sent")
    void jwtBearerRespectsTheAllowlist() throws Exception {
        var connection = JwtAssertionsTest.jwtBearerConnection();
        connection.getOauth().setTokenUrl("https://evil.example.com/token");

        assertThrows(ConnectionException.class, () -> client.jwtBearer(connection, JwtAssertionsTest.pem));
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @DisplayName("JWT bearer: invalid_grant (a bad signature, a skewed clock) is terminal, so the service re-mints rather than retrying blind")
    void jwtBearerInvalidGrant() throws Exception {
        respondWith(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}");

        var e = assertThrows(ConnectionException.class, () -> client.jwtBearer(JwtAssertionsTest.jwtBearerConnection(), JwtAssertionsTest.pem));
        assertEquals(ConnectionException.Reason.GRANT_UNUSABLE, e.getReason());
    }

    @Test
    @DisplayName("private_key_jwt on client_credentials: client_assertion replaces the secret; no Basic header, no client_secret")
    void privateKeyJwtClientCredentials() throws Exception {
        respondWith(200, TOKEN_BODY);
        ConnectionConfiguration connection = JwtAssertionsTest.privateKeyJwtConnection();

        client.clientCredentials(connection, JwtAssertionsTest.pem);

        OutboundRequest request = sentRequest();
        Map<String, String> sent = form(request);
        assertEquals("client_credentials", sent.get("grant_type"));
        assertEquals("https://graph.microsoft.com/.default", sent.get("scope"));
        assertEquals(connection.getOauth().getClientId(), sent.get("client_id"));
        assertEquals("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", sent.get("client_assertion_type"));
        assertClientAssertion(connection, sent.get("client_assertion"));
        assertNull(sent.get("client_secret"));
        assertFalse(hasHeader(request, "Authorization"));
    }

    @Test
    @DisplayName("private_key_jwt on authorization_code: the code, verifier and client assertion travel together")
    void privateKeyJwtAuthorizationCode() throws Exception {
        respondWith(200, TOKEN_BODY);
        ConnectionConfiguration connection = JwtAssertionsTest.privateKeyJwtConnection();
        connection.setAuthType(AuthType.OAUTH2_AUTHORIZATION_CODE);
        connection.setBinding(Binding.PER_USER);
        connection.getOauth().setAuthorizationUrl("https://login.microsoftonline.com/tenant-id/oauth2/v2.0/authorize");

        client.authorizationCode(connection, JwtAssertionsTest.pem, "the-code", "the-verifier", "https://eddi.example.com/connections/callback");

        OutboundRequest request = sentRequest();
        Map<String, String> sent = form(request);
        assertEquals("authorization_code", sent.get("grant_type"));
        assertEquals("the-code", sent.get("code"));
        assertEquals("the-verifier", sent.get("code_verifier"));
        assertEquals("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", sent.get("client_assertion_type"));
        assertClientAssertion(connection, sent.get("client_assertion"));
        assertFalse(hasHeader(request, "Authorization"));
    }

    @Test
    @DisplayName("private_key_jwt on refresh: a fresh assertion authenticates the refresh too")
    void privateKeyJwtRefresh() throws Exception {
        respondWith(200, TOKEN_BODY);
        ConnectionConfiguration connection = JwtAssertionsTest.privateKeyJwtConnection();
        connection.getOauth().setScopes(List.of());

        client.refresh(connection, JwtAssertionsTest.pem, "rt");

        Map<String, String> sent = form(sentRequest());
        assertEquals("refresh_token", sent.get("grant_type"));
        assertClientAssertion(connection, sent.get("client_assertion"));
    }

    /**
     * RFC 7523 §3 for a client assertion: issued by and about the client, addressed
     * to the token endpoint. A signature check alone would pass an assertion that
     * names the wrong client or the wrong endpoint.
     */
    private static void assertClientAssertion(ConnectionConfiguration connection, String assertion) throws Exception {
        JsonNode claims = JwtAssertionsTest.verify(assertion, JwtAssertionsTest.keyPair)[1];
        assertEquals(connection.getOauth().getClientId(), claims.get("iss").asText());
        assertEquals(connection.getOauth().getClientId(), claims.get("sub").asText());
        assertEquals(connection.getOauth().getTokenUrl(), claims.get("aud").asText());
    }

    @Test
    @DisplayName("private_key_jwt with an unusable key fails as configuration, and nothing is sent")
    void privateKeyJwtBadKey() throws Exception {
        var e = assertThrows(ConnectionException.class,
                () -> client.clientCredentials(JwtAssertionsTest.privateKeyJwtConnection(), "not-a-key"));
        assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, e.getReason());
        verify(httpClient, never()).send(any(), any());
    }
}
