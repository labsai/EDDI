/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.runtime.configuration.MemorySize;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.regex.Pattern;

/**
 * Holds every request body to the old 25 MB limit except the one endpoint that
 * needs more.
 *
 * <p>
 * {@code quarkus.http.limits.max-body-size} was raised to 60 MB so a knowledge
 * base's file upload can carry a file at its configured limit — and, being
 * global, that raised it for every endpoint: any authenticated JSON endpoint
 * now accepted, buffered and parsed 60 MB bodies. Quarkus has no per-route body
 * limit, so this filter restores one: a request that declares a
 * {@code Content-Length} above the effective limit is refused with 413 before
 * its body is read, unless it is an upload to a knowledge base's file source,
 * which keeps the global ceiling.
 *
 * <h2>The effective limit</h2>
 * <p>
 * {@code eddi.http.limits.default-max-body-size}, raised if need be to what an
 * attachment at {@code eddi.attachments.max-size-bytes} takes once it is
 * base64-encoded into a JSON body (four thirds, plus a megabyte for the rest of
 * the message) — so an operator raising the attachment limit, as its
 * validator's message tells them to, is not then refused here with a bare 413.
 *
 * <p>
 * Never above {@code quarkus.http.limits.max-body-size}, though, which refuses
 * a larger request before this filter or any validator sees it. An attachment
 * limit whose base64 body needs more than that ceiling cannot be honoured by
 * this filter alone, so it is reported at startup with a WARN naming both
 * settings and the ceiling it needs — a 60 MiB attachment needs about 81 MB.
 *
 * <h2>Bodies without a length</h2>
 * <p>
 * A chunked request declares nothing to judge. Counting its bytes as they
 * arrive is not possible from here: the REST layer installs its own handler on
 * the request later and would replace a counting one. So a chunked HTTP/1.1
 * body is refused with 411 on every endpoint but the upload, before any of it
 * is read — JSON clients and browsers send the length. {@code
 * eddi.http.limits.refuse-unsized-bodies=false} turns that off for a client
 * that cannot, leaving those bodies to the global ceiling.
 *
 * <p>
 * An HTTP/2 request without a {@code content-length} is left to the global
 * ceiling as well. It cannot be told apart from one with no body here: a
 * bodyless GET is not yet ended when a filter sees it either, so treating "not
 * ended" as "a body is coming" refused ordinary reads — agent sync's GETs to
 * another instance, which the JDK client sends over h2c, were refused, and the
 * sync failed with 502.
 *
 * <p>
 * Every refusal closes the connection, so the server does not go on draining a
 * body it has already refused.
 */
@ApplicationScoped
public class RequestBodyLimitGuard {

    private static final Logger LOGGER = Logger.getLogger(RequestBodyLimitGuard.class);

    /** Below {@link HttpMethodGuard}, which refuses whole methods first. */
    static final int PRIORITY = 19_000;

    /**
     * {@code POST /ragstore/rags/{id}/sources/{sourceId}/files} — the only endpoint
     * whose bodies are meant to exceed the default.
     */
    static final Pattern LARGE_BODY_PATHS = Pattern.compile("^/ragstore/rags/[^/]+/sources/[^/]+/files/?$");

    private static final long MEGABYTE = 1024L * 1024;

    /** Room for the rest of a JSON message around a base64-encoded attachment. */
    private static final long ENVELOPE_BYTES = MEGABYTE;

    @ConfigProperty(name = "eddi.http.limits.default-max-body-size", defaultValue = "25M")
    MemorySize defaultMaxBodySize;

    /**
     * The HTTP layer's absolute ceiling, which refuses a larger body before this
     * filter runs. Read, not set: the effective limit is capped by it.
     */
    @ConfigProperty(name = "quarkus.http.limits.max-body-size", defaultValue = "10240K")
    MemorySize globalMaxBodySize;

    @ConfigProperty(name = "eddi.attachments.max-size-bytes", defaultValue = "20971520")
    long attachmentMaxBytes = 20_971_520L;

    @ConfigProperty(name = "eddi.http.limits.refuse-unsized-bodies", defaultValue = "true")
    boolean refuseUnsizedBodies = true;

    /** Paths are matched below it, so the exemption holds under a prefix. */
    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/")
    String rootPath = "/";

    void register(@Observes Filters filters) {
        filters.register(this::handle, PRIORITY);
        String conflict = ceilingConflict();
        if (conflict != null) {
            LOGGER.warn(conflict);
        }
    }

