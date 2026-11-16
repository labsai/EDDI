/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.secrets.model.SecretReference;
import ai.labs.eddi.secrets.rest.IRestSecretStore;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import static ai.labs.eddi.engine.mcp.McpToolUtils.requireAnyRole;
import static ai.labs.eddi.engine.mcp.McpToolUtils.responseJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.toolFailure;

/**
 * One read-only MCP tool over the secrets vault
 * ({@code docs/secrets-vault.md}): the names of the stored keys and who may use
 * them, so an administrator's AI client can write {@code ${vault:name}}
 * references and check grants without opening the Manager.
 * <p>
 * <b>No value, no checksum, no write.</b> It calls the in-process
 * {@link IRestSecretStore#listSecrets} — the same listing as {@code GET
 * /secretstore/secrets/{tenantId}}, which carries neither the value nor the
 * checksum ({@code SecretListing}). Storing, rotating, granting and deleting
 * secrets stay out of MCP: a value typed into a model's context is a leaked
 * value, and a grant is an access-control change. Tier
 * {@link McpRoles#ADMIN_ONLY}, as the resource.
 *
 * @since 6.8.0
 */
@ApplicationScoped
@McpErrorResults
public class McpVaultTools {

    private static final Logger LOGGER = Logger.getLogger(McpVaultTools.class);

    private final IRestSecretStore secretStore;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public McpVaultTools(IRestSecretStore secretStore, IJsonSerialization jsonSerialization, SecurityIdentity identity,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.secretStore = secretStore;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    @Tool(name = "list_vault_keys", description = "Administrators only. List the secret names stored in the vault for a tenant, with "
            + "description, created/last-accessed/rotated times, allowedAgents (which agents may resolve it), allowedHosts (where it "
            + "may be sent; unrestrictedDestination when none) and which configurations reference it. Never returns a value or a "
            + "checksum. Reference a secret in a configuration as ${vault:<keyName>}. Storing or granting secrets is done in the "
            + "Manager (Secrets), never by a tool.")
    public String listVaultKeys(@ToolArg(description = "Tenant id (default: default)", required = false) String tenantId) {
        requireAnyRole(identity, authEnabled, McpRoles.ADMIN_ONLY);
        try {
            String tenant = tenantId == null || tenantId.isBlank() ? SecretReference.DEFAULT_TENANT : tenantId.trim();
            return responseJson(jsonSerialization, secretStore.listSecrets(tenant));
        } catch (Exception e) {
            return toolFailure(LOGGER, "list_vault_keys", e);
        }
    }
}
