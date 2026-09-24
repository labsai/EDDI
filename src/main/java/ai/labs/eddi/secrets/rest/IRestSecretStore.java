/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * REST interface for managing secrets in the vault. Secrets are stored
 * encrypted; plaintext values are NEVER returned by any endpoint.
 * <p>
 * Secrets are stored at the <b>tenant level</b> — identified by
 * {@code (tenantId, keyName)}. Which agents may use a secret is governed by its
 * {@code allowedAgents} list, checked when an agent is deployed; these
 * endpoints are where an operator widens or narrows that grant.
 *
 * @author ginccc
 * @since 6.0.0
 */
@Path("/secretstore/secrets")
@Tag(name = "Security / Secrets Vault", description = "Encrypted secret storage and management")
@RolesAllowed("eddi-admin")
public interface IRestSecretStore {

    /**
     * Store or update a secret.
     *
     * @param tenantId
     *            the tenant namespace
     * @param keyName
     *            the secret key name
     * @param body
     *            the request body containing the plaintext value, description, and
     *            optional agent access list
     * @return 201 Created or 200 OK if updated
     */
    @PUT
    @Path("/{tenantId}/{keyName}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Store or update a secret")
    Response storeSecret(@PathParam("tenantId") String tenantId, @PathParam("keyName") String keyName, SecretRequest body);

    /**
     * Replace which agents may use an existing secret, <b>without</b> supplying its
     * value.
     * <p>
     * The gap this closes: {@link #storeSecret} is the only other way to write
     * {@code allowedAgents} and it requires the plaintext, which an operator does
     * not have once a key is vaulted. Widening a grant therefore meant recovering
     * the value from a backup or rotating the key — or granting {@code ["*"]} to
     * everything, which is the outcome that made this endpoint necessary.
     * <p>
     * {@code PUT} on a {@code /grant} sub-resource rather than {@code PATCH} on the
     * secret: the body replaces the grant wholesale, which is idempotent, and the
     * sub-resource is what makes the value structurally unreachable from here —
     * there is no field in {@link GrantRequest} that could carry it.
     *
     * @param tenantId
     *            the tenant namespace
     * @param keyName
     *            the secret key name
     * @param dryRun
     *            when true, nothing is written and the response reports what the
     *            change <em>would</em> do. Exists so a UI can show the "these
     *            deployed agents lose access" warning before the operator commits,
     *            rather than after
     * @param body
     *            the replacement grant list and an optional description
     * @return 200 with the new grant plus any deployed agents it strips access
     *         from, 404 if the secret does not exist, 400 on a malformed grant list
     */
    @PUT
    @Path("/{tenantId}/{keyName}/grant")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("eddi-admin")
    @Operation(summary = "Update a secret's agent grant",
               description = "Replaces the secret's allowedAgents list (and optionally its description) without "
                       + "touching the encrypted value — no plaintext is accepted or required. Use [\"*\"] to allow "
                       + "every agent. The response names any deployed agent that references the secret and would no "
                       + "longer be granted it; pass dryRun=true to see that without writing anything.")
    Response updateGrant(@PathParam("tenantId") String tenantId, @PathParam("keyName") String keyName,
                         @QueryParam("dryRun")
                         @DefaultValue("false") boolean dryRun, GrantRequest body);

    /**
     * Delete a secret from the vault.
     *
     * @param tenantId
     *            the tenant namespace
     * @param keyName
     *            the secret key name
     * @return 204 No Content
     */
    @DELETE
    @Path("/{tenantId}/{keyName}")
    @Operation(summary = "Delete a secret")
    Response deleteSecret(@PathParam("tenantId") String tenantId, @PathParam("keyName") String keyName);

    /**
     * Get non-sensitive metadata about a secret. Plaintext value is NEVER returned.
     *
     * @param tenantId
     *            the tenant namespace
     * @param keyName
     *            the secret key name
     * @return metadata (timestamps, checksum, description, allowedAgents)
     */
    @GET
    @Path("/{tenantId}/{keyName}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get secret metadata")
    Response getSecretMetadata(@PathParam("tenantId") String tenantId, @PathParam("keyName") String keyName);

