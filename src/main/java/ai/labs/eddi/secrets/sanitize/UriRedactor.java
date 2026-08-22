/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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

    /**
     * Credential words that mark a name wherever they appear inside it. No benign
     * header or query parameter contains one, so a substring test is safe and
     * catches the run-together spellings ({@code mytoken}, {@code xapikey}) a
     * word-by-word test would miss.
     */
    private static final List<String> CREDENTIAL_WORDS = List.of("token", "secret", "credential", "password", "passwd", "apikey");

    /**
     * The auth family, matched as a whole WORD ending rather than as a substring.
     * <p>
     * {@code contains("auth")} is what the two detectors disagreed over, and both
     * readings were wrong: it matches {@code author}, {@code authority} and
     * {@code authored_by}, while the exact-name lists it was reconciled against had
     * no entry for {@code Authentication} at all — so a header named
     * {@code Authentication} holding a bearer token exported in plaintext, the
     * entropy fallback being a whole-string match that the space in "Bearer
     * &lt;token&gt;" defeats.
     * <p>
     * Ending a word is the discriminator: {@code auth}, {@code oauth},
     * {@code apiAuth}, {@code proxy-authorization} and {@code reauthentication} all
     * end one of these; {@code author} and {@code authority} end none of them,
     * because what follows the {@code auth} is what makes them ordinary English.
     */
    private static final List<String> AUTH_WORD_ENDINGS = List.of("auth", "authorization", "authorisation", "authentication", "authenticate",
            "authn");

    /**
     * A value that is, in full, an unresolved template placeholder —
     * {@code {properties.apiKey}} or {@code ${vars.region}}.
     * <p>
     * Whole-value, like every other exemption in this package: a placeholder with a
     * tail is not a placeholder.
     */
    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile("\\$?\\{[^{}]*+}");

    private UriRedactor() {
    }

    /** Redact secret-shaped values out of an arbitrary string. */
    static String redactBody(String body) {
        return SecretRedactionFilter.redact(body);
    }

    /**
     * Whether a header carries credential material, judged by its name.
     * <p>
     * The name is split into words on separators and camel-case boundaries, and
     * lowercased character by character — {@link Character#toLowerCase(char)}
     * rather than {@link String#toLowerCase()}, because the latter follows the
     * default locale and under a Turkish one "Authorization" lowercases to
     * "authorızation" (dotless i), every comparison below misses, and the header is
     * persisted unredacted.
     * <p>
     * This is the ONE definition of the rule. {@code RequestRedactor} delegates
     * here for approval cards and stored requests, and {@code SecretScrubber} for
     * agent export, so the same header cannot be a credential on one path and
     * ordinary configuration on the other.
     */
    public static boolean isSensitiveHeaderName(String headerName) {
        if (headerName == null) {
            return false;
        }
        List<String> words = splitWords(headerName);
        String runTogether = String.join("", words);
        for (String credentialWord : CREDENTIAL_WORDS) {
            if (runTogether.contains(credentialWord)) {
                return true;
            }
        }
        for (String word : words) {
            for (String ending : AUTH_WORD_ENDINGS) {
                if (word.endsWith(ending)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The lowercased words of a name, split on anything that is not a letter or
     * digit and on each camel-case hump, so {@code X-Api-Token},
     * {@code x_api_token} and {@code xApiToken} all yield the same three words.
     */
    private static List<String> splitWords(String name) {
        var words = new ArrayList<String>();
        var word = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean startsNewWord = !Character.isLetterOrDigit(c)
                    || (Character.isUpperCase(c) && i > 0 && Character.isLowerCase(name.charAt(i - 1)));
            if (startsNewWord && !word.isEmpty()) {
                words.add(word.toString());
                word.setLength(0);
            }
            if (Character.isLetterOrDigit(c)) {
                word.append(Character.toLowerCase(c));
            }
        }
        if (!word.isEmpty()) {
            words.add(word.toString());
        }
        return words;
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
        if (value == null) {
            return isSensitiveHeaderName(name) ? REDACTED : "";
        }
        if (value.contains("${vault:") || value.contains("${eddivault:")) {
            return REDACTED;
        }
        if (isTemplatePlaceholder(value)) {
            // Judged by name alone this would be redacted, and on the export path
            // that DESTROYS a config: `?api_key={properties.apiKey}` is the
            // documented wizard pattern, the value is a Qute expression rather
            // than a credential, and replacing it loses which property the call
            // reads. Left legible for the same reason a vault reference is — it
            // is a pointer, not a secret.
            return value;
        }
        if (isSensitiveHeaderName(name)) {
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
    private static String redactUpToQuery(String beforeQuery, String marker) {
        int schemeEnd = beforeQuery.indexOf("://");
        if (schemeEnd < 0) {
            // Relative or scheme-less: it is all path.
            return withMarker(redactBody(beforeQuery), marker);
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
        String safeAuthority = at < 0 ? authority : redactUserInfo(authority.substring(0, at), marker) + authority.substring(at);

        return beforeQuery.substring(0, authorityStart) + safeAuthority + withMarker(redactBody(path), marker);
    }

    /**
     * Redacts the password half of a {@code user:password} userinfo component,
     * leaving a bare username legible.
     */
    private static String redactUserInfo(String userInfo, String marker) {
        int separator = userInfo.indexOf(':');
        if (separator < 0) {
            return withMarker(redactBody(userInfo), marker);
        }
        return withMarker(redactBody(userInfo.substring(0, separator)), marker) + ":" + marker;
    }

    /**
     * Restates a redaction in the caller's marker. {@link #redactBody} knows only
     * this class's own, so a caller that needs a different one — an exported
     * config, where {@code <REDACTED>} is not made of URI characters — gets it
     * substituted here rather than by teaching every rule a second spelling.
     */
    private static String withMarker(String redacted, String marker) {
        return REDACTED.equals(marker) ? redacted : redacted.replace(REDACTED, marker);
    }

    /**
     * Redact one query value from a URI, judging it in its DECODED form and
     * emitting it back in its ENCODED one.
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
     * What is emitted is a different question from what is scanned, and answering
     * them the same way corrupted the URI. The decoded form was written straight
     * back out: {@code %26} re-emerged as a structural {@code &} that splits the
     * value in two, {@code +} became a space, and this string is not only shown to
     * an approver — {@code SecretScrubber} writes it into an exported config. So
     * the ORIGINAL is emitted when nothing matched, and what a rule did hit is
     * re-encoded around the marker, which is left whole so it stays readable.
     * <p>
     * A malformed escape falls back to scanning the raw form rather than skipping
     * the check.
     */
    private static String redactQueryValue(String name, String rawValue, String marker) {
        String decoded = rawValue;
        try {
            decoded = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedEscape) {
            // Keep the raw form — an unparseable escape is no reason to skip the scan.
        }
        String redacted = redactQueryParamValue(name, decoded);
        if (redacted.equals(decoded)) {
            return rawValue;
        }
        return reencodeAround(withMarker(redacted, marker), marker);
    }

    /**
     * Percent-encodes everything except the marker, so surviving text cannot carry
     * a {@code &}, {@code =} or {@code #} back into the URI's structure while the
     * marker itself stays legible.
     */
    private static String reencodeAround(String value, String marker) {
        if (marker.isEmpty()) {
            // An empty marker is found at every position, so the scan below would
            // never advance.
            return encodeQueryValue(value);
        }
        var out = new StringBuilder(value.length());
        int copiedUpTo = 0;
        for (int at = value.indexOf(marker); at >= 0; at = value.indexOf(marker, copiedUpTo)) {
            out.append(encodeQueryValue(value.substring(copiedUpTo, at))).append(marker);
            copiedUpTo = at + marker.length();
        }
        return out.append(encodeQueryValue(value.substring(copiedUpTo))).toString();
    }

    private static String encodeQueryValue(String text) {
        return text.isEmpty() ? text : URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    /**
     * Redact a request URI, using this class's own {@code <REDACTED>} marker.
     */
    public static String redactUri(String uri) {
        return redactUri(uri, REDACTED);
    }

    /**
     * Redact a URI, replacing whatever is found with {@code marker}.
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
     * The marker is the caller's because the destinations differ in what they can
     * hold. An approval card is text and {@code <REDACTED>} reads best there;
     * {@code SecretScrubber} stores its result as a URI in an exported config,
     * where angle brackets are not URI characters at all and a
     * {@code ${vault:REDACTED}} placeholder is both legal in an EDDI URI template
     * and exactly what an operator has to fill in on import.
     * <p>
     * Static and null-tolerant for the same reason as {@link #redactBody}:
     * {@link ResolvedRequest#of} applies it without an executor, keeping
     * "fingerprint the raw, store the redacted" resolved in exactly one place.
     */
    public static String redactUri(String uri, String marker) {
        if (uri == null) {
            return null;
        }
        int queryStart = uri.indexOf('?');
        if (queryStart < 0) {
            return redactUpToQuery(uri, marker);
        }
        String beforeQuery = redactUpToQuery(uri.substring(0, queryStart), marker);
        String query = uri.substring(queryStart + 1);
        // Preserve the fragment: it is not a query parameter and splitting on '&'
        // would otherwise fold it into the last value.
        String fragment = "";
        int fragmentStart = query.indexOf('#');
        if (fragmentStart >= 0) {
            fragment = withMarker(redactBody(query.substring(fragmentStart)), marker);
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
                redactedQuery.append(withMarker(redactBody(pair), marker));
                continue;
            }
            String name = pair.substring(0, eq);
            redactedQuery.append(name).append('=').append(redactQueryValue(name, pair.substring(eq + 1), marker));
        }
        return beforeQuery + "?" + redactedQuery + fragment;
    }

    /** Whether a value is, in full, an unresolved template placeholder. */
    private static boolean isTemplatePlaceholder(String value) {
        return TEMPLATE_PLACEHOLDER.matcher(value).matches();
    }

}
