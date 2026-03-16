package ai.labs.eddi.secrets.rest;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * REST interface for managing secrets in the vault.
 * Secrets are stored encrypted; plaintext values are NEVER returned by any endpoint.
 */
@Path("/secretstore/secrets")
public interface IRestSecretStore {

    /**
     * Store or update a secret.
     *
     * @param tenantId the tenant namespace
     * @param botId    the bot identifier
     * @param keyName  the secret key name
     * @param body     the request body containing the plaintext value
     * @return 201 Created or 200 OK if updated
     */
    @PUT
    @Path("/{tenantId}/{botId}/{keyName}")
    @Consumes(MediaType.APPLICATION_JSON)
    Response storeSecret(@PathParam("tenantId") String tenantId,
                         @PathParam("botId") String botId,
                         @PathParam("keyName") String keyName,
                         SecretRequest body);

    /**
     * Delete a secret from the vault.
     *
     * @param tenantId the tenant namespace
     * @param botId    the bot identifier
     * @param keyName  the secret key name
     * @return 204 No Content
     */
    @DELETE
    @Path("/{tenantId}/{botId}/{keyName}")
    Response deleteSecret(@PathParam("tenantId") String tenantId,
                          @PathParam("botId") String botId,
                          @PathParam("keyName") String keyName);

    /**
     * Get non-sensitive metadata about a secret.
     * Plaintext value is NEVER returned.
     *
     * @param tenantId the tenant namespace
     * @param botId    the bot identifier
     * @param keyName  the secret key name
     * @return metadata (timestamps, checksum)
     */
    @GET
    @Path("/{tenantId}/{botId}/{keyName}")
    @Produces(MediaType.APPLICATION_JSON)
    Response getSecretMetadata(@PathParam("tenantId") String tenantId,
                               @PathParam("botId") String botId,
                               @PathParam("keyName") String keyName);

    /**
     * List all secret keys for a given bot namespace.
     *
     * @param tenantId the tenant namespace
     * @param botId    the bot identifier
     * @return list of secret metadata
     */
    @GET
    @Path("/{tenantId}/{botId}")
    @Produces(MediaType.APPLICATION_JSON)
    List<?> listSecrets(@PathParam("tenantId") String tenantId,
                        @PathParam("botId") String botId);

    /**
     * Health check for the vault.
     *
     * @return 200 with status or 503 if unavailable
     */
    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    Response healthCheck();

    /**
     * Request body for storing a secret.
     */
    record SecretRequest(String value) {}
}
