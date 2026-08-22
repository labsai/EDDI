/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How to authenticate to one external system.
 * <p>
 * One resource type instead of an {@code oauth} block bolted onto each of the
 * five existing credential-resolution sites. Those five each have their own
 * {@code apiKey}-shaped field and their own
 * {@code globalVariableResolver → secretResolver} snippet; adding OAuth to each
 * separately is a five-times problem with five refresh-concurrency bugs. A
 * connection is referenced as {@code ${connection:name}} and resolved <em>per
 * request</em>, which is what lets one model cover both an org-wide API key and
 * a per-end-user OAuth grant.
 *
 * <h3>Everything secret is a reference</h3> {@code clientSecret},
 * {@code passwordRef} and any interpolated segment of a {@code valueTemplate}
 * must be a {@code ${vault:…}} or {@code ${vars:…}} reference. A plaintext key
 * in a connection document would sit outside the vault, outside export
 * scrubbing and outside {@code VaultGrantChecker}'s {@code ${vault:}} scan
 * simultaneously — one field defeating three controls.
 *
 * <h3>Two separate allowlists</h3> {@code baseUrlAllowlist} says where the
 * ACCESS TOKEN may be sent. Credential endpoints — {@code tokenUrl},
 * {@code authorizationUrl}, {@code discoveryUrl} — are validated against their
 * own operator-managed list, because the vault-resolved {@code clientSecret} is
 * sent to {@code tokenUrl}: an unvalidated token URL is a direct client-secret
 * exfiltration path, strictly worse than a misdirected access token. Their
 * origins routinely differ from the API's (Atlassian:
 * {@code auth.atlassian.com} versus {@code api.atlassian.com}), which is why
 * folding them into one list does not work.
 */
public class ConnectionConfiguration {

    /**
     * A reference EDDI resolves at use time. Anchored and whole-segment: a value
     * that merely CONTAINS a reference is not one, or
     * {@code "sk-live-x${vault:unused}"} would pass as a reference while carrying a
     * literal key.
     */
    private static final Pattern REFERENCE_ONLY = Pattern.compile("\\$\\{(vault|eddivault|vars):[^}]+}");

