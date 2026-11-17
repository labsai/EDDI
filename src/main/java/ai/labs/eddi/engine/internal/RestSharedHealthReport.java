/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.branding.BrandRequest;
import ai.labs.eddi.configs.branding.BrandingService;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import ai.labs.eddi.engine.api.IRestSharedHealthReport;
import ai.labs.eddi.engine.exception.ErrorResponses;
import ai.labs.eddi.engine.health.HealthAccess;
import ai.labs.eddi.engine.health.HealthReportLinks;
import ai.labs.eddi.engine.health.HealthReportRateLimiter;
import ai.labs.eddi.engine.health.HealthReportRateLimiter.Charge;
import ai.labs.eddi.engine.health.model.HealthReportLink;
import ai.labs.eddi.engine.health.report.HealthReports.ReportBrand;
import ai.labs.eddi.engine.health.report.HealthReports.SharedHealthReport;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard.UseCheck;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * @see IRestSharedHealthReport
 */
@ApplicationScoped
public class RestSharedHealthReport implements IRestSharedHealthReport {

    private static final Logger LOGGER = Logger.getLogger(RestSharedHealthReport.class);

    /** Seconds a client is asked to wait after a 429. */
    static final int RETRY_AFTER_SECONDS = 60;
    static final String REFERRER_POLICY = "Referrer-Policy";
    static final String NO_REFERRER = "no-referrer";

    private final HealthReportLinks links;
    private final HealthReportRateLimiter limiter;
    private final HealthAccess access;
    private final BrandingService branding;

    @Inject
    public RestSharedHealthReport(HealthReportLinks links, HealthReportRateLimiter limiter, HealthAccess access, BrandingService branding) {
        this.links = links;
        this.limiter = limiter;
        this.access = access;
        this.branding = branding;
    }

    /**
     * In this order, so that nothing a stranger sends costs a real link anything: a
     * malformed token is a 404 before any bucket or lookup; an unknown token is
     * charged to its client's and the shared lookup bucket <em>before</em> the
     * database lookup (a found link gets the charge back); only a found link is
     * charged to its own and the shared read bucket; then its creator's authority
     * is re-checked.
     */
    @Override
    public Response readSharedReport(String token, String host, HttpServerRequest request) {
        return noReferrer(respond(token, host, request), request);
    }

    private Response respond(String token, String host, HttpServerRequest request) {
        if (!HealthReportLinks.wellFormed(token)) {
            return notFound();
        }
        String hash = HealthReportLinks.hash(token);
        Charge charge = limiter.tryLookup(hash, clientOf(request));
        if (!charge.allowed()) {
            return tooMany();
        }
        Optional<HealthReportLink> link = links.resolveHash(hash);
        if (link.isEmpty()) {
            limiter.missed(hash);
            return notFound();
        }
        limiter.found(hash, charge);
        if (!limiter.tryRead(hash)) {
            return tooMany();
        }
        UseCheck authorized = links.authorized(link.get());
        if (authorized == UseCheck.DENIED) {
            // The creator no longer holds EDIT on the agent: like a revoked link.
            return notFound();
        }
        if (authorized != UseCheck.ALLOWED) {
            return noStore(Response.status(Response.Status.SERVICE_UNAVAILABLE).type(MediaType.APPLICATION_JSON_TYPE)
                    .header("Retry-After", RETRY_AFTER_SECONDS)
                    .entity(ErrorResponses.body("unavailable", "The report cannot be checked right now; try again in a minute"))).build();
        }
        Optional<SharedHealthReport> report;
        try {
            report = links.render(link.get(), access.markerTenants(link.get().agentId()), brand(host));
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not render owner report link %s: %s", link.get().id(), e.getMessage());
            return noStore(Response.serverError().type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(ErrorResponses.body("internal_error", "The report could not be built"))).build();
        }
        if (report.isEmpty()) {
            return notFound();
        }
        return noStore(Response.ok(report.get(), MediaType.APPLICATION_JSON_TYPE)).build();
    }

    /**
     * The client's address as Quarkus resolved it — the forwarded one only from a
     * trusted proxy ({@code quarkus.http.proxy.*}); never a raw header.
     */
    static String clientOf(HttpServerRequest request) {
        if (request == null) {
            return "unknown";
        }
        SocketAddress remote = request.remoteAddress();
        if (remote == null) {
            return "unknown";
        }
        String address = remote.hostAddress();
        return address == null || address.isBlank() ? "unknown" : address;
    }

    /**
     * The brand the shells show publicly anyway — product name, tagline, whether
     * "Powered by EDDI" shows, the logo's public URL and the primary colour.
     */
    ReportBrand brand(String host) {
        try {
            ResolvedBrand brand = branding.current(BrandRequest.ofHost(host));
            String primary = brand.colors() == null ? null : brand.colors().primary();
            return new ReportBrand(brand.productName(), brand.tagline(), brand.poweredBy(), "/branding/logo", primary);
        } catch (RuntimeException e) {
            ResolvedBrand fallback = ResolvedBrand.defaults(false);
            return new ReportBrand(fallback.productName(), null, true, "/branding/logo", null);
        }
    }

    private static Response notFound() {
        return noStore(Response.status(Response.Status.NOT_FOUND).type(MediaType.APPLICATION_JSON_TYPE)
                .entity(ErrorResponses.body("not_found", "No such report"))).build();
    }

    private static Response tooMany() {
        return noStore(Response.status(429).type(MediaType.APPLICATION_JSON_TYPE).header("Retry-After", RETRY_AFTER_SECONDS)
                .entity(ErrorResponses.body("too_many_requests", "Too many requests; try again in a minute"))).build();
    }

    /**
     * Never cached anywhere, never indexed. (Never sent on as a referrer either:
     * {@link #noReferrer}.)
     */
    static Response.ResponseBuilder noStore(Response.ResponseBuilder builder) {
        return builder.header("Cache-Control", "no-store, max-age=0").header("Pragma", "no-cache").header("X-Robots-Tag", "noindex, nofollow");
    }

    /**
     * {@code Referrer-Policy: no-referrer} — the token is in the page's URL. The
     * deployment-wide {@code quarkus.http.header.Referrer-Policy} has already
     * <em>added</em> its own value to the Vert.x response by the time this runs, so
     * a header on the JAX-RS response would be a second one (and a browser takes
     * the last it understands). It is <em>set</em> on the Vert.x response instead,
     * replacing the global value; without a request (unit tests) it goes on the
     * JAX-RS response.
     */
    static Response noReferrer(Response response, HttpServerRequest request) {
        if (request != null && request.response() != null) {
            request.response().headers().set(REFERRER_POLICY, NO_REFERRER);
            return response;
        }
        return Response.fromResponse(response).header(REFERRER_POLICY, NO_REFERRER).build();
    }
}
