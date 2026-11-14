/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.rest;

import ai.labs.eddi.engine.hitl.oversight.AgentCallMarker;
import ai.labs.eddi.engine.hitl.oversight.AgentOriginatedRequests;
import ai.labs.eddi.engine.hitl.oversight.OversightErrors;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Refuses an oversight write or a HITL decision that an agent's own tool call
 * makes: any non-read request carrying {@link AgentCallMarker#HEADER} to
 * {@code /hitl/samples/**}, {@code /hitl/autonomy/**},
 * {@code /hitl/analytics/rebuild}, a paused conversation's
 * {@code /agents/{id}/resume} (and {@code /resume/stream}), or a group's
 * {@code /groups/{id}/conversations/{id}/approve} (and {@code /approve/stream})
 * and {@code /human-input} answers 403 {@code AGENT_ORIGINATED}. Sample
 * verdicts, every change to earned autonomy and every approval decision are
 * human actions — a resume verdict is counted as human-verified evidence toward
 * earned autonomy.
 * <p>
 * The engine sets the header on an httpcall aimed at this deployment (loopback,
 * {@code eddi.self.base-url}, {@code eddi.hitl.oversight.self-hosts}, or the
 * origin the turn's caller addressed) and on every request that carries a
 * released {@code ${caller:token}}, so not even a forwarded caller token lets
 * an agent produce its own evidence or decide a pause as the human it talks to.
 * A request from outside EDDI — a script, a proxy, another product — carries no
 * such header, and the filter cannot tell it apart from a person.
 *
 * @since 6.7.0
 */
@Provider
public class AgentOriginatedRequestFilter implements ContainerRequestFilter {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Pattern GUARDED = Pattern.compile("^/?hitl/(?:samples|autonomy|analytics/rebuild)(?:/.*)?$");
    @Override
    public void filter(ContainerRequestContext request) {
        String marker = request.getHeaderString(AgentCallMarker.HEADER);
        String method = request.getMethod();
        if (!AgentOriginatedRequests.isMarked(marker) || method == null || READ_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            // Nothing to judge: every unmarked request and every read leaves untouched,
            // before any path is read.
            return;
        }
        String path = request.getUriInfo().getPath();
        String rawPath = rawPath(request.getUriInfo());
        if (refused(method, path, rawPath, marker)) {
            String message = isDecision(path, rawPath)
                    ? AgentOriginatedRequests.DECISION_REFUSAL
                    : "This request was made by an agent's tool call; reviewing samples and changing earned autonomy are human actions";
            request.abortWith(OversightErrors.response(Response.Status.FORBIDDEN, OversightErrors.AGENT_ORIGINATED, message));
        }
    }

    /**
     * The request path relative to the application, still percent-encoded.
     * <p>
     * Not {@code UriInfo.getPath(false)}: RESTEasy Reactive throws
     * {@code IllegalArgumentException("We do not support non-decoded parameters")}
     * from it on every call, which the exception mapper turned into a 400 for every
     * request this filter saw — {@code /manage} and every store write included. The
     * raw path of the request URI, less the base URI's, is the same string. Null
     * when it cannot be read; the decoded reading is then judged alone.
     */
    static String rawPath(UriInfo uriInfo) {
        try {
            URI requestUri = uriInfo.getRequestUri();
            String raw = requestUri == null ? null : requestUri.getRawPath();
            if (raw == null) {
                return null;
            }
            URI baseUri = uriInfo.getBaseUri();
            String base = baseUri == null ? null : baseUri.getRawPath();
            if (base != null && !base.isEmpty() && !"/".equals(base) && raw.startsWith(base)) {
                raw = raw.substring(base.endsWith("/") ? base.length() - 1 : base.length());
            }
            return raw;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether a request is refused, judged on its decoded path alone. */
    static boolean refused(String method, String path, String marker) {
        return refused(method, path, null, marker);
    }

    /**
     * Whether a request is refused. The path is judged in both readings routing may
     * give it, and refused when either is guarded: the decoded path, and the raw
     * one with matrix parameters stripped per raw segment before decoding. A matrix
     * value holding an encoded slash ({@code approve;x=%2Fzzz}) adds a segment to
     * the decoded reading only, which then no longer ends in {@code approve} —
     * while the router drops the parameter and serves {@code approve}.
     */
    static boolean refused(String method, String path, String rawPath, String marker) {
        if (!AgentOriginatedRequests.isMarked(marker) || method == null || READ_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            return false;
        }
        return guarded(path == null ? null : canonical(path)) || guarded(rawPath == null ? null : canonicalRaw(rawPath));
    }

    private static boolean guarded(String canonical) {
        return canonical != null && (GUARDED.matcher(canonical).matches() || AgentOriginatedRequests.isDecisionPath(canonical));
    }

    private static boolean isDecision(String path, String rawPath) {
        return path != null && AgentOriginatedRequests.isDecisionPath(canonical(path))
                || rawPath != null && AgentOriginatedRequests.isDecisionPath(canonicalRaw(rawPath));
    }

    /**
     * The decoded path as routing sees it, so no spelling slips past the patterns:
     * lower case, matrix parameters ({@code resume;x=1}) dropped and repeated
     * slashes collapsed. A decision path is matched segment by segment
     * ({@link AgentOriginatedRequests#isDecisionPath}) and an identifier may span
     * several segments, so an id that decodes to contain a {@code /} ({@code %2F})
     * still ends in {@code /resume}. No {@code LegacyPathRewriteFilter} alias leads
     * here: its rewrites cover the configuration stores and
     * {@code /administration/{env}} only, and it is {@code @PreMatching}, so this
     * post-matching filter reads the rewritten path anyway.
     */
    static String canonical(String path) {
        return path.toLowerCase(Locale.ROOT).replaceAll(";[^/]*", "").replaceAll("/{2,}", "/");
    }

    /**
     * The raw (still encoded) path read the way the router reads it: split on the
     * raw {@code /} only, each segment's matrix parameters dropped while still
     * encoded, then each segment percent-decoded and lower-cased. Empty segments
     * are dropped. Linear: one pass to split, one per segment to decode.
     */
    static String canonicalRaw(String rawPath) {
        StringBuilder out = new StringBuilder(rawPath.length());
        int start = 0;
        for (int i = 0; i <= rawPath.length(); i++) {
            if (i == rawPath.length() || rawPath.charAt(i) == '/') {
                String segment = rawPath.substring(start, i);
                int parameters = segment.indexOf(';');
                if (parameters >= 0) {
                    segment = segment.substring(0, parameters);
                }
                if (!segment.isEmpty()) {
                    out.append('/').append(percentDecode(segment).toLowerCase(Locale.ROOT));
                }
                start = i + 1;
            }
        }
        return out.toString();
    }

    /** {@code %XX} decoded as UTF-8; a malformed escape is kept as written. */
    private static String percentDecode(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;
        }
        try {
            return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return segment;
        }
    }
}
