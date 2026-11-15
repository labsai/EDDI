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

    /**
     * The client proves itself with a JWT signed by its private key — RFC 7523
     * §2.2, OpenID Connect Core §9. No shared secret exists to leak: the provider
     * holds only the public half (Microsoft Entra calls this "an app with a
     * certificate credential").
     */
    public static final String CLIENT_AUTH_PRIVATE_KEY_JWT = "private_key_jwt";

    /**
     * JOSE header carrying the base64url SHA-256 thumbprint of the client
     * certificate (RFC 7515 §4.1.8). What Microsoft Entra asks for today.
     */
    public static final String THUMBPRINT_X5T_S256 = "x5t#S256";

    /**
     * JOSE header carrying the base64url SHA-1 thumbprint (RFC 7515 §4.1.7). Still
     * accepted by Entra and AD FS; offered for identity providers that know only
     * this one.
     */
    public static final String THUMBPRINT_X5T = "x5t";

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

    /**
     * The signing key, for {@link AuthType#OAUTH2_JWT_BEARER} and for
     * {@link #CLIENT_AUTH_PRIVATE_KEY_JWT}. Must be a {@code ${vault:…}} reference
     * — never a literal — whose secret holds either an unencrypted PKCS#8 PEM
     * ({@code -----BEGIN PRIVATE KEY-----}) RSA key, or a whole Google
     * service-account JSON key file, from which {@code private_key},
     * {@code client_email} and {@code private_key_id} are read.
     */
    private String privateKey;

    /**
     * Optional {@code kid} header naming which key signed the assertion. Not a
     * secret. Falls back to the JSON key file's {@code private_key_id}.
     */
    private String privateKeyId;

    /**
     * The {@code aud} claim of a {@link AuthType#OAUTH2_JWT_BEARER} assertion.
     * Defaults to {@link #tokenUrl}, which is what RFC 7523 §3 and Google expect.
     */
    private String audience;

    /**
     * Thumbprint of the certificate registered for
     * {@link #CLIENT_AUTH_PRIVATE_KEY_JWT} — hex as identity-provider portals show
     * it (colons allowed), or base64url. Public data: it identifies the
     * certificate, it does not prove anything.
     */
    private String certificateThumbprint;

    /**
     * Which header the thumbprint travels in: {@link #THUMBPRINT_X5T_S256}
     * (default) or {@link #THUMBPRINT_X5T}. The hash must match the header — a
     * SHA-1 thumbprint in {@code x5t#S256} is refused at save time.
     */
    private String certificateThumbprintHeader = THUMBPRINT_X5T_S256;

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

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getPrivateKeyId() {
        return privateKeyId;
    }

    public void setPrivateKeyId(String privateKeyId) {
        this.privateKeyId = privateKeyId;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getCertificateThumbprint() {
        return certificateThumbprint;
    }

    public void setCertificateThumbprint(String certificateThumbprint) {
        this.certificateThumbprint = certificateThumbprint;
    }

    public String getCertificateThumbprintHeader() {
        return certificateThumbprintHeader;
    }

    public void setCertificateThumbprintHeader(String certificateThumbprintHeader) {
        this.certificateThumbprintHeader = certificateThumbprintHeader;
    }

    /**
     * Whether the client authenticates at the token endpoint with a signed
     * assertion rather than a client secret. Not a bean getter, so it never appears
     * in the stored document.
     */
    public boolean usesPrivateKeyJwt() {
        return CLIENT_AUTH_PRIVATE_KEY_JWT.equals(clientAuthMethod);
    }

    /**
     * Whether a connection of this type signs with {@link #privateKey} rather than
     * sending {@link #clientSecret}: always for {@link AuthType#OAUTH2_JWT_BEARER},
     * and for the other OAuth types when the client authenticates with
     * {@link #CLIENT_AUTH_PRIVATE_KEY_JWT}.
     */
    public boolean signsWithPrivateKey(AuthType authType) {
        return authType == AuthType.OAUTH2_JWT_BEARER || usesPrivateKeyJwt();
    }

    /**
     * The reference whose vault value proves this client to the token endpoint —
     * {@link #privateKey} or {@link #clientSecret}. One answer for every caller
     * that resolves it, so the token service and the authorization callback cannot
     * disagree about which secret a connection uses.
     */
    public String clientCredentialReference(AuthType authType) {
        return signsWithPrivateKey(authType) ? privateKey : clientSecret;
    }

    /**
     * How a refusal names {@link #clientCredentialReference(AuthType)}.
     */
    public String clientCredentialDescription(AuthType authType) {
        return signsWithPrivateKey(authType) ? "private key" : "client secret";
    }
}
