/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
import ai.labs.eddi.connections.ConnectionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Speaks to an OAuth 2.0 token endpoint. Nothing else.
 * <p>
 * Kept separate from {@code OAuthTokenService} because the two fail differently
 * and are tested differently: this one is about RFC 6749 wire format and
 * provider quirks, the service is about concurrency and storage.
 *
 * <h3>Every request goes through {@link SafeHttpClient}</h3> This is the one
 * new outbound path the connectors work introduces, so it starts compliant
 * rather than joining the four services that already bypass it. That buys
 * {@code Redirect.NEVER} — which matters more here than almost anywhere else,
 * since a token request carries the client secret in an {@code Authorization}
 * header and a followed redirect would hand it to whatever host the 302 named.
 */
@ApplicationScoped
public class OAuthTokenClient {

    private static final Logger LOGGER = Logger.getLogger(OAuthTokenClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Provider default when a connection sets no timeout. */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    /**
     * OAuth errors that mean the grant is dead and the user must reconnect. Every
     * other error — and every transport failure — is treated as transient.
     * <p>
     * The distinction is the difference between "reconnect required" shown once and
     * every user being logged out by a provider's five-minute outage.
     */
    private static final List<String> TERMINAL_ERRORS = List.of("invalid_grant", "unauthorized_client", "invalid_client");

    private final SafeHttpClient httpClient;
    private final CredentialEndpointAllowlist endpointAllowlist;

    @Inject
    public OAuthTokenClient(SafeHttpClient httpClient, CredentialEndpointAllowlist endpointAllowlist) {
        this.httpClient = httpClient;
        this.endpointAllowlist = endpointAllowlist;
    }

    /** RFC 6749 §4.4.2 — the service-account flow. */
    public TokenResponse clientCredentials(ConnectionConfiguration connection, String clientSecret) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        addScopes(form, connection.getOauth());
        return exchange(connection, clientSecret, form);
    }

    /** RFC 6749 §4.1.3 plus RFC 7636 — redeeming an authorization code. */
    public TokenResponse authorizationCode(ConnectionConfiguration connection, String clientSecret, String code, String codeVerifier,
                                           String redirectUri) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("code_verifier", codeVerifier);
        return exchange(connection, clientSecret, form);
    }

    /** RFC 6749 §6. */
    public TokenResponse refresh(ConnectionConfiguration connection, String clientSecret, String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        addScopes(form, connection.getOauth());
        return exchange(connection, clientSecret, form);
    }

    private static void addScopes(Map<String, String> form, OAuthConfig oauth) {
        if (oauth.getScopes() != null && !oauth.getScopes().isEmpty()) {
            form.put("scope", String.join(" ", oauth.getScopes()));
        }
    }

    private TokenResponse exchange(ConnectionConfiguration connection, String clientSecret, Map<String, String> form) {
        OAuthConfig oauth = connection.getOauth();
        // Re-checked here, not only at write time. A document can reach the database
        // by import or by a direct write, and this is the last point before the
        // client secret leaves the process.
        endpointAllowlist.require(oauth.getTokenUrl(), "oauth.tokenUrl");

        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create(oauth.getTokenUrl()))
                .timeout(connection.getTimeoutMs() == null ? DEFAULT_TIMEOUT : Duration.ofMillis(connection.getTimeoutMs()))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json");

        Map<String, String> body = new LinkedHashMap<>(form);
        if (OAuthConfig.CLIENT_AUTH_POST.equals(oauth.getClientAuthMethod())) {
            body.put("client_id", oauth.getClientId());
            body.put("client_secret", clientSecret);
        } else {
            // HTTP Basic is the default because RFC 6749 §2.3.1 says servers MUST
            // support it, while client_secret_post is optional.
            String credentials = urlEncode(oauth.getClientId()) + ":" + urlEncode(clientSecret);
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }

        HttpResponse<String> response;
        try {
            response = httpClient.sendValidated(request.POST(HttpRequest.BodyPublishers.ofString(encodeForm(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // Transport failure. NOT terminal: the grant stays usable and the next
            // request retries. Conflating this with invalid_grant logs every user of
            // the connection out on a provider blip.
            throw new ConnectionException(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE,
                    "Token endpoint for connection '" + connection.getName() + "' is unreachable (" + e.getClass().getSimpleName()
                            + "). The grant is unchanged; the next request will retry.",
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectionException(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE,
                    "Interrupted while contacting the token endpoint for connection '" + connection.getName() + "'.", e);
        }

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return parse(connection, response.body());
        }
        throw errorFor(connection, response);
    }

    /**
     * Maps a non-2xx token response onto terminal-versus-transient.
     * <p>
     * The response BODY is parsed for the OAuth {@code error} code and nothing else
     * is quoted back: a token endpoint's error body can echo request material, and
     * this message reaches logs and, for the callback, a browser.
     */
    private static ConnectionException errorFor(ConnectionConfiguration connection, HttpResponse<String> response) {
        String errorCode = "unknown_error";
        try {
            JsonNode json = MAPPER.readTree(response.body());
            if (json.hasNonNull("error")) {
                errorCode = json.get("error").asText();
            }
        } catch (Exception ignored) {
            // A non-JSON error body is itself only worth its status code.
        }
        boolean terminal = TERMINAL_ERRORS.contains(errorCode);
        LOGGER.warnf("Token endpoint for connection '%s' returned HTTP %d (%s)", connection.getName(), response.statusCode(), errorCode);
        if (terminal) {
            return new ConnectionException(ConnectionException.Reason.GRANT_UNUSABLE, "The provider rejected the grant for connection '"
                    + connection.getName() + "' (" + errorCode + "). The user must reconnect.");
        }
        // 5xx and rate limiting are the provider having a bad day, not a dead grant.
        return new ConnectionException(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, "Token endpoint for connection '"
                + connection.getName() + "' returned HTTP " + response.statusCode() + " (" + errorCode + "). The grant is unchanged.");
    }

    private static TokenResponse parse(ConnectionConfiguration connection, String body) {
        try {
            JsonNode json = MAPPER.readTree(body);
            String accessToken = json.path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new ConnectionException(ConnectionException.Reason.GRANT_UNUSABLE,
                        "Token endpoint for connection '" + connection.getName() + "' returned 200 with no access_token.");
            }
            String refreshToken = json.path("refresh_token").asText(null);
            Duration expiresIn = json.hasNonNull("expires_in")
                    ? Duration.ofSeconds(json.get("expires_in").asLong())
                    : TokenResponse.DEFAULT_LIFETIME;
            if (expiresIn.isNegative() || expiresIn.isZero()) {
                expiresIn = TokenResponse.DEFAULT_LIFETIME;
            }
            List<String> scopes = new ArrayList<>();
            String granted = json.path("scope").asText(null);
            if (granted != null && !granted.isBlank()) {
                scopes.addAll(List.of(granted.trim().split("\\s+")));
            }
            return new TokenResponse(accessToken, refreshToken, expiresIn, scopes);
        } catch (ConnectionException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectionException(ConnectionException.Reason.GRANT_UNUSABLE,
                    "Token endpoint for connection '" + connection.getName() + "' returned a body that is not a token response.", e);
        }
    }

    private static String encodeForm(Map<String, String> form) {
        var encoded = new StringBuilder();
        form.forEach((key, value) -> {
            if (!encoded.isEmpty()) {
                encoded.append('&');
            }
            encoded.append(urlEncode(key)).append('=').append(urlEncode(value));
        });
        return encoded.toString();
    }

    private static String urlEncode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
