/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

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
import java.util.Base64;
import java.util.List;

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

    private final ISecretProvider secretProvider;
    private final MeterRegistry meterRegistry;
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

            LOGGER.infof("Generated Ed25519 keypair for agent '%s' in tenant '%s'", agentId, tenantId);
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
            SecretReference ref = new SecretReference(tenantId, vaultKeyName(agentId));
            String privateKeyB64 = secretProvider.resolve(ref);

            byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyB64);
            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
            PrivateKey privateKey = keyFactory.generatePrivate(
                    new java.security.spec.PKCS8EncodedKeySpec(privateKeyBytes));

            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initSign(privateKey);
            sig.update(payload.getBytes(StandardCharsets.UTF_8));
            byte[] signatureBytes = sig.sign();

            signCounter.increment();
            return Base64.getEncoder().encodeToString(signatureBytes);
        } catch (ISecretProvider.SecretNotFoundException e) {
            throw new AgentSigningException("No signing key found for agent " + agentId, e);
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
            PublicKey publicKey = keyFactory.generatePublic(
                    new java.security.spec.X509EncodedKeySpec(publicKeyBytes));

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
     * Delete the signing keypair for an agent (cleanup on agent deletion).
     */
    public void deleteKeyPair(String tenantId, String agentId) {
        try {
            SecretReference ref = new SecretReference(tenantId, vaultKeyName(agentId));
            secretProvider.delete(ref);
            LOGGER.infof("Deleted signing key for agent '%s' in tenant '%s'", agentId, tenantId);
        } catch (Exception e) {
            LOGGER.warnf("Failed to delete signing key for agent '%s': %s", agentId, e.getMessage());
        }
    }

    private String vaultKeyName(String agentId) {
        return VAULT_KEY_PREFIX + agentId;
    }

    public static class AgentSigningException extends Exception {
        public AgentSigningException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
