/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import java.util.Locale;

/**
 * Removes credential material from a URI, a query parameter, or a header judged
 * by its name.
 * <p>
 * Extracted from {@code RequestRedactor}, which still owns the request-shaped
 * API and delegates here, so there stays exactly ONE definition of each rule.
 * The extraction exists because a second consumer appeared on the other side of
 * the dependency arrow: {@link SecretScrubber} runs on the agent EXPORT path
 * and needs the same URI rules, and having {@code secrets} import
 * {@code modules.apicalls} — which already imports {@code secrets} — would have
 * made the two packages mutually dependent to share sixty lines of subtle
 * string handling. Copying them instead is worse: {@code RequestRedactor}'s own
 * class comment records that these rules drifted apart once already, and each
 * drift was a leak through whichever path was forgotten.
 * <p>
 * Every method is static and stateless; nothing here needs CDI.
 */
public final class UriRedactor {

    /** What a redacted value is replaced with. */
    public static final String REDACTED = "<REDACTED>";

    private UriRedactor() {
    }

    /** Redact secret-shaped values out of an arbitrary string. */
    static String redactBody(String body) {
        return SecretRedactionFilter.redact(body);
    }

    /**
     * Whether a header carries credential material, judged by its name.
     * <p>
     * {@code Locale.ROOT}, not the default locale: under a Turkish locale
     * "Authorization" lowercases to "authorızation" (dotless i), every test below
     * misses, and the header is persisted unredacted.
     */
    public static boolean isSensitiveHeaderName(String headerName) {
        if (headerName == null) {
            return false;
        }
        String name = headerName.toLowerCase(Locale.ROOT);
        return name.contains("authorization") || name.contains("api-key") || name.contains("api_key") || name.contains("apikey")
                || name.contains("x-api-key") || name.contains("token") || name.contains("secret") || name.contains("credential")
                || name.contains("password");
    }

    /**
     * Redact a query parameter's value.
     * <p>
     * Judged by name like a header, and for the same reason: {@code ?api_key=…} or
     * {@code ?access_token=…} is a conventional way to pass a credential, and this
     * value is shown to an approver who is routinely not the person whose turn
     * raised the pause. Value-shape matching backs the name check up so a
     * credential under an unconventional name is still caught.
     */
    public static String redactQueryParamValue(String name, String value) {
        if (isSensitiveHeaderName(name)) {
            return REDACTED;
        }
        if (value == null) {
            return "";
        }
        if (value.contains("${vault:") || value.contains("${eddivault:")) {
            return REDACTED;
        }
        // No caller-token check, unlike a header: CallerIdentityResolver rejects
        // ${caller:token} outside headers outright, so a live caller token cannot
        // legitimately reach a query parameter. That is what lets this stay static
        // — and static is what lets ResolvedRequest#of apply it itself, keeping
        // "fingerprint the raw, store the redacted" in one place.
        return redactBody(value);
    }

    /**
     * Shape-scan the part of a URI before any query string, WITHOUT letting the
     * scan eat the authority.
     * <p>
     * {@code SecretRedactionFilter}'s generic rule matches
     * {@code (api_key|token|secret|password|authorization)[=:]<8+ chars>}, and its
     * trailing character class does not exclude {@code /}. Run over a whole URI
     * that scan consumes to the end of the string the moment the host itself ends
     * in one of those words followed by a port — plausible for an in-cluster
     * service name — so {@code https://vault-secret:8200/v1/agents/a1} collapsed to
     * {@code https://vault-secret=<REDACTED>}. That is worse than the leak it
     * guards: the approver loses the method's target entirely, and what is left is
     * not even a URI. Over-redaction hides what is being written to; a human who
     * cannot see the target cannot approve it.
     * <p>
     * So the scheme and authority are held aside and the scan is applied only to
     * the path, where a templated credential can actually land.
     */
    private static String redactUpToQuery(String beforeQuery) {
        int schemeEnd = beforeQuery.indexOf("://");
        if (schemeEnd < 0) {
            // Relative or scheme-less: it is all path.
            return redactBody(beforeQuery);
        }
        int authorityStart = schemeEnd + 3;
        int pathStart = beforeQuery.indexOf('/', authorityStart);
        String authority = pathStart < 0 ? beforeQuery.substring(authorityStart) : beforeQuery.substring(authorityStart, pathStart);
        String path = pathStart < 0 ? "" : beforeQuery.substring(pathStart);

        // The authority is kept verbatim EXCEPT its userinfo, which is bounded by
        // '@' so redacting it cannot run away into the host and path the way
        // scanning the whole authority did. Everything from '@' onward (host,
        // port) stays legible.
        //
        // The PASSWORD half is replaced outright rather than shape-scanned. A
        // shape scan only catches credentials that look like one — an sk- key, a
        // bearer token — and a password is by construction arbitrary text, so
        // `https://svc:hunter2@host` survived a scan that was doing exactly what
        // it was asked to. In `user:pass@host` the second half is a credential by
        // definition (RFC 3986 §3.2.1 deprecates the field for that reason), so
        // there is no false positive to trade away. Bare `user@host` is only a
        // username, and is shape-scanned as before rather than blanked, because
        // an approver reading the preview wants to see who the call runs as.
        int at = authority.lastIndexOf('@');
        String safeAuthority = at < 0 ? authority : redactUserInfo(authority.substring(0, at)) + authority.substring(at);

        return beforeQuery.substring(0, authorityStart) + safeAuthority + redactBody(path);
    }

