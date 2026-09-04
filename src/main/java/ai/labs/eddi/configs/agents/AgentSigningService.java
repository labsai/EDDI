/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;
import ai.labs.eddi.configs.agents.crypto.SignedEnvelope;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.model.SecretReference;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ed25519-based signing and verification service for agent identity.
 * <p>
 * Protects against:
 * <ul>
 * <li>Identity spoofing via prompt injection — attacker manipulates an LLM to
 * claim it's another agent</li>
 * <li>Tampered inter-agent context — messages between agents modified in
 * flight</li>
 * <li>Audit repudiation — proves which agent actually generated a particular
 * output</li>
 * </ul>
 * <p>
 * Key lifecycle:
 * <ol>
 * <li>On agent creation: {@link #generateKeyPair(String, String)} creates an
 * Ed25519 keypair</li>
 * <li>Public key stored in {@code AgentConfiguration.identity.publicKey}</li>
 * <li>Private key stored in {@link ISecretProvider} (encrypted, never in config
 * JSON)</li>
 * <li>On deletion: {@link #deleteKeyPair(String, String)} cleans up vault
 * entry</li>
 * </ol>
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class AgentSigningService {
    private static final Logger LOGGER = Logger.getLogger(AgentSigningService.class);
    private static final String ALGORITHM = "Ed25519";
    private static final String VAULT_KEY_PREFIX = "agent-signing-key:";
    /**
     * Maximum key version to scan during deletion cleanup. Package-private so the
     * test can pin the number of vault round trips one agent deletion costs.
     */
    static final int MAX_KEY_VERSION_SCAN = 100;

    private final ISecretProvider secretProvider;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, PrivateKey> privateKeyCache = new ConcurrentHashMap<>();
    private Counter signCounter;
    private Counter verifySuccessCounter;
    private Counter verifyFailCounter;

    @Inject
    public AgentSigningService(ISecretProvider secretProvider, MeterRegistry meterRegistry) {
        this.secretProvider = secretProvider;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void initMetrics() {
        signCounter = meterRegistry.counter("eddi.agent.identity.sign.count");
        verifySuccessCounter = meterRegistry.counter("eddi.agent.identity.verify.success");
        verifyFailCounter = meterRegistry.counter("eddi.agent.identity.verify.fail");
    }

    /**
     * Generate a new Ed25519 keypair for an agent.
     *
     * @param tenantId
     *            the tenant identifier
     * @param agentId
     *            the agent identifier
     * @return the Base64-encoded public key (to store in AgentConfiguration)
     * @throws AgentSigningException
     *             if key generation fails
     */
    public String generateKeyPair(String tenantId, String agentId) throws AgentSigningException {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(ALGORITHM);
            KeyPair keyPair = keyGen.generateKeyPair();

            String publicKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            String privateKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());

            // Store private key in vault
            SecretReference ref = new SecretReference(tenantId, vaultKeyName(agentId));
            secretProvider.store(ref, privateKeyB64,
                    "Ed25519 signing key for agent " + agentId,
                    List.of(agentId));

            // Evict cached private key so the new key is used immediately
            // (prevents stale key on re-generation / rotation)
            privateKeyCache.remove(cacheKey(tenantId, agentId));

            LOGGER.infof("Generated Ed25519 keypair for agent '%s' in tenant '%s' (cache evicted)", agentId, tenantId);
            return publicKeyB64;
        } catch (NoSuchAlgorithmException e) {
            throw new AgentSigningException("Ed25519 not available in JVM", e);
        } catch (ISecretProvider.SecretProviderException e) {
            throw new AgentSigningException("Failed to store private key in vault", e);
        }
    }

    /**
     * Sign a message payload using the agent's private key.
     *
     * @param tenantId
     *            the tenant identifier
     * @param agentId
     *            the agent identifier
     * @param payload
     *            the message to sign
     * @return Base64-encoded signature
     * @throws AgentSigningException
     *             if signing fails
     */
    public String sign(String tenantId, String agentId, String payload) throws AgentSigningException {
        try {
            PrivateKey privateKey = loadPrivateKey(tenantId, agentId, 0);

            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initSign(privateKey);
            sig.update(payload.getBytes(StandardCharsets.UTF_8));
            byte[] signatureBytes = sig.sign();

            signCounter.increment();
            return Base64.getEncoder().encodeToString(signatureBytes);
        } catch (PrivateKeyLoadException e) {
            // Unwrap typed exception from computeIfAbsent — preserves
            // original cause type (SecretNotFound, InvalidKeySpec, etc.)
            Throwable cause = e.getCause();
            if (cause instanceof ISecretProvider.SecretNotFoundException) {
                throw new AgentSigningException("No signing key found for agent " + agentId, cause);
            }
            throw new AgentSigningException(
                    "Failed to load private key for agent " + agentId + ": " + cause.getClass().getSimpleName(),
                    cause);
        } catch (Exception e) {
            throw new AgentSigningException("Signing failed for agent " + agentId, e);
        }
    }

    /**
     * Verify a signature against a public key and payload.
     *
     * @param publicKeyB64
     *            Base64-encoded public key from AgentConfiguration
     * @param payload
     *            the original message
     * @param signatureB64
     *            the Base64-encoded signature to verify
     * @return true if the signature is valid
     */
    public boolean verify(String publicKeyB64, String payload, String signatureB64) {
        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyB64);
            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
            PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));

            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(publicKey);
            sig.update(payload.getBytes(StandardCharsets.UTF_8));
            boolean valid = sig.verify(Base64.getDecoder().decode(signatureB64));

            if (valid) {
                verifySuccessCounter.increment();
            } else {
                verifyFailCounter.increment();
            }
            return valid;
        } catch (Exception e) {
            LOGGER.warnf("Signature verification failed: %s", e.getMessage());
            verifyFailCounter.increment();
            return false;
        }
    }

    /**
     * Delete the signing keypair for an agent (cleanup on agent deletion). Removes
     * both the legacy unversioned key and any versioned keys.
     *
     * <p>
     * The whole version range is scanned rather than stopped at the first gap:
     * {@code rotateKey} takes an arbitrary version number from its caller, so a
     * rotation that skipped a number (v1, v3) used to leave every key after the gap
     * in the vault forever. And the legacy key is deleted in its own guard, because
     * it not existing is the ordinary state of a rotated agent — letting that
     * {@code SecretNotFoundException} out skipped the versioned scan entirely and
     * leaked exactly the keys this method exists to remove.
     * </p>
     *
     * <p>
     * "Absent" and "vault unreachable" are told apart. A blanket
     * {@code catch (Exception ignored)} across a hundred iterations meant a vault
     * outage mid-delete left every remaining key behind in complete silence, which
     * is the same leak wearing a success message.
     * </p>
     */
    public void deleteKeyPair(String tenantId, String agentId) {
        deleteVaultKey(tenantId, agentId, vaultKeyName(agentId), cacheKey(tenantId, agentId));

        for (int v = 1; v <= MAX_KEY_VERSION_SCAN; v++) {
            deleteVaultKey(tenantId, agentId, vaultKeyNameVersioned(agentId, v), cacheKey(tenantId, agentId) + ";v=" + v);
        }
        LOGGER.infof("Deleted signing keys for agent '%s' in tenant '%s' (cache evicted)", agentId, tenantId);
    }

    /**
     * Removes one vault entry and its cache line, treating "no such key" as the
     * expected case and anything else as a leak worth reporting.
     */
    private void deleteVaultKey(String tenantId, String agentId, String vaultKey, String cacheKeyStr) {
        try {
            secretProvider.delete(new SecretReference(tenantId, vaultKey));
            privateKeyCache.remove(cacheKeyStr);
        } catch (ISecretProvider.SecretNotFoundException e) {
            // Expected for every version this agent never had.
            privateKeyCache.remove(cacheKeyStr);
        } catch (Exception e) {
            LOGGER.warnf("Could not delete vault key '%s' for agent '%s' in tenant '%s' — it may still hold private key "
                    + "material: %s", vaultKey, agentId, tenantId, e.getMessage());
        }
    }

    /**
     * Loads (and caches) an agent's private key.
     *
     * <p>
     * One implementation for both signing paths — {@link #sign} and
     * {@link #signEnvelope} each carried a verbatim copy, so a fix to key loading
     * had to be applied twice, and their error handling had already drifted apart.
     * </p>
     *
     * <p>
     * The vault round-trip happens OUTSIDE {@code computeIfAbsent}: doing it inside
     * held a ConcurrentHashMap bin lock across network I/O, blocking unrelated
     * agents that hash to the same bin. A racing loser simply loads the same key
     * twice, which is harmless.
     * </p>
     *
     * @param keyVersion
     *            the rotated key version, or {@code <= 0} for the legacy
     *            unversioned key
     * @throws PrivateKeyLoadException
     *             wrapping the original cause, so callers can report precisely
     */
    private PrivateKey loadPrivateKey(String tenantId, String agentId, int keyVersion) {
        String cacheKeyStr = keyVersion > 0 ? cacheKey(tenantId, agentId) + ";v=" + keyVersion : cacheKey(tenantId, agentId);

        PrivateKey cached = privateKeyCache.get(cacheKeyStr);
        if (cached != null) {
            return cached;
        }

        String vaultKey = keyVersion > 0 ? vaultKeyNameVersioned(agentId, keyVersion) : vaultKeyName(agentId);
        PrivateKey loaded;
        try {
            SecretReference ref = new SecretReference(tenantId, vaultKey);
            String privateKeyB64 = secretProvider.resolve(ref);
            byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyB64);
            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
            loaded = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
        } catch (Exception e) {
            throw new PrivateKeyLoadException(agentId, e);
        }

        PrivateKey raced = privateKeyCache.putIfAbsent(cacheKeyStr, loaded);
        return raced != null ? raced : loaded;
    }

    private String vaultKeyName(String agentId) {
        return VAULT_KEY_PREFIX + agentId;
    }

    private String vaultKeyNameVersioned(String agentId, int version) {
        return VAULT_KEY_PREFIX + agentId + ":v" + version;
    }

    /**
     * Collision-resistant cache key: uses a structured format so that
     * tenantId="a:b", agentId="c" cannot collide with tenantId="a", agentId="b:c".
     */
    private static String cacheKey(String tenantId, String agentId) {
        return "tenant=" + tenantId + ";agent=" + agentId;
    }

    /**
     * Generate a versioned keypair for key rotation.
     *
     * @param tenantId
     *            the tenant identifier
     * @param agentId
     *            the agent identifier
     * @param version
     *            the key version number
     * @return the Base64-encoded public key
     * @throws AgentSigningException
     *             if key generation fails
     */
    public String generateKeyPairVersioned(String tenantId, String agentId, int version) throws AgentSigningException {
        if (version <= 0) {
            throw new AgentSigningException("Key version must be positive, got: " + version, null);
        }
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(ALGORITHM);
            KeyPair keyPair = keyGen.generateKeyPair();

            String publicKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            String privateKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());

            // Store versioned private key in vault
            SecretReference ref = new SecretReference(tenantId, vaultKeyNameVersioned(agentId, version));
            secretProvider.store(ref, privateKeyB64,
                    "Ed25519 signing key v" + version + " for agent " + agentId,
                    List.of(agentId));

            // Evict version-specific cached private key so the new key is used immediately
            privateKeyCache.remove(cacheKey(tenantId, agentId) + ";v=" + version);
            // Also evict the legacy unversioned entry (if any)
            privateKeyCache.remove(cacheKey(tenantId, agentId));

            LOGGER.infof("Generated Ed25519 keypair v%d for agent '%s' in tenant '%s'", version, agentId, tenantId);
            return publicKeyB64;
        } catch (NoSuchAlgorithmException e) {
            throw new AgentSigningException("Ed25519 not available in JVM", e);
        } catch (ISecretProvider.SecretProviderException e) {
            throw new AgentSigningException("Failed to store private key in vault", e);
        }
    }

    /**
     * Sign a {@link SignedEnvelope} using the agent's versioned key.
     *
     * @param tenantId
     *            the tenant identifier
     * @param agentId
     *            the agent identifier
     * @param envelope
     *            the unsigned envelope
     * @param keyVersion
     *            the key version to use for signing
     * @return the signed envelope
     * @throws AgentSigningException
     *             if signing fails
     */
    public SignedEnvelope signEnvelope(
                                       String tenantId, String agentId,
                                       SignedEnvelope envelope,
                                       int keyVersion)
            throws AgentSigningException {
        try {
            String canonicalForm = envelope.canonicalForm();
            PrivateKey privateKey = loadPrivateKey(tenantId, agentId, keyVersion);

            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initSign(privateKey);
            sig.update(canonicalForm.getBytes(StandardCharsets.UTF_8));
            String signatureB64 = Base64.getEncoder().encodeToString(sig.sign());

            signCounter.increment();
            return envelope.withSignature(signatureB64, keyVersion);
        } catch (PrivateKeyLoadException e) {
            // Unwrapped the same way sign() does — the two used to diverge, so the
            // envelope path reported "InvalidKeySpecException" where the plain path said
            // "No signing key found" for the identical failure.
            Throwable cause = e.getCause();
            if (cause instanceof ISecretProvider.SecretNotFoundException) {
                throw new AgentSigningException("No signing key found for agent " + agentId, cause);
            }
            throw new AgentSigningException("Envelope signing failed for agent " + agentId
                    + ": " + cause.getClass().getSimpleName(), cause);
        } catch (Exception e) {
            throw new AgentSigningException("Envelope signing failed for agent " + agentId, e);
        }
    }

    /**
     * Verify a signed envelope against a public key.
     *
     * @param envelope
     *            the signed envelope to verify
     * @param publicKeyB64
     *            the Base64-encoded public key
     * @return true if the signature is valid
     */
    public boolean verifyEnvelope(SignedEnvelope envelope, String publicKeyB64) {
        try {
            String canonicalForm = envelope.canonicalForm();
            return verify(publicKeyB64, canonicalForm, envelope.signature());
        } catch (Exception e) {
            LOGGER.warnf("Envelope verification failed: %s", e.getMessage());
            verifyFailCounter.increment();
            return false;
        }
    }

    /**
     * Rotate the signing key for an agent. Creates a new versioned key and returns
     * the public key for it.
     *
     * @param tenantId
     *            the tenant identifier
     * @param agentId
     *            the agent identifier
     * @param newVersion
     *            the new key version number
     * @return the Base64-encoded new public key
     * @throws AgentSigningException
     *             if rotation fails
     */
    public String rotateKey(String tenantId, String agentId, int newVersion) throws AgentSigningException {
        if (newVersion <= 0) {
            throw new AgentSigningException("Key version must be positive, got: " + newVersion, null);
        }
        String publicKeyB64 = generateKeyPairVersioned(tenantId, agentId, newVersion);
        LOGGER.infof("Rotated signing key for agent '%s' to version %d", agentId, newVersion);
        return publicKeyB64;
    }

    public static class AgentSigningException extends Exception {
        public AgentSigningException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Typed unchecked exception for the cache loader lambda. Preserves the original
     * cause type (SecretNotFoundException, InvalidKeySpecException,
     * IllegalArgumentException from bad Base64, etc.) so the unwrapping logic in
     * {@link #sign} can produce precise diagnostic messages.
     */
    private static class PrivateKeyLoadException extends RuntimeException {
        PrivateKeyLoadException(String agentId, Throwable cause) {
            super("Failed to load private key for agent " + agentId, cause);
        }
    }
}
