/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding.rest;

import ai.labs.eddi.configs.branding.BrandAssetValidator;
import ai.labs.eddi.configs.branding.BrandDelivery;
import ai.labs.eddi.configs.branding.BrandRequest;
import ai.labs.eddi.configs.branding.BrandingService;
import ai.labs.eddi.configs.branding.model.BrandAsset;
import ai.labs.eddi.configs.branding.model.BrandAssetSlot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Optional;

/**
 * Serves {@code /branding/*}.
 * <p>
 * Images carry {@code Cache-Control: public, max-age=300}, an ETag equal to the
 * content hash (so a revalidation after a rebrand costs one 304 at most), and
 * {@code nosniff}. An SVG is additionally served under {@value #SVG_CSP}:
 * opened directly it is a document on EDDI's origin, and this keeps it from
 * running script or loading anything even if an upload check were ever bypassed
 * (plan R10).
 */
@ApplicationScoped
public class RestBranding implements IRestBranding {

    static final String SVG_CSP = "default-src 'none'; style-src 'unsafe-inline'; sandbox";
    static final String ASSET_CACHE = "public, max-age=300";
    static final String NO_CACHE = "no-cache, must-revalidate";

    private final BrandingService branding;

    @Inject
    public RestBranding(BrandingService branding) {
        this.branding = branding;
    }

    @Override
    public Response brandScript() {
        return Response.ok(BrandDelivery.script(branding.current(BrandRequest.NONE)))
                .type("application/javascript;charset=UTF-8")
                .header("Cache-Control", NO_CACHE)
                .header("X-Content-Type-Options", "nosniff")
                .build();
    }

    @Override
    public Response brandStylesheet() {
        // Always 200, even when empty: the browser logs a <link> answered 204 or
        // 404 as an error, and CI's shell check wants every dependency to be 2xx.
        return Response.ok(BrandDelivery.stylesheet(branding.current(BrandRequest.NONE)))
                .type("text/css;charset=UTF-8")
                .header("Cache-Control", NO_CACHE)
                .header("X-Content-Type-Options", "nosniff")
                .build();
    }

    @Override
    public Response asset(String asset, String ifNoneMatch) {
        Optional<BrandAssetSlot> slot = BrandAssetSlot.fromPublicPath(asset);
        if (slot.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Optional<BrandAsset> image = branding.asset(BrandRequest.NONE, slot.get());
        if (image.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        String etag = "\"" + image.get().hash() + "\"";
        Response.ResponseBuilder response;
        if (matches(ifNoneMatch, etag)) {
            response = Response.notModified();
        } else {
            response = Response.ok(image.get().data()).type(image.get().contentType());
        }
        response.header("ETag", etag)
                .header("Cache-Control", ASSET_CACHE)
                .header("X-Content-Type-Options", "nosniff");
        if (BrandAssetValidator.SVG.equals(image.get().contentType())) {
            response.header("Content-Security-Policy", SVG_CSP);
        }
        return response.build();
    }

    /** {@code If-None-Match} with one or more tags, or {@code *}. */
    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String tag = candidate.strip();
            if (tag.startsWith("W/")) {
                tag = tag.substring(2);
            }
            if ("*".equals(tag) || etag.equals(tag)) {
                return true;
            }
        }
        return false;
    }
}
