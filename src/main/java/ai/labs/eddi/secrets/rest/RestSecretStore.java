/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.rest;

import ai.labs.eddi.secrets.AutoVaultedSecrets;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.UnrestrictedSecretsCheck;
import ai.labs.eddi.secrets.VaultGrantGate;
import ai.labs.eddi.secrets.VaultGrantImpactAnalyzer;
import ai.labs.eddi.secrets.VaultGrantRequests;
import ai.labs.eddi.secrets.VaultGrantService;
import ai.labs.eddi.secrets.VaultHostAllowlist;
import ai.labs.eddi.secrets.impl.VaultSecretProvider;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /** See {@link VaultGrantRequests#MAX_ALLOWED_AGENTS}. */
    private static final int MAX_ALLOWED_AGENTS = VaultGrantRequests.MAX_ALLOWED_AGENTS;

    private final ISecretProvider secretProvider;
    private final SecretResolver secretResolver;
    private final VaultGrantImpactAnalyzer grantImpactAnalyzer;
    /**
     * Decides whether a create may omit its grant — see
     * {@link VaultGrantRequests#requireGrantOnCreate}.
     */
    private final VaultGrantGate.Mode grantEnforcement;

    /**
     * Field-injected so the existing constructor (and its tests) keep their
     * signature. The append endpoint answers 503 without it.
     */
    @Inject
    VaultGrantService grantService;

    /** The caller, recorded as the actor of a grant append; null in tests. */
    @Inject
    SecurityIdentity identity;

    /**
     * The startup scan for referenced secrets without a destination allow-list (H1
     * residual of the 2026-10-17 review); null in tests, which leaves
     * {@code referencedBy} out of the listing.
     */
    @Inject
    UnrestrictedSecretsCheck unrestrictedSecretsCheck;

    @Inject
    public RestSecretStore(ISecretProvider secretProvider, SecretResolver secretResolver, VaultGrantImpactAnalyzer grantImpactAnalyzer,
            @ConfigProperty(name = "eddi.vault.grant-enforcement", defaultValue = VaultGrantGate.DEFAULT_MODE_NAME) String grantEnforcement) {
        this(secretProvider, secretResolver, grantImpactAnalyzer, VaultGrantGate.Mode.parseStrict(grantEnforcement));
    }

    /**
     * The shipped mode, {@code enforce}. For the tests that construct the store
     * directly: a test that omits the mode gets the strict one, never a quieter
     * one.
     */
    RestSecretStore(ISecretProvider secretProvider, SecretResolver secretResolver, VaultGrantImpactAnalyzer grantImpactAnalyzer) {
        this(secretProvider, secretResolver, grantImpactAnalyzer, VaultGrantGate.Mode.ENFORCE);
    }

    RestSecretStore(ISecretProvider secretProvider, SecretResolver secretResolver, VaultGrantImpactAnalyzer grantImpactAnalyzer,
            VaultGrantGate.Mode grantEnforcement) {
        this.secretProvider = secretProvider;
        this.secretResolver = secretResolver;
        this.grantImpactAnalyzer = grantImpactAnalyzer;
        this.grantEnforcement = grantEnforcement;
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
        if (AutoVaultedSecrets.isReservedName(keyName)) {
            // GDPR erasure deletes a user's auto-vaulted slots by name, so a manual key
            // in that shape would be erased with them.
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "keyName '" + keyName + "' has the reserved shape of an auto-vaulted conversation secret "
                            + "(<agentId>.u<16 hex>.<12 hex>.<name>). Choose another name."))
                    .build();
        }
        if (body == null || body.value() == null || body.value().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Secret value must not be empty")).build();
        }
        List<String> allowedAgents;
        List<String> allowedHosts;
        try {
            // A list that IS present is held to the grant endpoint's rules whatever the
            // mode: a malformed id or an empty list is wrong in every mode.
            allowedAgents = VaultGrantRequests.validatedGrant(body.allowedAgents());
            allowedHosts = VaultHostAllowlist.requestedHosts(body.allowedHosts());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
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

            if (!exists) {
                try {
                    VaultGrantRequests.requireGrantOnCreate(grantEnforcement, allowedAgents);
                    VaultGrantRequests.requireHostsOnCreate(grantEnforcement, body.allowedHosts());
                } catch (IllegalArgumentException e) {
                    return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
                }
                if (allowedAgents == null) {
                    // warn / off: the convenience those modes opted into, stated here rather
                    // than left to a persistence default.
                    allowedAgents = List.of(SecretMetadata.WILDCARD_AGENT);
                }
                // Agents deployed while this secret was absent were never checked against
                // its grant; this is that check.
                String refusal = grantImpactAnalyzer.refusalToCreate(ref, allowedAgents, grantEnforcement);
                if (refusal != null) {
                    return Response.status(Response.Status.CONFLICT).entity(Map.of("error", refusal)).build();
                }
            }

            secretProvider.store(ref, body.value(), body.description(), allowedAgents, allowedHosts);

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
        List<String> allowedHosts;
        try {
            requested = validatedGrant(body);
            validateExpectedGrant(body.expectedAllowedAgents());
            // ["*"] reads as [] here, the clear it already was on this endpoint, so the
            // spelling a create requires is accepted on an edit too.
            allowedHosts = VaultHostAllowlist.requestedHosts(body.allowedHosts());
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
                        grant, allowedHosts != null ? allowedHosts : before.allowedHosts());
            } else {
                if (allowedHosts != null) {
                    // Two independent writes, the host list FIRST. The precondition below
                    // does not cover the hosts, and if the second write fails the row must
                    // be left in its tighter state: a narrowed host list without the widened
                    // grant is safe, a widened grant without the narrowed host list is not.
                    secretProvider.updateAllowedHosts(ref, allowedHosts);
                }
                after = expected == null
                        ? secretProvider.updateGrant(ref, grant, body.description())
                        : secretProvider.updateGrant(ref, grant, body.description(), expected);
                if (allowedHosts != null) {
                    // Invalidated for the host list, unlike the grant below: the list is
                    // checked when a model, embedding store or MCP client is BUILT, and
                    // those are cached. Without the eviction their invalidation listeners
                    // perform, a narrowed list would not reach a client already built.
                    secretResolver.invalidateCache(ref);
                }
                // For the agent grant alone, deliberately NOT invalidating the
                // SecretResolver cache.
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
        return VaultGrantRequests.validatedGrant(body.allowedAgents());
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
     * The metadata as it may leave over REST — with the {@code checksum} nulled
     * out. The stored checksum is a keyed value of the plaintext used for internal
     * dedup and value-match; exposing it hands an offline attacker a target to test
     * guesses against, so it never crosses the API boundary. A null field is
     * omitted from the response body, so callers simply see no {@code checksum}.
     */
    private static SecretMetadata withoutChecksum(SecretMetadata metadata) {
        if (metadata == null || metadata.checksum() == null) {
            return metadata;
        }
        return new SecretMetadata(metadata.tenantId(), metadata.keyName(), metadata.createdAt(), metadata.lastAccessedAt(),
                metadata.lastRotatedAt(), null, metadata.description(), metadata.allowedAgents(), metadata.allowedHosts());
    }

    /**
     * One entry of {@code GET /{tenantId}}: the metadata without its checksum, plus
     * whether the secret may be sent anywhere and which configurations the last
     * startup scan found referencing it.
     */
    private SecretListing listing(SecretMetadata metadata) {
        List<String> referencedBy = unrestrictedSecretsCheck == null
                ? null
                : unrestrictedSecretsCheck.referencedBy(new SecretReference(metadata.tenantId(), metadata.keyName())).orElse(null);
        return SecretListing.of(withoutChecksum(metadata), referencedBy);
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
        response.put("allowedHosts", after.allowedHosts() == null ? List.of() : after.allowedHosts());
        response.put("description", after.description());
        // Echoed so the response itself evidences that a grant edit is not a rotation.
        response.put("createdAt", after.createdAt());
        response.put("lastRotatedAt", after.lastRotatedAt());
        response.put("agentsLosingAccess", losingAccess);
        // Said in the payload, not only in the docs: the list covers every recorded
        // deployment in the shared database plus this node's runtime registry. An
        // autoDeploy=false deployment made on another node is unrecorded, and is seen
        // here once that node's announcement has deployed it on this one too.
        response.put("agentsLosingAccessScope", "cluster");
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
    public Response grantAgent(String tenantId, String keyName, String agentId, boolean dryRun) {
        var unavailable = vaultUnavailableResponse();
        if (unavailable.isPresent())
            return unavailable.get();

        try {
            validateId(tenantId, "tenantId");
            validateId(keyName, "keyName");
            validateId(agentId, "agentId");
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }
        if (grantService == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(Map.of("error", "Grant service unavailable")).build();
        }

        var ref = new SecretReference(tenantId, keyName);
        try {
            var result = grantService.grantAgent(ref, agentId, actor(), "rest", dryRun);
            // An append removes nobody, so this is expected to be empty; computed
            // rather than assumed, so the response can never claim it falsely.
            var impact = grantImpactAnalyzer.agentsLosingAccess(ref, result.after().allowedAgents());
            var body = grantResponse(ref, result.before(), result.after(), impact, dryRun);
            body.put("changed", result.changed());
            body.put("agentId", agentId);
            return Response.ok(body).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        } catch (ISecretProvider.SecretNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Secret not found", "reference", ref.toReferenceString(), "action",
                            "Grants can only be changed on a secret that exists. Check the key name, or store the secret first."))
                    .build();
        } catch (ISecretProvider.GrantConflictException e) {
            return grantConflictResponse(ref, e.getCurrentAllowedAgents());
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.error("Failed to add an agent to the grant of secret: " + sanitize(tenantId) + "/" + sanitize(keyName), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "Failed to update secret grant")).build();
        }
    }

    private String actor() {
        return identity != null && !identity.isAnonymous() && identity.getPrincipal() != null ? identity.getPrincipal().getName() : null;
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
            return Response.ok(withoutChecksum(metadata)).build();
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
            return Response.ok(secretProvider.listKeys(tenantId).stream().map(this::listing).toList()).build();
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
                    "checksumKeyReset", adoption.checksumKeyReset(),
                    "message", "The configured master key is now the vault's master key and new secrets can be stored. "
                            + (adoption.tenantsNeedingReset().isEmpty()
                                    ? "No tenant holds unreadable DEKs."
                                    : "Reset each tenant listed in tenantsNeedingReset with POST /secretstore/secrets/{tenantId}/reset?confirm=true — "
                                            + "their secrets were sealed under the lost key.")))
                    .build();
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.error("Failed to adopt the master key", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "Adopting the master key failed")).build();
        }
    }

    @Override
    public Response resetTenant(String tenantId, boolean confirm) {
        var unavailable = vaultUnavailableResponse();
        if (unavailable.isPresent())
            return unavailable.get();

        if (!confirm) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "confirm=true is required",
                            "action", "Resetting a tenant permanently deletes every secret and DEK it holds. Only do this when its "
                                    + "secrets were sealed under a master key that is lost for good, then repeat the request with "
                                    + "?confirm=true."))
                    .build();
        }

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
