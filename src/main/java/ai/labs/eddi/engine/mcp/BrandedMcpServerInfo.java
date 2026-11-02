/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.branding.BrandRequest;
import ai.labs.eddi.configs.branding.BrandingService;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import io.quarkiverse.mcp.server.Implementation;
import io.quarkiverse.mcp.server.InitialResponseInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * The {@code serverInfo} the MCP server answers {@code initialize} with, under
 * a brand: the product name as both {@code name} and {@code title}, in place of
 * {@code quarkus.application.name} ("eddi").
 * <p>
 * The extension asks every {@link InitialResponseInfo} bean on each
 * {@code initialize}, before it falls back to its configuration, so this
 * follows a rebrand made on the Branding page without a restart — a client sees
 * it on its next connection.
 * <p>
 * It steps aside, and the extension's own answer applies unchanged, when:
 * <ul>
 * <li>nothing is branded — an unbranded deployment answers exactly as
 * before;</li>
 * <li>an operator set {@code quarkus.mcp.server.server-info.name} explicitly,
 * which is the more specific choice;</li>
 * <li>the brand cannot be read.</li>
 * </ul>
 */
@ApplicationScoped
public class BrandedMcpServerInfo implements InitialResponseInfo {

    private static final Logger LOGGER = Logger.getLogger(BrandedMcpServerInfo.class);

    private final BrandingService branding;
    private final Optional<String> configuredName;
    private final String version;

    @Inject
    public BrandedMcpServerInfo(BrandingService branding,
            @ConfigProperty(name = "quarkus.mcp.server.server-info.name") Optional<String> configuredName,
            @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown") String version) {
        this.branding = branding;
        this.configuredName = configuredName.filter(name -> !name.isBlank());
        this.version = version;
    }

    @Override
    public Optional<Implementation> implementation(String serverName) {
        if (configuredName.isPresent()) {
            return Optional.empty();
        }
        try {
            ResolvedBrand brand = branding.current(BrandRequest.NONE);
            if (!brand.branded()) {
                return Optional.empty();
            }
            return Optional.of(new Implementation(brand.productName(), version, brand.productName()));
        } catch (RuntimeException e) {
            LOGGER.debugf("Brand unavailable for the MCP serverInfo: %s", e.getMessage());
            return Optional.empty();
        }
    }
}