    /** Interpolated segments inside a header value template. */
    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{[^}]*}");

    /**
     * Names that mark a value as credential-shaped, used to keep one out of
     * {@code extraAuthParams}. Same vocabulary as the export scrubber's, minus the
     * entropy heuristic — this is a write-boundary check on a small map, so it can
     * afford to be strict and say why.
     */
    private static final Set<String> CREDENTIAL_PARAM_NAMES = Set.of("apikey", "api_key", "apitoken", "api_token", "password", "passwd", "secret",
            "secretkey", "secret_key", "token", "accesstoken", "access_token", "refreshtoken", "refresh_token", "authorization", "auth",
            "credential", "credentials", "privatekey", "private_key", "clientsecret", "client_secret", "assertion", "code_verifier");

    /** Referenced as {@code ${connection:name}}. */
    private String name;

    /**
     * Defaults to {@code "default"} until multi-tenancy Phase 1 lands, at which
     * point it is populated from {@code TenantContext} rather than from the
     * document. Present now so the stored shape does not have to change then.
     */
    private String tenantId = "default";

    /** Free text for whoever reads the connection list. */
    private String description;

    private AuthType authType = AuthType.STATIC;

    private Binding binding = Binding.SERVICE;

    private StaticAuth staticAuth;

    private OAuthConfig oauth;

    /**
     * Origins this connection's credential may be sent to. A list because one
     * provider's credential legitimately spans hosts (Google:
     * {@code https://www.googleapis.com}, {@code https://drive.googleapis.com}, …).
     * <p>
     * This is the generalisation of the same-origin rule that makes
     * {@code ${caller:token}} safe: a connection names where its credential may go,
     * so a config edit cannot redirect a Google token to an attacker's host.
     */
    private List<String> baseUrlAllowlist = new ArrayList<>();

    /** Timeout for the token endpoint. Null means the resolver's default. */
    private Integer timeoutMs;

    /**
     * Rejects a connection the engine cannot honour safely.
     *
     * @throws IllegalArgumentException
     *             naming the field and the fix
     */
    public void validate() {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A connection needs a name — it is what ${connection:name} refers to.");
        }
        if (authType == null) {
            throw new IllegalArgumentException("authType is required (STATIC, BASIC, OAUTH2_CLIENT_CREDENTIALS or OAUTH2_AUTHORIZATION_CODE).");
        }
        validateBinding();
        validateAllowlist();
        switch (authType) {
            case STATIC, BASIC -> validateStaticAuth();
            case OAUTH2_CLIENT_CREDENTIALS, OAUTH2_AUTHORIZATION_CODE -> validateOAuth();
        }
    }

    /**
     * The two halves of one rule, and both are needed.
     * <p>
     * The first was here from the start. The second was not, and its absence made
     * the DEFAULT configuration of an authorization-code connection a dead one:
     * {@code binding} defaults to {@code SERVICE}, so an author who wrote an
     * authorization-code block and did not think about binding got a connection
     * that saved cleanly, deployed cleanly, offered its users a working consent
     * screen — and then resolved every call against the {@code __service__}
     * principal, which no authorization-code flow can ever produce a grant for. The
     * symptom is "not connected" for a user who just connected, with nothing
     * anywhere naming the cause.
     */
    private void validateBinding() {
        if (binding == Binding.PER_USER && authType != AuthType.OAUTH2_AUTHORIZATION_CODE) {
            throw new IllegalArgumentException("PER_USER binding requires authType OAUTH2_AUTHORIZATION_CODE — it is the only flow that "
                    + "produces a grant per end user. A static key is the same key for everybody however it is bound.");
        }
        if (authType == AuthType.OAUTH2_AUTHORIZATION_CODE && binding != Binding.PER_USER) {
            throw new IllegalArgumentException("authType OAUTH2_AUTHORIZATION_CODE requires binding PER_USER. The flow files its grant under "
                    + "the user who completed the consent screen, so a SERVICE-bound one would look for a grant under the service principal "
                    + "that nothing can ever create — it would save and deploy and then fail every call as 'not connected'. Use "
                    + "OAUTH2_CLIENT_CREDENTIALS for a service account.");
        }
    }

    private void validateAllowlist() {
        if (baseUrlAllowlist == null || baseUrlAllowlist.isEmpty()) {
            throw new IllegalArgumentException("baseUrlAllowlist is required: a connection must name the origins its credential may be sent to, "
                    + "or a config edit can redirect that credential to any host.");
        }
        for (String origin : baseUrlAllowlist) {
            requireCanonicalOrigin(origin, "baseUrlAllowlist");
        }
    }

    private void validateStaticAuth() {
        if (staticAuth == null) {
            throw new IllegalArgumentException("authType " + authType + " requires a staticAuth block.");
        }
        if (staticAuth.getHeaderName() == null || staticAuth.getHeaderName().isBlank()) {
            throw new IllegalArgumentException("staticAuth.headerName is required.");
        }
        if (authType == AuthType.BASIC) {
            if (staticAuth.getUsername() == null || staticAuth.getUsername().isBlank()) {
                throw new IllegalArgumentException("BASIC requires staticAuth.username.");
            }
            requireReference(staticAuth.getPasswordRef(), "staticAuth.passwordRef");
            return;
        }
        if (staticAuth.getValueTemplate() == null || staticAuth.getValueTemplate().isBlank()) {
            throw new IllegalArgumentException("STATIC requires staticAuth.valueTemplate, e.g. \"Bearer ${vault:jira-token}\".");
        }
        requireTemplateIsReferenceOnly(staticAuth.getValueTemplate());
    }

    private void validateOAuth() {
        if (oauth == null) {
            throw new IllegalArgumentException("authType " + authType + " requires an oauth block.");
        }
        requireCredentialEndpoint(oauth.getTokenUrl(), "oauth.tokenUrl", true);
        requireCredentialEndpoint(oauth.getDiscoveryUrl(), "oauth.discoveryUrl", false);
        if (oauth.getClientId() == null || oauth.getClientId().isBlank()) {
            throw new IllegalArgumentException("oauth.clientId is required.");
        }
        requireReference(oauth.getClientSecret(), "oauth.clientSecret");
        if (authType == AuthType.OAUTH2_AUTHORIZATION_CODE) {
            requireCredentialEndpoint(oauth.getAuthorizationUrl(), "oauth.authorizationUrl", true);
            if (!oauth.isUsePkce()) {
                throw new IllegalArgumentException("PKCE is mandatory for OAUTH2_AUTHORIZATION_CODE. The callback is a permit path, and without "
                        + "PKCE it is an authorization-code interception vector.");
            }
        }
        String method = oauth.getClientAuthMethod();
        if (method != null && !OAuthConfig.CLIENT_AUTH_BASIC.equals(method) && !OAuthConfig.CLIENT_AUTH_POST.equals(method)) {
            throw new IllegalArgumentException("oauth.clientAuthMethod must be " + OAuthConfig.CLIENT_AUTH_BASIC + " or "
                    + OAuthConfig.CLIENT_AUTH_POST + ", got: " + method);
        }
        validateExtraAuthParams();
    }

    private void validateExtraAuthParams() {
        Map<String, String> params = oauth.getExtraAuthParams();
        if (params == null) {
            return;
        }
        for (String key : params.keySet()) {
            if (key == null) {
                continue;
            }
            String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[\\-._]", "");
            if (CREDENTIAL_PARAM_NAMES.contains(normalized) || CREDENTIAL_PARAM_NAMES.contains(normalized.replace("_", ""))) {
                throw new IllegalArgumentException("oauth.extraAuthParams may carry only non-secret protocol parameters (prompt, audience, …). '"
                        + key + "' is credential-shaped; store it with POST /secretstore/secrets and reference it instead.");
            }
        }
    }

    /**
     * A value that is exactly one reference, and nothing else.
     * <p>
     * {@code matches}, not {@code find}: a value that merely contains a reference
     * is not one, and treating it as one would let
     * {@code "sk-live-x${vault:unused}"} through with a literal key in it.
     */
    private static void requireReference(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required, as a ${vault:…} reference.");
        }
        if (!REFERENCE_ONLY.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException(field + " must be a ${vault:…} or ${vars:…} reference, not a literal. Store the value with "
                    + "POST /secretstore/secrets and reference it here — a literal here bypasses the vault, export scrubbing and deploy-time "
                    + "grant enforcement at once.");
        }
    }

    /**
     * A header template may mix literal text with references — {@code "Bearer
     * ${vault:k}"} — as long as every interpolated segment is one. The literal text
     * between them is checked too: a template with no interpolation at all is a
     * plaintext credential wearing a template's clothes.
     */
    private static void requireTemplateIsReferenceOnly(String template) {
        Matcher matcher = INTERPOLATION.matcher(template);
        boolean sawReference = false;
        while (matcher.find()) {
            String segment = matcher.group();
            if (!REFERENCE_ONLY.matcher(segment).matches()) {
                throw new IllegalArgumentException("staticAuth.valueTemplate may only interpolate ${vault:…} or ${vars:…}; found: " + segment);
            }
            sawReference = true;
        }
        if (!sawReference) {
            throw new IllegalArgumentException("staticAuth.valueTemplate contains no ${vault:…} reference, so it is a plaintext credential. "
                    + "Store it with POST /secretstore/secrets and reference it here.");
        }
    }

    /**
     * A credential endpoint must be an absolute https URL. Parsed, not
     * prefix-matched: {@code startsWith("https://")} accepts userinfo, a query and
     * a fragment, any of which changes where the request actually goes.
     * <p>
     * Whether the ORIGIN is one an operator trusts is checked separately, against
     * the deployment-level allowlist — a per-connection document cannot be allowed
     * to vouch for its own token endpoint.
     */
    private static void requireCredentialEndpoint(String url, String field, boolean required) {
        if (url == null || url.isBlank()) {
            if (required) {
                throw new IllegalArgumentException(field + " is required for an OAuth connection (OAUTH2_CLIENT_CREDENTIALS or "
                        + "OAUTH2_AUTHORIZATION_CODE).");
            }
            return;
        }
        URI parsed = parse(url, field);
        if (!"https".equals(parsed.getScheme())) {
            throw new IllegalArgumentException(field + " must use https — the client secret is sent to it: " + url);
        }
        if (parsed.getUserInfo() != null) {
            throw new IllegalArgumentException(field + " must not carry userinfo: " + url);
        }
        if (parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw new IllegalArgumentException(field + " must be an absolute URL with a host: " + url);
        }
    }

    /**
     * A bare origin — {@code scheme://host[:port]}, lowercase, no path, query,
     * fragment, userinfo or trailing slash.
     * <p>
     * Parsed and re-serialized rather than string-compared, so
     * {@code api.atlassian.com} (no scheme) fails loudly here instead of silently
     * never matching at resolve time — which would look like a working allowlist
     * that blocks everything, or worse, be "fixed" by loosening the comparison.
     */
    public static String requireCanonicalOrigin(String origin, String field) {
        if (origin == null || origin.isBlank()) {
            throw new IllegalArgumentException(field + " entries must be non-empty origins, e.g. https://api.example.com");
        }
        URI parsed = parse(origin, field);
        String scheme = parsed.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(field + " entries must be a bare origin with an http or https scheme — got: " + origin);
        }
        if (parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw new IllegalArgumentException(field + " entries must name a host — got: " + origin);
        }
        if (parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getFragment() != null
                || (parsed.getPath() != null && !parsed.getPath().isEmpty() && !"/".equals(parsed.getPath()))) {
            throw new IllegalArgumentException(field + " entries must be a BARE origin (scheme://host[:port]) with no path, query, fragment or "
                    + "userinfo — got: " + origin);
        }
        return canonicalOrigin(parsed);
    }

    /** {@code scheme://host[:port]}, lowercased, with no trailing slash. */
    public static String canonicalOrigin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        return port < 0 ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }

    private static URI parse(String value, String field) {
        try {
            return new URI(value.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(field + " is not a valid URL: " + value, e);
        }
    }

    // --- Getters and Setters ---

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public AuthType getAuthType() {
        return authType;
    }

    public void setAuthType(AuthType authType) {
        this.authType = authType;
    }

    public Binding getBinding() {
        return binding;
    }

    public void setBinding(Binding binding) {
        this.binding = binding;
    }

    public StaticAuth getStaticAuth() {
        return staticAuth;
    }

    public void setStaticAuth(StaticAuth staticAuth) {
        this.staticAuth = staticAuth;
    }

    public OAuthConfig getOauth() {
        return oauth;
    }

    public void setOauth(OAuthConfig oauth) {
        this.oauth = oauth;
    }

    public List<String> getBaseUrlAllowlist() {
        return baseUrlAllowlist;
    }

    public void setBaseUrlAllowlist(List<String> baseUrlAllowlist) {
        this.baseUrlAllowlist = baseUrlAllowlist;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