    /**
     * Redacts the password half of a {@code user:password} userinfo component,
     * leaving a bare username legible.
     */
    private static String redactUserInfo(String userInfo) {
        int separator = userInfo.indexOf(':');
        if (separator < 0) {
            return redactBody(userInfo);
        }
        return redactBody(userInfo.substring(0, separator)) + ":" + REDACTED;
    }

    /**
     * Redact a request URI.
     * <p>
     * The URI was the one field of a resolved request that carried no redaction of
     * any kind, which made it the leak the rest of this class exists to prevent: a
     * credential templated into the path —
     * {@code "/v1/invoices?api_key=${vault:k}"} — is resolved to its live value by
     * {@code ApiCallExecutor#buildRequest} before the URI is ever built, and the
     * same value then appeared REDACTED in {@code queryParams} and PLAINTEXT in
     * {@code uri}, adjacent fields of one JSON object shown to an approver who is
     * routinely not the person whose turn raised the pause.
     * <p>
     * Two passes, because a URI has two places to hide one:
     * <ul>
     * <li>the query string is split and each value run through
     * {@link #redactQueryParamValue} — the SAME function the {@code queryParams}
     * map uses, so the two views of one credential cannot disagree;</li>
     * <li>whatever remains (scheme, userinfo, host, path) goes through
     * {@link #redactBody}'s value-shape scan, which catches
     * {@code https://user:sk-…@host} and a key segment inside a path.</li>
     * </ul>
     * <p>
     * Static and null-tolerant for the same reason as {@link #redactBody}:
     * {@link ResolvedRequest#of} applies it without an executor, keeping
     * "fingerprint the raw, store the redacted" resolved in exactly one place.
     */

    /**
     * Redact one query value from a URI, judging it in its DECODED form.
     * <p>
     * The scan has to see what the value actually is. {@code HttpClientWrapper}
     * decodes into {@code queryParamsMap}, but {@code toMap()} hands back the raw
     * {@code uri.toString()} — so the same credential arrives here still encoded,
     * and percent-encoding defeats the shape rules outright: a bearer token becomes
     * {@code Bearer%20aaaa…}, which the {@code Bearer\s+…} rule no longer matches,
     * and a {@code ${vault:…}} reference survives as {@code $%7Bvault%3A…}. The
     * result was a credential redacted in {@code queryParams} and plaintext one
     * field away in {@code uri} — the exact pair of adjacent contradictory fields
     * this method was added to stop.
     * <p>
     * The ORIGINAL value is emitted when nothing matched, so the preview keeps
     * showing what is genuinely on the wire; only a value the scan actually hit is
     * replaced. A malformed escape falls back to scanning the raw form rather than
     * skipping the check.
     */
    private static String redactQueryValueDecoded(String name, String rawValue) {
        String decoded = rawValue;
        try {
            decoded = java.net.URLDecoder.decode(rawValue, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedEscape) {
            // Keep the raw form — an unparseable escape is no reason to skip the scan.
        }
        String redacted = redactQueryParamValue(name, decoded);
        return redacted.equals(decoded) ? rawValue : redacted;
    }

    public static String redactUri(String uri) {
        if (uri == null) {
            return null;
        }
        int queryStart = uri.indexOf('?');
        if (queryStart < 0) {
            return redactUpToQuery(uri);
        }
        String beforeQuery = redactUpToQuery(uri.substring(0, queryStart));
        String query = uri.substring(queryStart + 1);
        // Preserve the fragment: it is not a query parameter and splitting on '&'
        // would otherwise fold it into the last value.
        String fragment = "";
        int fragmentStart = query.indexOf('#');
        if (fragmentStart >= 0) {
            fragment = redactBody(query.substring(fragmentStart));
            query = query.substring(0, fragmentStart);
        }
        var redactedQuery = new StringBuilder();
        for (String pair : query.split("&", -1)) {
            if (!redactedQuery.isEmpty()) {
                redactedQuery.append('&');
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                // A valueless flag carries no credential to redact, but could still
                // BE one (?sk-live-…), so it is shape-scanned like anything else.
                redactedQuery.append(redactBody(pair));
                continue;
            }
            String name = pair.substring(0, eq);
            redactedQuery.append(name).append('=').append(redactQueryValueDecoded(name, pair.substring(eq + 1)));
        }
        return beforeQuery + "?" + redactedQuery + fragment;
    }

}
