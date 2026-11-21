/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.pricing.rest.IRestModelPriceCatalog;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

import static ai.labs.eddi.engine.mcp.McpToolUtils.errorJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.requireAnyRole;
import static ai.labs.eddi.engine.mcp.McpToolUtils.responseJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.toolFailure;

/**
 * MCP read tool for the deployment's model price catalog
 * ({@code docs/model-pricing.md}): what prices a model, what the unpriced
 * policy is, and which models were called without a price.
 * <p>
 * <b>Read only.</b> The catalog is a budget lever — lowering a price to $0
 * raises every budget that model counts against — so its MCP write,
 * {@code update_model_prices} in {@link McpSpendControlTools}, is admin-only
 * and applies only what makes spending stricter. The tool calls the in-process
 * {@link IRestModelPriceCatalog} bean, the same reads as
 * {@code GET /administration/model-prices}, {@code /resolve} and
 * {@code /unpriced}. Tier {@link McpRoles#AUTHOR}, equal to their
 * {@code @RolesAllowed}.
 *
 * @since 6.7.0
 */
@ApplicationScoped
@McpErrorResults
public class McpPricingTools {

    private static final Logger LOGGER = Logger.getLogger(McpPricingTools.class);

    private final IRestModelPriceCatalog catalog;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public McpPricingTools(IRestModelPriceCatalog catalog, IJsonSerialization jsonSerialization, SecurityIdentity identity,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.catalog = catalog;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    @Tool(name = "read_model_prices", description = "Read the deployment's model price catalog: every entry (provider, model pattern, "
            + "input/output USD per 1M tokens, cache and long-context prices, asOf), the effective unpriced-call policy (ALLOW, "
            + "REFUSE or REFUSE_WHEN_BUDGETED — the stricter of the catalog's and the deployment floor) and the models called without "
            + "any price on this node since it started. A model with no price costs $0 against every budget and is counted as unpriced. "
            + "Give provider and model to also see which catalog entry prices that model (exact, then the longest wildcard). "
            + "Read only; administrators change prices with update_model_prices (a lower price waits for approval in the Manager).")
    public String readModelPrices(@ToolArg(description = "Model type, e.g. openai, anthropic (with model: resolve that model)",
                                           required = false) String provider,
                                  @ToolArg(description = "Model name, e.g. gpt-4o-mini (with provider: resolve that model)",
                                           required = false) String model) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        boolean hasProvider = provider != null && !provider.isBlank();
        boolean hasModel = model != null && !model.isBlank();
        if (hasProvider != hasModel) {
            return errorJson("Give both provider and model to resolve a model, or neither to read the catalog", "BAD_REQUEST", null);
        }
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            Response read = catalog.readCatalog();
            if (read == null || read.getStatus() != 200) {
                return responseJson(jsonSerialization, read);
            }
            result.put("catalog", read.getEntity());
            Response unpriced = catalog.unpriced();
            result.put("unpriced", unpriced != null && unpriced.getStatus() == 200 ? unpriced.getEntity() : null);
            if (hasProvider) {
                Response resolved = catalog.resolve(provider.trim(), model.trim());
                if (resolved == null || resolved.getStatus() != 200) {
                    return responseJson(jsonSerialization, resolved);
                }
                result.put("resolved", resolved.getEntity());
            }
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            return toolFailure(LOGGER, "read_model_prices", e);
        }
    }
}
