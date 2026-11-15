/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing.rest;

import ai.labs.eddi.engine.pricing.ModelPriceCatalogException;
import ai.labs.eddi.engine.pricing.ModelPriceCatalogService;
import ai.labs.eddi.engine.pricing.UnpricedSightings;
import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A thin layer over {@link ModelPriceCatalogService}, which owns validation,
 * concurrency and the audit line. A refused write answers with {@code {"error":
 * "...", "violations": [...]}} and, for a revision conflict, the
 * {@code currentRevision}.
 */
@ApplicationScoped
public class RestModelPriceCatalog implements IRestModelPriceCatalog {

    private final ModelPriceCatalogService service;
    private final SecurityIdentity identity;

    @Inject
    public RestModelPriceCatalog(ModelPriceCatalogService service, SecurityIdentity identity) {
        this.service = service;
        this.identity = identity;
    }

    @Override
    public Response readCatalog() {
        ModelPriceCatalog catalog = service.read();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("catalog", catalog);
        body.put("effectivePolicy", service.snapshot().effectivePolicy());
        body.put("floorPolicy", service.floor());
        body.put("knownProviders", service.knownProviders());
        body.put("staleAfterDays", ModelPriceCatalogService.STALE_AFTER_DAYS);
        body.put("referenceAsOf", referenceAsOf());
        return Response.ok(body, MediaType.APPLICATION_JSON_TYPE).tag(String.valueOf(catalog.revision())).build();
    }

    @Override
    public Response replaceCatalog(String ifMatch, ModelPriceCatalog catalog) {
        if (catalog == null) {
            return error(ModelPriceCatalogException.INVALID, Map.of("error", "A catalog document is required."));
        }
        long expected = catalog.revision();
        if (ifMatch != null && !ifMatch.isBlank()) {
            try {
                expected = Long.parseLong(ifMatch.replace("\"", "").replace("W/", "").trim());
            } catch (NumberFormatException e) {
                return error(ModelPriceCatalogException.INVALID, Map.of("error", "If-Match must be the catalog revision."));
            }
        }
        try {
            ModelPriceCatalog stored = service.replace(expected, catalog, user());
            return Response.ok(stored, MediaType.APPLICATION_JSON_TYPE).tag(String.valueOf(stored.revision())).build();
        } catch (ModelPriceCatalogException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            if (!e.violations().isEmpty()) {
                body.put("violations", e.violations());
            }
            if (e.currentRevision() != null) {
                body.put("currentRevision", e.currentRevision());
            }
            return error(e.status(), body);
        }
    }

    @Override
    public Response resolve(String provider, String model) {
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            return error(ModelPriceCatalogException.INVALID, Map.of("error", "provider and model are required."));
        }
        Optional<ModelPriceEntry> entry = service.resolve(provider, model);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", provider);
        body.put("model", model);
        body.put("matched", entry.isPresent());
        body.put("entry", entry.orElse(null));
        return Response.ok(body, MediaType.APPLICATION_JSON_TYPE).build();
    }

    @Override
    public ModelPriceCatalog reference() {
        return service.reference();
    }

    @Override
    public Response unpriced() {
        return Response.ok(Map.of("node", "local", "sightings", UnpricedSightings.list()), MediaType.APPLICATION_JSON_TYPE).build();
    }

    private String referenceAsOf() {
        return service.reference().updatedAt();
    }

    private static Response error(int status, Map<String, ?> body) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build();
    }

    private String user() {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            return "anonymous";
        }
        return identity.getPrincipal().getName();
    }
}
