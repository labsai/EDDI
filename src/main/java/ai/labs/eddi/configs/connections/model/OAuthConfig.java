/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.model;

import java.util.List;
import java.util.Map;

/**
 * The OAuth half of a connection.
 * <p>
 * No provider is named here and none ever should be: Atlassian, Google, Notion
 * and Linear differ only in three URLs and a scope list, all of which are data.
 */
public class OAuthConfig {

    /** Client authenticates with HTTP Basic — RFC 6749 §2.3.1, the default. */
    public static final String CLIENT_AUTH_BASIC = "client_secret_basic";

    /** Client credentials go in the form body. Some providers accept only this. */
    public static final String CLIENT_AUTH_POST = "client_secret_post";

    /** Null for {@link AuthType#OAUTH2_CLIENT_CREDENTIALS}. */
    private String authorizationUrl;

    /** Where the code or the client credentials are exchanged for a token. */
    private String tokenUrl;

    /** Public identifier issued by the provider. Not a secret. */
    private String clientId;

    /** Must be a {@code ${vault:…}} reference — never a literal. */
    private String clientSecret;

    /** Space-delimited on the wire; a list here so a config can read it. */
    private List<String> scopes;

    /**
     * Extra <b>non-secret</b> protocol parameters added to the authorization
     * request ({@code prompt}, {@code audience}, {@code access_type}, …).
     * <p>
     * Validated against the credential-shaped name denylist, because an arbitrary
     * string map is the obvious place for an author to paste a key — and a key
     * pasted here would sit in plaintext in the connection document, invisible to
     * the vault, to export scrubbing and to deploy-time grant enforcement.
     */
    private Map<String, String> extraAuthParams;

    /**
     * Forced true for {@link AuthType#OAUTH2_AUTHORIZATION_CODE}. Present as a
     * field so a config round-trips, not so it can be turned off: a public redirect
     * endpoint without PKCE is an authorization-code interception vector, and there
     * is no provider in scope that lacks S256.
     */
    private boolean usePkce = true;

    /**
     * How the client authenticates at the token endpoint —
     * {@link #CLIENT_AUTH_BASIC} (default) or {@link #CLIENT_AUTH_POST}. A config
     * choice rather than a runtime guess: guessing means a failed exchange that
     * looks like a bad secret.
     */
    private String clientAuthMethod = CLIENT_AUTH_BASIC;

    /** Optional RFC 8414 / RFC 9728 metadata URL. */
    private String discoveryUrl;

    public String getAuthorizationUrl() {
        return authorizationUrl;
    }

    public void setAuthorizationUrl(String authorizationUrl) {
        this.authorizationUrl = authorizationUrl;
    }

    public String getTokenUrl() {
        return tokenUrl;
    }

    public void setTokenUrl(String tokenUrl) {
        this.tokenUrl = tokenUrl;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public void setScopes(List<String> scopes) {
        this.scopes = scopes;
    }

    public Map<String, String> getExtraAuthParams() {
        return extraAuthParams;
    }

    public void setExtraAuthParams(Map<String, String> extraAuthParams) {
        this.extraAuthParams = extraAuthParams;
    }

    public boolean isUsePkce() {
        return usePkce;
    }

    public void setUsePkce(boolean usePkce) {
        this.usePkce = usePkce;
    }

    public String getClientAuthMethod() {
        return clientAuthMethod;
    }

    public void setClientAuthMethod(String clientAuthMethod) {
        this.clientAuthMethod = clientAuthMethod;
    }

    public String getDiscoveryUrl() {
        return discoveryUrl;
    }

    public void setDiscoveryUrl(String discoveryUrl) {
        this.discoveryUrl = discoveryUrl;
    }
}