    /**
     * List all secret keys for a given tenant.
     *
     * @param tenantId
     *            the tenant namespace
     * @return list of secret metadata
     */
    @GET
    @Path("/{tenantId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List all secrets for a tenant")
    Response listSecrets(@PathParam("tenantId") String tenantId);

    /**
     * Health check for the vault.
     *
     * @return 200 with status or 503 if unavailable
     */
    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Vault health check")
    Response healthCheck();

    /**
     * Rotate the Data Encryption Key (DEK) for a specific tenant. Re-encrypts all
     * secrets for the tenant with a newly generated DEK.
     *
     * @param tenantId
     *            the tenant whose DEK to rotate
     * @return 200 with the number of secrets re-encrypted
     */
    @POST
    @Path("/{tenantId}/rotate-dek")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("eddi-admin")
    @Operation(summary = "Rotate DEK for a tenant", description = "Generates a new Data Encryption Key and re-encrypts all secrets for the tenant. "
            + "This does NOT require a restart — the new DEK is used immediately.")
    Response rotateDek(@PathParam("tenantId") String tenantId);

    /**
     * Rotate the Key Encryption Key (KEK / Master Key). Re-encrypts all tenant DEKs
     * with the new master key. After this call, restart the application with the
     * new master key in the environment.
     *
     * @param body
     *            contains the old and new master keys
     * @return 200 with the number of DEKs re-encrypted
     */
    @POST
    @Path("/admin/rotate-kek")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("eddi-admin")
    @Operation(summary = "Rotate Master Key (KEK)", description = "Re-encrypts all tenant DEKs with a new master key. "
            + "WARNING: This endpoint transmits master keys in the request body. " + "Ensure TLS is enabled. After a successful rotation, update the "
            + "EDDI_VAULT_MASTER_KEY environment variable and restart the application.")
    Response rotateKek(KekRotationRequest body);

    /**
     * Reset the vault for a specific tenant. Deletes ALL secrets and the DEK for
     * the tenant, allowing the vault to start fresh with the current master key.
     * <p>
     * This is a destructive operation — all encrypted secrets for the tenant will
     * be permanently deleted. Use this when the master key has changed and the old
     * key is not available.
     *
     * @param tenantId
     *            the tenant to reset
     * @return 200 with details of what was deleted
     */
    @POST
    @Path("/{tenantId}/reset")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("eddi-admin")
    @Operation(summary = "Reset vault for a tenant",
               description = "Deletes ALL secrets and the Data Encryption Key for the tenant. "
                       + "Use this when the master key has changed and recovery is not possible. "
                       + "WARNING: This permanently destroys all encrypted secrets for the tenant.")
    Response resetTenant(@PathParam("tenantId") String tenantId);

    /**
     * Request body for storing a secret. Includes the plaintext value, an optional
     * description, and an optional allowed-agents list.
     *
     * @param value
     *            the plaintext secret value
     * @param description
     *            human-readable description (nullable)
     * @param allowedAgents
     *            list of agent IDs, or ["*"] for all (nullable → defaults to ["*"])
     */
    record SecretRequest(String value, String description, List<String> allowedAgents) {
    }

    /**
     * Request body for {@link #updateGrant}. Note what is <em>not</em> here: there
     * is no value field, so this request cannot express a change to the secret
     * itself.
     *
     * @param allowedAgents
     *            the replacement grant list. <b>Required</b> — unlike
     *            {@link SecretRequest}, an omitted list is rejected rather than
     *            defaulting to {@code ["*"]}. On a create, defaulting to the
     *            wildcard is a convenience; on an edit it would silently open a
     *            narrowed secret to every agent because a field was left out of a
     *            JSON body. An empty list is rejected for the same reason: it means
     *            "every agent" everywhere else. Send {@code ["*"]} to mean all
     *            agents
     * @param description
     *            the new description, or {@code null} to leave it as it is. An
     *            empty string clears it
     */
    record GrantRequest(List<String> allowedAgents, String description) {
    }

    /**
     * Request body for KEK rotation.
     *
     * @param oldMasterKey
     *            the current master key
     * @param newMasterKey
     *            the new master key to rotate to
     */
    record KekRotationRequest(String oldMasterKey, String newMasterKey) {
    }
}
