/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.catalog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * @see IRestCatalog
 */
@ApplicationScoped
public class RestCatalog implements IRestCatalog {

    private final CatalogService catalogService;

    @Inject
    public RestCatalog(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Override
    public List<CatalogAgent> listAgents() {
        return catalogService.listAgents();
    }

    @Override
    public List<CatalogGroup> listGroups() {
        return catalogService.listGroups();
    }
}
