/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.rest;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.impl.VaultSecretProvider;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * REST implementation for the Secrets Vault API. All endpoints require
 * authentication (handled by Keycloak). Plaintext secret values are NEVER
 * returned — only accepted via PUT.
 * <p>
 * Secrets are scoped at the <b>tenant level</b>. The {@code agentId} has been
 * removed from paths — it was impractical to duplicate shared secrets (like API
 * keys) across every agent.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class RestSecretStore implements IRestSecretStore {

    private static final Logger LOGGER = Logger.getLogger(RestSecretStore.class);
    private static final Pattern VALID_ID = Pattern.compile("[a-zA-Z0-9._\\-]{1,128}");

    private final ISecretProvider secretProvider;
    private final SecretResolver secretResolver;

    @Inject
    public RestSecretStore(ISecretProvider secretProvider, SecretResolver secretResolver) {
        this.secretProvider = secretProvider;
        this.secretResolver = secretResolver;
    }

    /**
     * Check if the vault is unavailable and return an actionable 503 response.
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
    public Response storeSecret(String tenantId, String keyName, SecretRequest body) {
        var unavailable = vaultUnavailableResponse();
        if (unavailable.isPresent())
            return unavailable.get();

        try {
            validateId(tenantId, "tenantId");
            validateId(keyName, "keyName");
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }
        if (body == null || body.value() == null || body.value().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Secret value must not be empty")).build();
        }

        try {
            var ref = new SecretReference(tenantId, keyName);

            // Check if exists (for correct HTTP status)
            boolean exists;
            try {
                secretProvider.getMetadata(ref);
                exists = true;
            } catch (ISecretProvider.SecretNotFoundException e) {
                exists = false;
            }

            secretProvider.store(ref, body.value(), body.description(), body.allowedAgents());

            // Always invalidate: on update, the SecretResolver cache has stale plaintext.
            // On new creation, the ChatModelRegistry may have cached a model that was
            // built with the unresolved vault reference as a literal string (failed
            // resolution).
            secretResolver.invalidateCache(ref);

            var responseRef = Map.of("reference", ref.toReferenceString(), "tenantId", tenantId, "keyName", keyName);

            if (exists) {
                return Response.ok(responseRef).build();
            } else {
                return Response.status(Response.Status.CREATED).entity(responseRef).build();
            }
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.errorf("Failed to store secret: %s/%s — %s", sanitize(tenantId), sanitize(keyName), e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "Failed to store secret")).build();
        }
    }

    @Override
    public Response deleteSecret(String tenantId, String keyName) {
        var unavailable = vaultUnavailableResponse();
        if (unavailable.isPresent())
            return unavailable.get();

        try {
            validateId(tenantId, "tenantId");
            validateId(keyName, "keyName");
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }
        try {
            var ref = new SecretReference(tenantId, keyName);
            secretProvider.delete(ref);
            secretResolver.invalidateCache(ref);
            return Response.noContent().build();
        } catch (ISecretProvider.SecretNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Secret not found")).build();
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.errorf("Failed to delete secret: %s/%s — %s", sanitize(tenantId), sanitize(keyName), e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "Failed to delete secret")).build();
        }
    }

    @Override
    public Response getSecretMetadata(String tenantId, String keyName) {
        var unavailable = vaultUnavailableResponse();
        if (unavailable.isPresent())
            return unavailable.get();

        try {
            validateId(tenantId, "tenantId");
            validateId(keyName, "keyName");
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }
        try {
            SecretMetadata metadata = secretProvider.getMetadata(new SecretReference(tenantId, keyName));
            return Response.ok(metadata).build();
        } catch (ISecretProvider.SecretNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Secret not found")).build();
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.errorf("Failed to get secret metadata: %s/%s — %s", sanitize(tenantId), sanitize(keyName), e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "Failed to get metadata")).build();
        }
    }

    @Override
    public Response listSecrets(String tenantId) {
        var unavailable = vaultUnavailableResponse();
        if (unavailable.isPresent())
            return unavailable.get();

        try {
            validateId(tenantId, "tenantId");
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }
        try {
            return Response.ok(secretProvider.listKeys(tenantId)).build();
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.errorf("Failed to list secrets: %s — %s", sanitize(tenantId), e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "Failed to list secrets")).build();
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

    @Override
    public Response rotateDek(String tenantId) {
        var unavailable = vaultUnavailableResponse();
        if (unavailable.isPresent())
            return unavailable.get();

        try {
            validateId(tenantId, "tenantId");
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }
        try {
            int count = secretProvider.rotateDek(tenantId);
            secretResolver.invalidateAll(); // All cached secrets for this tenant may have changed
            return Response.ok(Map.of("tenantId", tenantId, "secretsReEncrypted", count, "message",
                    "DEK rotated successfully. " + count + " secrets re-encrypted.")).build();
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.errorf("Failed to rotate DEK for tenant %s: %s", sanitize(tenantId), e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "DEK rotation failed: " + e.getMessage())).build();
        }
    }

    @Override
    public Response rotateKek(KekRotationRequest body) {
        var unavailable = vaultUnavailableResponse();
        if (unavailable.isPresent())
            return unavailable.get();

        if (body == null || body.oldMasterKey() == null || body.oldMasterKey().isBlank() || body.newMasterKey() == null
                || body.newMasterKey().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Both oldMasterKey and newMasterKey are required and must not be empty")).build();
        }
        if (body.newMasterKey().length() < 8) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "New master key must be at least 8 characters")).build();
        }

        if (!(secretProvider instanceof VaultSecretProvider vaultProvider)) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "KEK rotation is only supported by VaultSecretProvider")).build();
        }

        try {
            int count = vaultProvider.rotateKek(body.oldMasterKey(), body.newMasterKey());
            secretResolver.invalidateAll(); // All cached secrets use the old KEK chain
            return Response.ok(Map.of("deksReEncrypted", count, "message", "KEK rotated successfully. " + count + " DEKs re-encrypted. "
                    + "IMPORTANT: Update the EDDI_VAULT_MASTER_KEY environment variable to the new key and restart.")).build();
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.errorf("Failed to rotate KEK: %s", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "KEK rotation failed: " + e.getMessage())).build();
        }
    }
}
