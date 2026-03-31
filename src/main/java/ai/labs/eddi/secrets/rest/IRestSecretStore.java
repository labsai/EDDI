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
 * Secrets are scoped at the <b>tenant level</b> — identified by
 * {@code (tenantId, keyName)}. Access control is via configuration authorship
 * (the admin writes vault references into agent configs).
 *
 * @author ginccc
 * @since 6.0.0
 */
@Path("/secretstore/secrets")
@Tag(name = "Secrets Vault")
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
    @Operation(summary = "Rotate DEK for a tenant")
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
    @Operation(summary = "Rotate Master Key (KEK)")
    Response rotateKek(KekRotationRequest body);

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
