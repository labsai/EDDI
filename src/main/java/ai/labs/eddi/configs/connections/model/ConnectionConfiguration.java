/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.model;

import ai.labs.eddi.connections.model.ConnectionReference;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

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
 * <h3>Everything secret is a reference</h3> {@code clientSecret} and
 * {@code passwordRef} must be exactly one {@code ${vault:…}} or
 * {@code ${vars:…}} reference. A {@code valueTemplate} may add literal text
 * around its references — a scheme — but that text is bounded and checked for a
 * credential shape too (see {@link #requireTemplateIsReferenceOnly}), or a
 * literal key with a reference stapled on would pass. A plaintext key in a
 * connection document would sit outside the vault, outside export scrubbing and
 * outside {@code VaultGrantChecker}'s {@code ${vault:}} scan simultaneously —
 * one field defeating three controls.
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

    private static final Logger LOGGER = Logger.getLogger(ConnectionConfiguration.class);

    /**
     * A reference EDDI resolves at use time. Anchored and whole-segment: a value
     * that merely CONTAINS a reference is not one, or
     * {@code "sk-live-x${vault:unused}"} would pass as a reference while carrying a
     * literal key.
     */
    private static final Pattern REFERENCE_ONLY = Pattern.compile("\\$\\{(vault|eddivault|vars):[^}]{1,256}}");

    /**
     * The most literal text a {@code valueTemplate} may carry between or around its
     * references. Enough for a scheme ({@code "Bearer "}, {@code "SSWS "},
     * {@code "token="}); not enough for a key.
     */
    static final int MAX_TEMPLATE_LITERAL_CHARS = 32;

    /**
     * A run of key characters long enough to be a credential rather than a scheme.
     * Twelve is below every real API key format and above every scheme word
     * ({@code Bearer}, {@code Basic}, {@code Authorization} — 13, is the one it
     * refuses, and a template has no business carrying a header NAME in its value).
     */
    private static final Pattern CREDENTIAL_SHAPED_RUN = Pattern.compile("[A-Za-z0-9_\\-+/=.]{12,}");

    /** How much of an offending literal a refusal quotes back. */
    private static final int QUOTED_LITERAL_CHARS = 4;

    /**
     * What a connection may be called, spelled out so an author can read it back
     * from the refusal. Derived from the two places a name is parsed, neither of
     * which has a grammar of its own:
     * {@code ConnectionReference.CONNECTION_PATTERN} stops at {@code /} and
     * {@code }}, and the caller-credential header is split at the first space. A
     * name carrying a space, a slash, a brace, a colon or surrounding whitespace
     * therefore saved and could never be referenced or supplied — or, with a slash,
     * resolved as somebody else's tenant.
     */
    public static final String NAME_GRAMMAR = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";

    private static final Pattern NAME = Pattern.compile(NAME_GRAMMAR);

    /** How much of an unusable name a refusal quotes back. */
    private static final int QUOTED_NAME_CHARS = 80;

    /**
     * Names that mark a value as credential-shaped, used to keep one out of
     * {@code extraAuthParams}. Same vocabulary as the export scrubber's, minus the
     * entropy heuristic — this is a write-boundary check on a small map, so it can
     * afford to be strict and say why.
     * <p>
     * Entries are in <em>normalized</em> form — lower case, with {@code -},
     * {@code .} and {@code _} removed — because that is the shape
     * {@link #validateExtraAuthParams()} compares against. An entry spelled the way
     * the parameter travels on the wire ({@code code_verifier}) matches nothing at
     * all, so keep new entries stripped.
     */
    private static final Set<String> CREDENTIAL_PARAM_NAMES = Set.of("apikey", "apitoken", "password", "passwd", "secret", "secretkey",
            "token", "accesstoken", "refreshtoken", "authorization", "auth", "credential", "credentials", "privatekey", "clientsecret",
            "assertion", "codeverifier");

    /**
     * Parameters of the authorization request that EDDI composes itself. Same
     * normalized form as {@link #CREDENTIAL_PARAM_NAMES}, and for the same reason:
     * {@code Redirect_uri} and {@code redirect-uri} are one name on the wire once a
     * provider has applied its own leniency. A value here would either be silently
     * ignored or override the one PKCE and the callback depend on — a
     * {@code redirect_uri} pointing elsewhere is an authorization code delivered
     * elsewhere.
     */
    private static final Set<String> RESERVED_OAUTH_PARAM_NAMES = Set.of("redirecturi", "state", "codechallenge", "codechallengemethod",
            "clientid", "responsetype", "codeverifier", "clientsecret");

    /** Longest value an extra authorization parameter may carry. */
    static final int MAX_EXTRA_AUTH_PARAM_VALUE_CHARS = 512;

    /**
     * Value prefixes no protocol parameter legitimately starts with and every
     * common credential format does: OpenAI/Stripe keys, Slack tokens, GitHub
     * tokens, AWS access key ids, JWTs, and a pasted {@code Authorization} header.
     * A small local list rather than the export scrubber's entropy heuristic — this
     * is a write-boundary check on a handful of short values, so it can afford to
     * refuse by shape and say so.
     */
    private static final Pattern CREDENTIAL_SHAPED_VALUE = Pattern
            .compile("^(?:sk-|xox[abpsre]-|gh[pousr]_|github_pat_|AKIA|eyJ|(?i:bearer|basic)\\s)");

    /** Referenced as {@code ${connection:name}}. */
    private String name;

    /**
     * Defaults to {@code "default"} until multi-tenancy Phase 1 lands, at which
     * point it is populated from {@code TenantContext} rather than from the
     * document. Present now so the stored shape does not have to change then.
     */
    private String tenantId = ConnectionReference.DEFAULT_TENANT;

    /** Free text for whoever reads the connection list. */
    private String description;

    private AuthType authType = AuthType.STATIC;

    private Binding binding = Binding.SERVICE;

    /**
     * Whether a {@link Binding#PER_USER} grant may be resolved for a user id EDDI
     * itself never authenticated, on the grounds that a trusted front proxy did.
     * <p>
     * The risk this accepts, stated plainly: with this on, anyone who can assert a
     * user id to the fronting proxy can resolve <em>that user's</em> stored SaaS
     * credentials. Nothing checks the assertion afterwards — the id is the entire
     * authority for choosing whose refresh token to spend, so a proxy that lets a
     * caller pick the id it forwards hands out other people's live tokens. It
     * belongs only to a deployment that genuinely authenticates its users upstream.
     * <p>
     * Default off, and per connection rather than per deployment, so switching it
     * on is a decision about one provider's tokens instead of a global posture:
     * proxy identity can be good enough for a calendar connection while the finance
     * one still demands a principal EDDI verified itself.
     */
    private boolean allowUnverifiedPrincipal;

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

    /**
     * Timeout for the token endpoint,
     * {@value #MIN_TIMEOUT_MS}..{@value #MAX_TIMEOUT_MS} ms. Null means the
     * resolver's default.
     */
    private Integer timeoutMs;

    /** Lower bound of {@link #timeoutMs}. */
    public static final int MIN_TIMEOUT_MS = 1;

    /**
     * Upper bound of {@link #timeoutMs}: a token endpoint that takes a minute is
     * down.
     */
    public static final int MAX_TIMEOUT_MS = 60_000;

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
        // Not trimmed: a name saved with whitespace the author cannot see is a name
        // that resolves for nobody, and silently storing something other than what
        // was sent is a worse surprise than refusing it.
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Connection name '" + quoteName(name) + "' is not usable. A name must match " + NAME_GRAMMAR
                    + ": letters, digits, '.', '_' and '-', starting with a letter or digit, at most 64 characters, with no surrounding "
                    + "whitespace. It is what ${connection:name} refers to and what a caller names in the X-EDDI-Connection-Credential "
                    + "header, so a space, '/', '}' or ':' in it could never be referenced or supplied.");
        }
        if (authType == null) {
            throw new IllegalArgumentException("authType is required (STATIC, BASIC, OAUTH2_CLIENT_CREDENTIALS or OAUTH2_AUTHORIZATION_CODE).");
        }
        validateBinding();
        validateUnverifiedPrincipalFlag();
        validateAllowlist();
        validateTimeout();
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
        if (binding == Binding.CALLER_SUPPLIED && authType != AuthType.STATIC) {
            throw new IllegalArgumentException("CALLER_SUPPLIED binding requires authType STATIC. The caller hands over a finished header "
                    + "value, so there is nothing for EDDI to encode, exchange or refresh — a BASIC username or an oauth block on such a "
                    + "connection would read as load-bearing and be silently ignored.");
        }
        if (authType == AuthType.OAUTH2_AUTHORIZATION_CODE && binding != Binding.PER_USER) {
            throw new IllegalArgumentException("authType OAUTH2_AUTHORIZATION_CODE requires binding PER_USER. The flow files its grant under "
                    + "the user who completed the consent screen, so a SERVICE-bound one would look for a grant under the service principal "
                    + "that nothing can ever create — it would save and deploy and then fail every call as 'not connected'. Use "
                    + "OAUTH2_CLIENT_CREDENTIALS for a service account.");
        }
    }

    /**
     * The flag has nothing to relax unless the binding is per user.
     * <p>
     * Refused rather than ignored. A flag whose name promises to loosen an identity
     * check, sitting on a document where it does nothing, reads to the next person
     * as a deliberate posture that is already in force — so the day the binding
     * changes, a relaxation nobody re-decided comes into force with it.
     */
    private void validateUnverifiedPrincipalFlag() {
        if (allowUnverifiedPrincipal && binding != Binding.PER_USER) {
            throw new IllegalArgumentException("allowUnverifiedPrincipal applies only to binding PER_USER: it relaxes WHICH end user's grant "
                    + "may be resolved, and any other binding resolves the same credential for everybody regardless of who is asking. Remove "
                    + "the flag, or set binding PER_USER if this connection really is per end user.");
        }
    }

    private void validateAllowlist() {
        if (baseUrlAllowlist == null || baseUrlAllowlist.isEmpty()) {
            throw new IllegalArgumentException("baseUrlAllowlist is required: a connection must name the origins its credential may be sent to, "
                    + "or a config edit can redirect that credential to any host.");
        }
        for (String origin : baseUrlAllowlist) {
            String canonical = requireCanonicalOrigin(origin, "baseUrlAllowlist");
            // Accepted, deliberately: an internal service behind a private network is
            // a real deployment and refusing it would only push authors to put the
            // credential somewhere with no allowlist at all. But it is a credential
            // crossing the network unencrypted, so it is said out loud at the moment
            // somebody can still change their mind — and again at boot, by
            // ConnectionStartupGuard, for a document that arrived some other way.
            if (isPlaintextRemoteOrigin(canonical)) {
                LOGGER.warnf("[CONNECTIONS] Connection '%s' allows its credential to be sent over plaintext http to %s. Accepted, but "
                        + "the credential crosses the network unencrypted; prefer an https origin.", sanitize(name), canonical);
            }
        }
    }

    /**
     * Whether a canonical origin sends traffic in the clear to something other than
     * this host. Loopback is exempt: {@code http://localhost} never leaves the
     * machine, and it is the normal shape of a development setup.
     */
    public static boolean isPlaintextRemoteOrigin(String canonicalOrigin) {
        if (canonicalOrigin == null || !canonicalOrigin.startsWith("http://")) {
            return false;
        }
        String host = URI.create(canonicalOrigin).getHost();
        return host != null && !isLoopbackHost(host);
    }

    /**
     * {@code localhost}, {@code 127.0.0.1} or {@code ::1}, as {@link URI#getHost()}
     * renders them.
     */
    public static boolean isLoopbackHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(lower) || "127.0.0.1".equals(lower) || "[::1]".equals(lower) || "::1".equals(lower);
    }

    /**
     * A bound rather than a clamp. {@code OAuthTokenClient} already clamps the
     * value it uses, so an out-of-range document worked — silently, at a timeout
     * the author did not write. Refusing at save time is the only place the author
     * finds out.
     */
    private void validateTimeout() {
        if (timeoutMs != null && (timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS)) {
            throw new IllegalArgumentException("timeoutMs must be between " + MIN_TIMEOUT_MS + " and " + MAX_TIMEOUT_MS
                    + " milliseconds; got: " + timeoutMs + ". Leave it unset for the resolver's default.");
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
        // CALLER_SUPPLIED inverts the rule the two lines below enforce: the value
        // arrives with each request, so a stored template is either dead config or a
        // second credential racing the supplied one — and whichever won would do so by
        // resolution order, silently. Refusing is the only reading that cannot
        // surprise.
        // headerName is still required, and still checked above: the connection owns
        // the
        // header name whoever supplies its value.
        if (binding == Binding.CALLER_SUPPLIED) {
            if (staticAuth.getValueTemplate() != null && !staticAuth.getValueTemplate().isBlank()) {
                throw new IllegalArgumentException("CALLER_SUPPLIED must not set staticAuth.valueTemplate: the caller supplies the value on "
                        + "each request, so a stored one would either never be read or quietly take precedence over theirs.");
            }
            if (staticAuth.getUsername() != null && !staticAuth.getUsername().isBlank()) {
                throw new IllegalArgumentException("CALLER_SUPPLIED must not set staticAuth.username: it belongs to BASIC, which this "
                        + "binding does not allow.");
            }
            if (staticAuth.getPasswordRef() != null && !staticAuth.getPasswordRef().isBlank()) {
                throw new IllegalArgumentException("CALLER_SUPPLIED must not set staticAuth.passwordRef: the credential comes from the "
                        + "caller, not the vault.");
            }
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

    /**
     * Refuses a credential-shaped name among the extra parameters appended to the
     * authorization URL.
     * <p>
     * The name is normalized before the lookup, so a denylisted parameter is caught
     * however a config spells it: {@code code_verifier}, {@code Code-Verifier} and
     * {@code codeverifier} are one name here. That normalization is also why
     * {@link #CREDENTIAL_PARAM_NAMES} is written stripped — an entry in wire
     * spelling matches nothing, which is how a {@code code_verifier} parameter
     * reached the authorization URL, and with it the browser history, the
     * {@code Referer} and every proxy log in front of the provider. That is the one
     * place a PKCE verifier must not travel: it is the secret half of a pair whose
     * whole point is that only the challenge is public.
     */
    private void validateExtraAuthParams() {
        Map<String, String> params = oauth.getExtraAuthParams();
        if (params == null) {
            return;
        }
        for (Map.Entry<String, String> param : params.entrySet()) {
            String key = param.getKey();
            if (key == null) {
                continue;
            }
            String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[\\-._]", "");
            if (CREDENTIAL_PARAM_NAMES.contains(normalized)) {
                throw new IllegalArgumentException("oauth.extraAuthParams may carry only non-secret protocol parameters (prompt, audience, …). '"
                        + key + "' is credential-shaped; store it with POST /secretstore/secrets and reference it instead.");
            }
            if (RESERVED_OAUTH_PARAM_NAMES.contains(normalized)) {
                throw new IllegalArgumentException("oauth.extraAuthParams must not set '" + key + "': it is a protocol parameter EDDI "
                        + "composes itself when it builds the authorization request (redirect_uri, state, code_challenge, "
                        + "code_challenge_method, client_id, response_type). A value here would be ignored or would override the one the "
                        + "callback and PKCE depend on.");
            }
            requireProtocolParameterValue(key, param.getValue());
        }
    }

    /**
     * The value half of the same check. The docs promised the map "is checked too",
     * and the keys were — but a key called {@code prompt} carrying a pasted API key
     * sailed through, straight into a plaintext field of the document. So: no
     * reference (a {@code ${vault:…}} here would be RESOLVED into the authorization
     * URL, which the browser history, the {@code Referer} and every proxy log see),
     * a bounded length, and no credential-shaped prefix.
     */
    private static void requireProtocolParameterValue(String key, String value) {
        if (value == null) {
            throw new IllegalArgumentException("oauth.extraAuthParams['" + key + "'] has no value. A parameter with nothing to send does "
                    + "not belong in the authorization request; remove it.");
        }
        if (value.contains("${")) {
            throw new IllegalArgumentException("oauth.extraAuthParams['" + key + "'] must not carry a ${…} reference: the authorization URL "
                    + "is opened in the user's browser, so a resolved value would land in browser history, the Referer and every proxy log "
                    + "in front of the provider. Only literal, non-secret protocol values belong here.");
        }
        if (value.length() > MAX_EXTRA_AUTH_PARAM_VALUE_CHARS) {
            throw new IllegalArgumentException("oauth.extraAuthParams['" + key + "'] is " + value.length() + " characters long; a protocol "
                    + "parameter is at most " + MAX_EXTRA_AUTH_PARAM_VALUE_CHARS + ". Anything longer is being smuggled through the "
                    + "authorization URL.");
        }
        if (CREDENTIAL_SHAPED_VALUE.matcher(value).find()) {
            throw new IllegalArgumentException("oauth.extraAuthParams['" + key + "'] looks like a credential ('" + quoteLiteral(value)
                    + "'). The map may carry only non-secret protocol parameters (prompt, audience, access_type, …); store a credential "
                    + "with POST /secretstore/secrets and reference it from the field that expects it.");
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
     * ${vault:k}"} — under three rules, all of them about the LITERAL text, because
     * the references were always checked and the literal text was not:
     * <ol>
     * <li>every {@code ${} is a well-formed {@code ${vault:…}} or {@code ${vars:…}}
     * reference. An unknown prefix, an empty key, an unclosed brace or a key over
     * 256 characters used to fall outside the interpolation pattern and count as
     * literal text — which the old check never looked at;</li>
     * <li>at least one reference is present, or the whole value is a plaintext
     * credential wearing a template's clothes;</li>
     * <li>each literal segment between or around the references is at most {@value
     * #MAX_TEMPLATE_LITERAL_CHARS} characters and contains no run of twelve or more
     * key characters ({@code [A-Za-z0-9_\-+/=.]}). {@code "Bearer "}, {@code "Basic
     * "}, {@code "token="} and {@code "SSWS "} pass; {@code
     * "sk-live-abcdef${vault:unused}"} — a literal key with a reference stapled on
     * to satisfy the pattern — does not.</li>
     * </ol>
     * The offending literal is quoted back redacted to its first {@value
     * #QUOTED_LITERAL_CHARS} characters: it is the one string in the document that
     * may be a credential.
     */
    private static void requireTemplateIsReferenceOnly(String template) {
        Matcher matcher = REFERENCE_ONLY.matcher(template);
        List<String> literals = new ArrayList<>();
        int cursor = 0;
        boolean sawReference = false;
        while (matcher.find()) {
            literals.add(template.substring(cursor, matcher.start()));
            cursor = matcher.end();
            sawReference = true;
        }
        literals.add(template.substring(cursor));
        for (String literal : literals) {
            int stray = literal.indexOf("${");
            if (stray >= 0) {
                throw new IllegalArgumentException("staticAuth.valueTemplate may only interpolate ${vault:…} or ${vars:…}; the interpolation "
                        + "at '" + quoteLiteral(literal.substring(stray)) + "' is not a well-formed reference (unknown prefix, empty key, "
                        + "unclosed brace, or a key over 256 characters).");
            }
        }
        if (!sawReference) {
            throw new IllegalArgumentException("staticAuth.valueTemplate contains no ${vault:…} reference, so it is a plaintext credential. "
                    + "Store it with POST /secretstore/secrets and reference it here.");
        }
        for (String literal : literals) {
            if (literal.length() > MAX_TEMPLATE_LITERAL_CHARS) {
                throw new IllegalArgumentException("staticAuth.valueTemplate carries " + literal.length() + " characters of literal text ('"
                        + quoteLiteral(literal) + "') around its references; a literal segment may hold at most " + MAX_TEMPLATE_LITERAL_CHARS
                        + " characters — a scheme such as 'Bearer ' — so a plaintext credential cannot ride alongside a reference. Store the "
                        + "value with POST /secretstore/secrets and reference it here.");
            }
            Matcher run = CREDENTIAL_SHAPED_RUN.matcher(literal);
            if (run.find()) {
                throw new IllegalArgumentException("staticAuth.valueTemplate carries a credential-shaped literal ('" + quoteLiteral(run.group())
                        + "') around its references — a run of twelve or more key characters. Literal text may only carry a scheme such as "
                        + "'Bearer '; store the value with POST /secretstore/secrets and reference it here.");
            }
        }
    }

    /**
     * A name is not secret, but an unbounded one has no place in an error message.
     */
    private static String quoteName(String candidate) {
        return candidate.length() <= QUOTED_NAME_CHARS ? candidate : candidate.substring(0, QUOTED_NAME_CHARS) + "…";
    }

    /**
     * The first few characters of a literal that may be a credential, and no more.
     */
    private static String quoteLiteral(String literal) {
        String trimmed = literal.strip();
        return trimmed.length() <= QUOTED_LITERAL_CHARS ? trimmed : trimmed.substring(0, QUOTED_LITERAL_CHARS) + "…";
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

    /**
     * {@code scheme://host[:port]}, lowercased, with no trailing slash and the
     * scheme's default port folded away.
     */
    public static String canonicalOrigin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = normalizePort(scheme, uri.getPort());
        return port < 0 ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }

    /**
     * The port, or {@code -1} when it is the scheme's own default.
     * <p>
     * {@code https://api.example.com} and {@code https://api.example.com:443} are
     * one origin, and a comparison that says otherwise refuses a target the
     * allowlist was written to permit — an allowlist that looks correct and blocks
     * everything, which is precisely the failure canonicalisation exists to
     * prevent. Exposed because the same fold is needed wherever a URL of this
     * deployment is compared to another, not only inside an origin string.
     */
    public static int normalizePort(String scheme, int port) {
        if (port < 0) {
            return -1;
        }
        String lower = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        if (("https".equals(lower) && port == 443) || ("http".equals(lower) && port == 80)) {
            return -1;
        }
        return port;
    }

    /**
     * The tenant a connection belongs to, with the default applied.
     * <p>
     * One helper rather than a ternary per call site: the grants, the OAuth state
     * rows and the registry cache key are all filed under this exact string, so two
     * sites that spell the default differently file one connection's data under two
     * tenants and neither can find the other's.
     */
    public static String effectiveTenant(ConnectionConfiguration connection) {
        return connection == null ? ConnectionReference.DEFAULT_TENANT : effectiveTenant(connection.getTenantId());
    }

    /** @see #effectiveTenant(ConnectionConfiguration) */
    public static String effectiveTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? ConnectionReference.DEFAULT_TENANT : tenantId;
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

    public boolean isAllowUnverifiedPrincipal() {
        return allowUnverifiedPrincipal;
    }

    public void setAllowUnverifiedPrincipal(boolean allowUnverifiedPrincipal) {
        this.allowUnverifiedPrincipal = allowUnverifiedPrincipal;
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
