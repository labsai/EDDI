package ai.labs.eddi.secrets.rest;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * REST implementation for the Secrets Vault API. All endpoints require
 * authentication (handled by Keycloak). Plaintext secret values are NEVER
 * returned — only accepted via PUT.
 */
@ApplicationScoped
public class RestSecretStore implements IRestSecretStore {

    private static final Logger LOGGER = Logger.getLogger(RestSecretStore.class);
    private static final Pattern VALID_ID = Pattern.compile("[a-zA-Z0-9._\\-]{1,128}");

    private final ISecretProvider secretProvider;

    @Inject
    public RestSecretStore(ISecretProvider secretProvider) {
        this.secretProvider = secretProvider;
    }

    /**
     * Check if the vault is unavailable and return an actionable 503 response.
     * Returns empty if the vault is available (caller should proceed normally).
     */
    private Optional<Response> vaultUnavailableResponse() {
        if (!secretProvider.isAvailable()) {
            return Optional.of(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Secrets Vault is not configured", "reason", "The EDDI_VAULT_MASTER_KEY environment variable is not set.",
                            "action",
                            "Set the EDDI_VAULT_MASTER_KEY environment variable and restart EDDI. "
                                    + "For local development, use: set EDDI_VAULT_MASTER_KEY=any-passphrase-at-least-8-chars",
                            "docs", "https://docs.labs.ai/secrets-vault"))
                    .build());
        }
        return Optional.empty();
    }

    /**
     * Validate that a path parameter is safe (alphanumeric + dots, hyphens,
     * underscores). Prevents path traversal and injection attacks.
     */
    private static void validateId(String id, String paramName) {
        if (id == null || !VALID_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(paramName + " must match [a-zA-Z0-9._-]{1,128}, got: " + id);
        }
    }

    @Override
    public Response storeSecret(String tenantId, String agentId, String keyName, SecretRequest body) {
        var unavailable = vaultUnavailableResponse();
        if (unavailable.isPresent())
            return unavailable.get();

        try {
            validateId(tenantId, "tenantId");
            validateId(agentId, "agentId");
            validateId(keyName, "keyName");
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }
        if (body == null || body.value() == null || body.value().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Secret value must not be empty")).build();
        }

        try {
            var ref = new SecretReference(tenantId, agentId, keyName);

            // Check if exists (for correct HTTP status)
            boolean exists;
            try {
                secretProvider.getMetadata(ref);
                exists = true;
            } catch (ISecretProvider.SecretNotFoundException e) {
                exists = false;
            }

            secretProvider.store(ref, body.value());

            var responseRef = Map.of("reference", ref.toReferenceString(), "tenantId", tenantId, "agentId", agentId, "keyName", keyName);

            if (exists) {
                return Response.ok(responseRef).build();
            } else {
                return Response.status(Response.Status.CREATED).entity(responseRef).build();
            }
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.errorv("Failed to store secret: {0}/{1}/{2} — {3}", tenantId, agentId, keyName, e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "Failed to store secret")).build();
        }
    }

    @Override
    public Response deleteSecret(String tenantId, String agentId, String keyName) {
        var unavailable = vaultUnavailableResponse();
        if (unavailable.isPresent())
            return unavailable.get();

        try {
            validateId(tenantId, "tenantId");
            validateId(agentId, "agentId");
            validateId(keyName, "keyName");
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }
        try {
            secretProvider.delete(new SecretReference(tenantId, agentId, keyName));
            return Response.noContent().build();
        } catch (ISecretProvider.SecretNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Secret not found")).build();
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.errorv("Failed to delete secret: {0}/{1}/{2} — {3}", tenantId, agentId, keyName, e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "Failed to delete secret")).build();
        }
    }

    @Override
    public Response getSecretMetadata(String tenantId, String agentId, String keyName) {
        var unavailable = vaultUnavailableResponse();
        if (unavailable.isPresent())
            return unavailable.get();

        try {
            validateId(tenantId, "tenantId");
            validateId(agentId, "agentId");
            validateId(keyName, "keyName");
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }
        try {
            SecretMetadata metadata = secretProvider.getMetadata(new SecretReference(tenantId, agentId, keyName));
            return Response.ok(metadata).build();
        } catch (ISecretProvider.SecretNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Secret not found")).build();
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.errorv("Failed to get secret metadata: {0}/{1}/{2} — {3}", tenantId, agentId, keyName, e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "Failed to get metadata")).build();
        }
    }

    @Override
    public Response listSecrets(String tenantId, String agentId) {
        var unavailable = vaultUnavailableResponse();
        if (unavailable.isPresent())
            return unavailable.get();

        try {
            validateId(tenantId, "tenantId");
            validateId(agentId, "agentId");
        } catch (IllegalArgumentException e) {
            return Response.ok(List.of()).build();
        }
        try {
            return Response.ok(secretProvider.listKeys(tenantId, agentId)).build();
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.errorv("Failed to list secrets: {0}/{1} — {2}", tenantId, agentId, e.getMessage());
            return Response.ok(List.of()).build();
        }
    }

    @Override
    public Response healthCheck() {
        boolean available = secretProvider.isAvailable();
        var status = Map.of("status", available ? "UP" : "DOWN", "provider", secretProvider.getClass().getSimpleName(), "available", available);

        if (available) {
            return Response.ok(status).build();
        } else {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(status).build();
        }
    }
}
