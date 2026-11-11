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
import ai.labs.eddi.engine.health.model.HealthReportLink;
import ai.labs.eddi.engine.health.report.HealthReports.ReportBrand;
import ai.labs.eddi.engine.health.report.HealthReports.SharedHealthReport;
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

    @Override
    public Response readSharedReport(String token, String host) {
        if (!limiter.tryRequest()) {
            return tooMany();
        }
        Optional<HealthReportLink> link = links.resolve(token);
        if (link.isEmpty()) {
            return limiter.tryMiss() ? notFound() : tooMany();
        }
        if (!limiter.tryLink(link.get().tokenHash())) {
            return tooMany();
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
     * Never cached anywhere, never sent on as a referrer (the token is in the
     * page's URL), never indexed.
     */
    static Response.ResponseBuilder noStore(Response.ResponseBuilder builder) {
        return builder.header("Cache-Control", "no-store, max-age=0").header("Pragma", "no-cache").header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex, nofollow");
    }
}
