/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.rest;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultGrantImpactAnalyzer;
import ai.labs.eddi.secrets.impl.VaultSecretProvider;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /**
     * Upper bound on a grant list. Not a security control — an admin who can edit
     * grants can already delete the vault — but an unbounded list gets written by
     * mistake far more often than on purpose, and every entry is walked on every
     * deployment's grant check.
     */
    private static final int MAX_ALLOWED_AGENTS = 500;

    private final ISecretProvider secretProvider;
    private final SecretResolver secretResolver;
    private final VaultGrantImpactAnalyzer grantImpactAnalyzer;

    @Inject
    public RestSecretStore(ISecretProvider secretProvider, SecretResolver secretResolver, VaultGrantImpactAnalyzer grantImpactAnalyzer) {
        this.secretProvider = secretProvider;
        this.secretResolver = secretResolver;
        this.grantImpactAnalyzer = grantImpactAnalyzer;
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
            LOGGER.error("Failed to store secret: " + sanitize(tenantId) + "/" + sanitize(keyName), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "Failed to store secret")).build();
        }
    }

    @Override
    public Response updateGrant(String tenantId, String keyName, boolean dryRun, GrantRequest body) {
        var unavailable = vaultUnavailableResponse();
        if (unavailable.isPresent())
            return unavailable.get();

        try {
            validateId(tenantId, "tenantId");
            validateId(keyName, "keyName");
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }

        List<String> requested;
        try {
            requested = validatedGrant(body);
            validateExpectedGrant(body.expectedAllowedAgents());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }

        var ref = new SecretReference(tenantId, keyName);
        // Canonicalised before the impact analysis, not after, so the warning is
        // computed against exactly the list that will be stored.
        List<String> grant = SecretMetadata.canonicalGrant(requested);

        try {
            // Read first, so a dry run 404s on an unknown key exactly as a real write
            // does — a preview that succeeds where the write would fail is worse than
            // no preview.
            SecretMetadata before = secretProvider.getMetadata(ref);

            var impact = grantImpactAnalyzer.agentsLosingAccess(ref, grant);

            List<String> expected = body.expectedAllowedAgents();
            SecretMetadata after;
            if (dryRun) {
                // A dry run answers the precondition too, so a preview never promises a
                // write that would then be refused.
                if (expected != null && !SecretMetadata.sameGrant(before.allowedAgents(), expected)) {
                    return grantConflictResponse(ref, before.allowedAgents());
                }
                // The projection the write would produce, built here rather than by
                // calling the provider with a flag: a dry run that goes near the write
                // path is a dry run that can one day stop being dry.
                after = new SecretMetadata(before.tenantId(), before.keyName(), before.createdAt(), before.lastAccessedAt(),
                        before.lastRotatedAt(), before.checksum(), body.description() != null ? body.description() : before.description(),
                        grant);
            } else {
                after = expected == null
                        ? secretProvider.updateGrant(ref, grant, body.description())
                        : secretProvider.updateGrant(ref, grant, body.description(), expected);
                // Deliberately NOT invalidating the SecretResolver cache.
                //
                // storeSecret has to, because the plaintext behind the cached entry may
                // have changed. Here it cannot have: the value is untouched, so every
                // cached entry is still correct. And the cache plays no part in the
                // grant decision — VaultGrantChecker calls
                // ISecretProvider.getMetadata, which reads persistence directly on
                // every call, so the new grant is in force for the very next
                // deployment either way. Invalidating would only force a needless
                // decrypt for every agent already using the key.
            }

            return Response.ok(grantResponse(ref, before, after, impact, dryRun)).build();
        } catch (ISecretProvider.SecretNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Secret not found", "reference", ref.toReferenceString(), "action",
                            "Grants can only be changed on a secret that exists. Check the key name, or store the secret first."))
                    .build();
        } catch (ISecretProvider.GrantConflictException e) {
            return grantConflictResponse(ref, e.getCurrentAllowedAgents());
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.error("Failed to update the grant of secret: " + sanitize(tenantId) + "/" + sanitize(keyName), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "Failed to update secret grant")).build();
        }
    }

    /**
     * The grant list from a {@link GrantRequest}, validated and de-duplicated.
     * <p>
     * An absent list is an error rather than the wildcard default
     * {@code storeSecret} applies: on an edit, "the field was missing from the
     * JSON" must not be a way to open a narrowed secret to every agent.
     * <p>
     * An <em>empty</em> list is refused for the same reason. Every other layer
     * reads {@code []} as unrestricted, so accepting it here would let a client
     * that filtered its list down to nothing open the secret to everyone with a
     * 200. The only way to say "all agents" on this endpoint is {@code ["*"]}, out
     * loud.
     *
     * @throws IllegalArgumentException
     *             with an operator-readable message
     */
    private static List<String> validatedGrant(GrantRequest body) {
        if (body == null || body.allowedAgents() == null) {
            throw new IllegalArgumentException(
                    "allowedAgents is required. Send the full replacement list, or [\"*\"] to allow every agent.");
        }
        if (body.allowedAgents().isEmpty()) {
            throw new IllegalArgumentException("allowedAgents must not be empty: an empty list means every agent. "
                    + "Send [\"*\"] if that is what you intend, or list the agents that may use this secret.");
        }
        if (body.allowedAgents().size() > MAX_ALLOWED_AGENTS) {
            throw new IllegalArgumentException("allowedAgents must not contain more than " + MAX_ALLOWED_AGENTS + " entries.");
        }
        // LinkedHashSet: duplicates dropped, operator's order kept, so the list reads
        // back the way it was written.
        var distinct = new LinkedHashSet<String>();
        for (String agentId : body.allowedAgents()) {
            if (agentId == null || agentId.isBlank()) {
                throw new IllegalArgumentException("allowedAgents must not contain blank entries.");
            }
            String trimmed = agentId.trim();
            if (!SecretMetadata.WILDCARD_AGENT.equals(trimmed) && !VALID_ID.matcher(trimmed).matches()) {
                throw new IllegalArgumentException(
                        "allowedAgents entries must be agent IDs matching [a-zA-Z0-9._-]{1,128}, or \"*\" for all agents. Got: " + trimmed
                                + ". If this entry is already on the secret's grant, leave it out of the replacement list — it can "
                                + "never match an agent ID.");
            }
            distinct.add(trimmed);
        }
        return new ArrayList<>(distinct);
    }

    /**
     * The optional precondition list only has to be comparable, not valid as a new
     * grant: it may legitimately hold an entry an older release stored that would
     * no longer pass {@link #validatedGrant}. Bounded and null-free all the same,
     * since it arrives in a request body.
     */
    private static void validateExpectedGrant(List<String> expected) {
        if (expected == null) {
            return;
        }
        if (expected.size() > MAX_ALLOWED_AGENTS) {
            throw new IllegalArgumentException("expectedAllowedAgents must not contain more than " + MAX_ALLOWED_AGENTS + " entries.");
        }
        if (expected.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("expectedAllowedAgents must not contain null entries.");
        }
    }

    /**
     * 409 for a grant edit whose precondition no longer holds, with the grant as it
     * now stands so the editor can show it without another round trip.
     */
    private static Response grantConflictResponse(SecretReference ref, List<String> currentAllowedAgents) {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", "The grant was changed since it was loaded");
        body.put("reference", ref.toReferenceString());
        body.put("allowedAgents", SecretMetadata.canonicalGrant(currentAllowedAgents));
        body.put("action", "Nothing was written. Review the current grant and apply the edit again.");
        return Response.status(Response.Status.CONFLICT).entity(body).build();
    }

    /**
     * The response body for a grant update. Carries the previous list as well as
     * the new one — an operator changing a security control should be able to see
     * what it was, and a UI can diff the two without having re-read the secret
     * first.
     */
    private static Map<String, Object> grantResponse(SecretReference ref, SecretMetadata before, SecretMetadata after,
                                                     VaultGrantImpactAnalyzer.GrantImpact impact, boolean dryRun) {
        var losingAccess = impact.agentsLosingAccess();
        // LinkedHashMap, not Map.of: description is nullable and Map.of rejects nulls,
        // and a stable field order makes the response readable in a terminal.
        var response = new LinkedHashMap<String, Object>();
        response.put("reference", ref.toReferenceString());
        response.put("tenantId", ref.tenantId());
        response.put("keyName", ref.keyName());
        response.put("dryRun", dryRun);
        response.put("allowedAgents", after.allowedAgents());
        response.put("previousAllowedAgents", before.allowedAgents());
        response.put("grantsAllAgents", SecretMetadata.grantsAllAgents(after.allowedAgents()));
        response.put("description", after.description());
        // Echoed so the response itself evidences that a grant edit is not a rotation.
        response.put("createdAt", after.createdAt());
        response.put("lastRotatedAt", after.lastRotatedAt());
        response.put("agentsLosingAccess", losingAccess);
        // Said in the payload, not only in the docs: on a cluster the list covers the
        // agents deployed on the node that answered, and an API consumer should not
        // mistake it for the whole fleet.
        response.put("agentsLosingAccessScope", "this-node");
        // False when an environment could not be listed. A caller must be able to
        // tell "nothing breaks" from "could not tell", or it will read the empty
        // list as the former and narrow the grant on that basis.
        response.put("agentsLosingAccessComplete", impact.complete());
        // One key, both reasons: an incomplete scan and a named casualty can be true
        // at once, and a second put() would have silently dropped whichever came
        // first.
        var warnings = new ArrayList<String>();
        if (!losingAccess.isEmpty()) {
            warnings.add(losingAccess.size() + " deployed agent(s) reference this secret and are not on the new grant list. "
                    + "They keep running — the grant is checked when an agent is deployed, not when a secret is resolved — but under "
                    + "eddi.vault.grant-enforcement=enforce their next deployment will be REFUSED. Add them to allowedAgents, or "
                    + "remove the reference from their configuration first.");
        }
        if (!impact.complete()) {
            warnings.add("The deployed agents could not be listed in full, so this list may be short. Treat it as unknown "
                    + "rather than as empty before narrowing the grant.");
        }
        if (!warnings.isEmpty()) {
            response.put("warning", String.join(" ", warnings));
        }
        return response;
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
            LOGGER.error("Failed to delete secret: " + sanitize(tenantId) + "/" + sanitize(keyName), e);
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
            LOGGER.error("Failed to get secret metadata: " + sanitize(tenantId) + "/" + sanitize(keyName), e);
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
            LOGGER.error("Failed to list secrets for tenant: " + sanitize(tenantId), e);
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
            LOGGER.error("Failed to rotate DEK for tenant: " + sanitize(tenantId), e);
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
            LOGGER.error("Failed to rotate KEK", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "KEK rotation failed: " + e.getMessage())).build();
        }
    }

    @Override
    public Response adoptMasterKey(boolean confirm) {
        var unavailable = vaultUnavailableResponse();
        if (unavailable.isPresent())
            return unavailable.get();

        if (!confirm) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "confirm=true is required",
                            "action", "Only adopt the configured master key if the previous one is lost for good. During an unfinished KEK "
                                    + "rotation, re-run POST /secretstore/secrets/admin/rotate-kek instead — it recovers everything."))
                    .build();
        }
        if (!(secretProvider instanceof VaultSecretProvider vaultProvider)) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Adopting a master key is only supported by VaultSecretProvider")).build();
        }
        try {
            var adoption = vaultProvider.adoptCurrentMasterKey();
            secretResolver.invalidateAll();
            return Response.ok(Map.of("tenantsNeedingReset", adoption.tenantsNeedingReset(), "systemValuesReset", adoption.systemValuesReset(),
                    "message", "The configured master key is now the vault's master key and new secrets can be stored. "
                            + (adoption.tenantsNeedingReset().isEmpty()
                                    ? "No tenant holds unreadable DEKs."
                                    : "Reset each tenant listed in tenantsNeedingReset with POST /secretstore/secrets/{tenantId}/reset — "
                                            + "their secrets were sealed under the lost key.")))
                    .build();
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.error("Failed to adopt the master key", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "Adopting the master key failed")).build();
        }
    }

    @Override
    public Response resetTenant(String tenantId) {
        var unavailable = vaultUnavailableResponse();
        if (unavailable.isPresent())
            return unavailable.get();

        try {
            validateId(tenantId, "tenantId");
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }

        try {
            int deletedSecrets = secretProvider.resetTenant(tenantId);
            secretResolver.invalidateAll();
            return Response.ok(Map.of(
                    "tenantId", tenantId,
                    "secretsDeleted", deletedSecrets,
                    "message", "Vault reset for tenant '" + tenantId + "'. "
                            + deletedSecrets + " secret(s) deleted, DEK removed. "
                            + "The next secret store operation will generate a fresh DEK with the current master key."))
                    .build();
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.error("Failed to reset vault for tenant: " + sanitize(tenantId), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Vault reset failed: " + e.getMessage())).build();
        }
    }
}