    void handle(RoutingContext context) {
        if (mayCarryLargeBody(context)) {
            context.next();
            return;
        }
        HttpServerRequest request = context.request();
        long declared = declaredLength(request.getHeader(HttpHeaders.CONTENT_LENGTH));
        long limit = effectiveLimit();
        if (declared > limit) {
            refuse(context, 413, "The request body is larger than this endpoint accepts ("
                    + limit / (1024 * 1024) + " MB).");
            return;
        }
        if (declared < 0 && refuseUnsizedBodies && carriesUnsizedBody(request)) {
            refuse(context, 411, "This endpoint needs the request's Content-Length.");
            return;
        }
        context.next();
    }

    /**
     * The configured limit, or what the largest allowed attachment needs, whichever
     * is more — but never more than the HTTP layer's ceiling lets through.
     */
    long effectiveLimit() {
        long wanted = Math.max(defaultMaxBodySize.asLongValue(), attachmentBodyBytes());
        return Math.min(wanted, ceiling());
    }

    /**
     * Why an attachment at the configured limit cannot arrive inline, or null when
     * it can: its base64 body needs more than {@code
     * quarkus.http.limits.max-body-size}, which refuses the request before this
     * filter — or the attachment validator and its advice — ever sees it.
     */
    String ceilingConflict() {
        long needed = attachmentBodyBytes();
        long ceiling = ceiling();
        if (needed <= ceiling) {
            return null;
        }
        long neededMb = (needed + MEGABYTE - 1) / MEGABYTE;
        return "eddi.attachments.max-size-bytes=" + attachmentMaxBytes + " allows attachments whose base64 JSON "
                + "body needs about " + neededMb + " MB, but quarkus.http.limits.max-body-size is "
                + ceiling / MEGABYTE + " MB: such requests are refused with a bare 413 before any endpoint "
                + "sees them. Raise quarkus.http.limits.max-body-size to at least " + neededMb
                + "M, or lower eddi.attachments.max-size-bytes.";
    }

    /** What an attachment at the limit takes base64-encoded in a JSON body. */
    private long attachmentBodyBytes() {
        return attachmentMaxBytes <= 0 ? 0 : (attachmentMaxBytes / 3 + 1) * 4 + ENVELOPE_BYTES;
    }

    private long ceiling() {
        return globalMaxBodySize == null ? Long.MAX_VALUE : globalMaxBodySize.asLongValue();
    }

    /**
     * A body is on its way without a declared length: chunked, which only HTTP/1.1
     * has. Not "an HTTP/2 request that has not ended" — see the class comment.
     */
    private static boolean carriesUnsizedBody(HttpServerRequest request) {
        return request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null;
    }

    private static void refuse(RoutingContext context, int status, String message) {
        HttpServerResponse response = context.response()
                .setStatusCode(status)
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        if (isHttp1(context.request().version())) {
            // HTTP/2 forbids connection-specific headers (RFC 9113 §8.2.2), and a
            // client that enforces it — the JDK's does — discards the whole response
            // as malformed, so the refusal never reaches it. There the stream ends
            // with the response; the connection is shared and stays.
            response.putHeader(HttpHeaders.CONNECTION, "close");
        }
        response.end("{\"error\":\"" + message + "\"}");
    }

    private static boolean isHttp1(HttpVersion version) {
        return version == HttpVersion.HTTP_1_0 || version == HttpVersion.HTTP_1_1;
    }

    private boolean mayCarryLargeBody(RoutingContext context) {
        String path = relativeToRoot(context.normalizedPath());
        return "POST".equals(context.request().method().name()) && path != null
                && LARGE_BODY_PATHS.matcher(path).matches();
    }

    /**
     * The path below {@code quarkus.http.root-path}, or null when it is not under
     * it.
     */
    private String relativeToRoot(String path) {
        if (path == null) {
            return null;
        }
        String root = rootPath == null
                ? "/"
                : rootPath.endsWith("/")
                        ? rootPath.substring(0, rootPath.length() - 1)
                        : rootPath;
        if (root.isEmpty()) {
            return path;
        }
        return path.startsWith(root + "/") ? path.substring(root.length()) : null;
    }

    /** The declared length, or -1 when there is none to judge. */
    private static long declaredLength(String header) {
        if (header == null || header.isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            // Malformed: the HTTP layer refuses it on its own terms.
            return -1;
        }
    }
}
