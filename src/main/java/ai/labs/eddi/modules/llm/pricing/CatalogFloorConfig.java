/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pricing;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Publishes {@code eddi.llm.pricing.catalog-floor} to {@link ExplicitPrices},
 * which {@link ModelPriceResolver} reads on every priced call.
 * <p>
 * Off by default: a configuration's explicit price beats the catalog, and an
 * explicit {@code 0} is a real price (a local model is free). On, the price
 * catalog is a <em>floor</em> — a task, cascade step or guardrail may charge
 * more than the catalog says for a model the catalog knows, never less, so an
 * editor cannot switch cost accounting off by writing {@code 0}.
 */
@Startup
@ApplicationScoped
public class CatalogFloorConfig {

    private static final Logger LOGGER = Logger.getLogger(CatalogFloorConfig.class);

    @Inject
    @ConfigProperty(name = "eddi.llm.pricing.catalog-floor", defaultValue = "false")
    boolean catalogFloor;

    @PostConstruct
    void publish() {
        ExplicitPrices.setCatalogFloor(catalogFloor);
        if (catalogFloor) {
            LOGGER.info("Price catalog is a floor: configured model prices below the catalog's are raised to it (eddi.llm.pricing.catalog-floor)");
        }
    }
}
