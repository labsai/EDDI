/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;
import ai.labs.eddi.configs.agents.crypto.SignedEnvelope;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.model.SecretReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

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
 * <li>Private key in {@link ISecretProvider} under
 * {@code agent-signing-key:{agentId}}, or
 * {@code agent-signing-key:{agentId}:v{n}} for a rotated key (Base64 PKCS#8,
 * never in config JSON); this service only reads it. Nothing in EDDI writes one
 * — see the "Cryptographic Agent Identity" section of
 * {@code docs/architecture.md}</li>
 * <li>Public key in {@code AgentConfiguration.identity.publicKey}, or
 * {@code identity.keys[]} for rotated keys</li>
 * <li>On permanent agent deletion:
 * {@link #deleteKeyPair(String, String, Collection)} removes the vault
 * entries</li>
 * </ol>
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class AgentSigningService {
    private static final Logger LOGGER = Logger.getLogger(AgentSigningService.class);
    private static final String ALGORITHM = "Ed25519";
    /**
     * Reserved key-name prefix, defined once in {@link SecretReference} so that the
     * reader here and the resolution-time refusal in {@code SecretResolver} cannot
     * drift apart.
     */
    private static final String VAULT_KEY_PREFIX = SecretReference.AGENT_SIGNING_KEY_PREFIX;
    /**
     * Maximum key version to scan during deletion cleanup. Package-private so the
     * test can pin the number of vault round trips one agent deletion costs.
     */
    static final int MAX_KEY_VERSION_SCAN = 100;

    private final ISecretProvider secretProvider;
    private final MeterRegistry meterRegistry;

    /**
     * How long the vault's answer about an agent's signing key is believed before
     * the vault is asked again — the key it returned, and equally "this agent has
     * no key".
     *
     * <p>
     * The miss is the frequent case: most agents have no key, and the audit ledger
     * signs every entry it writes — three per turn — so without remembering it each
     * entry of a keyless agent cost a vault lookup, i.e. a database read, only to
     * learn the same thing again. A key that was found expires on the same clock:
     * it used to be kept for the life of the process, so a key replaced or removed
     * in the vault by anything but this node's own {@link #deleteKeyPair} was never
     * picked up, and this node went on signing with the old one — every signature
     * then failing verification against the new public key. A key added, replaced
     * or removed in the vault is now picked up within the TTL; this node's own
     * delete takes effect at once.
     * </p>
     */
    static final long KEY_CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(60);

    /** Bounds {@link #keyCache}; past it Caffeine evicts. */
    static final int MAX_CACHED_KEYS = 10_000;

    /** Package-private so tests can move time instead of sleeping. */
    LongSupplier nanoClock = System::nanoTime;

    /**
     * Cache key → the agent's private key, or {@link Optional#empty()} for a vault
     * answer of "not found". A vault failure is never remembered. The ticker reads
     * {@link #nanoClock} on every call, so a test that replaces the clock after
     * construction moves this cache's time too.
     */
    private final Cache<String, Optional<PrivateKey>> keyCache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHED_KEYS)
            .expireAfterWrite(KEY_CACHE_TTL_NANOS, TimeUnit.NANOSECONDS)
            .ticker(() -> nanoClock.getAsLong())
            .build();

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
     * As {@link #sign}, for callers that sign when they can and carry on unsigned
     * when the agent has no key: the audit ledger, which signs every entry on the
     * turn's thread. While a "no key" answer is remembered (see
     * {@link #KEY_CACHE_TTL_NANOS}) this returns {@code null} without throwing,
     * where {@link #sign} would still build three exceptions per entry.
     *
     * @return Base64-encoded signature, or {@code null} if the agent has no key
     * @throws AgentSigningException
     *             if a key exists but signing failed, or the vault could not answer
     */
    public String signIfKeyPresent(String tenantId, String agentId, String payload) throws AgentSigningException {
        Optional<PrivateKey> cached = keyCache.getIfPresent(cacheKey(tenantId, agentId));
        if (cached != null && cached.isEmpty()) {
            return null;
        }
        try {
            return sign(tenantId, agentId, payload);
        } catch (AgentSigningException e) {
            if (e.getCause() instanceof ISecretProvider.SecretNotFoundException) {
                return null;
            }
            throw e;
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
     * With no list of versions to work from this sweeps the whole range rather than
     * stopping at the first gap: rotated keys are written to the vault under
     * whatever version number their provisioner chose, so a rotation that skipped a
     * number (v1, v3) used to leave every key after the gap in the vault forever.
     * Callers that have read the agent's configuration should pass its declared
     * versions instead — see {@link #deleteKeyPair(String, String, Collection)}.
     * And the legacy key is deleted in its own guard, because it not existing is
     * the ordinary state of a rotated agent — letting that
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
        deleteKeyPair(tenantId, agentId, null);
    }

    /**
     * As {@link #deleteKeyPair(String, String)}, but told which rotated versions
     * the agent's configuration actually declares.
     *
     * <p>
     * The blind 1..{@value #MAX_KEY_VERSION_SCAN} sweep is the fallback, not the
     * normal path. An agent holding one or two keys paid 101 vault round trips per
     * delete — 101 {@code deleteSecret} calls and 101 increments of the vault
     * delete metric — and on a vault-less dev instance every one of them failed
     * with the same "may still hold private key material" WARN, for versions that
     * never existed. The agent config lists its versions
     * ({@code identity.keys[].version}) and the caller has already read it to
     * decide whether there is anything to clean up at all.
     * </p>
     *
     * <p>
     * "Told which versions" bounds the sweep; it does not narrow it to exactly that
     * list. Everything up to the highest declared version is attempted, because a
     * key can exist in the vault without being listed — see
     * {@link #versionsToSweep(Collection)}.
     * </p>
     *
     * @param knownVersions
     *            the rotated key versions the configuration declares, used as the
     *            upper bound of the sweep, or {@code null} when they are unknown
     *            and the whole 1..{@value #MAX_KEY_VERSION_SCAN} range has to be
     *            swept. A list holding no positive version is treated as
     *            {@code null}: it bounds nothing — see
     *            {@link #versionsToSweep(Collection)}
     */
    public void deleteKeyPair(String tenantId, String agentId, Collection<Integer> knownVersions) {
        DeleteTally tally = new DeleteTally();

        deleteVaultKey(tenantId, vaultKeyName(agentId), cacheKey(tenantId, agentId), tally);
        for (int v : versionsToSweep(knownVersions)) {
            deleteVaultKey(tenantId, vaultKeyNameVersioned(agentId, v), cacheKey(tenantId, agentId) + ";v=" + v, tally);
        }

        // Reports what actually happened. The old unconditional "Deleted signing
        // keys … (cache evicted)" claimed success after a vault outage had left
        // every key behind — the leak wearing a success message this method's own
        // Javadoc warns about — and equally after deleting nothing at all.
        if (tally.failed > 0) {
            LOGGER.warnf("Signing key cleanup for agent '%s' in tenant '%s': %d key(s) deleted, %d could NOT be deleted and may "
                    + "still hold private key material (first failure: %s)", sanitize(agentId), sanitize(tenantId),
                    tally.deleted, tally.failed, sanitize(tally.firstFailure));
        } else if (tally.deleted > 0) {
            LOGGER.infof("Deleted %d signing key(s) for agent '%s' in tenant '%s' (cache evicted)", tally.deleted,
                    sanitize(agentId), sanitize(tenantId));
        } else {
            LOGGER.debugf("No signing keys found in the vault for agent '%s' in tenant '%s' (cache evicted)",
                    sanitize(agentId), sanitize(tenantId));
        }
    }

    /**
     * The versioned keys to attempt: the blind 1..{@value #MAX_KEY_VERSION_SCAN}
     * range when the configuration is unknown, and otherwise everything up to the
     * highest version it declares. Positive versions only — signing treats a
     * version {@code <= 0} as the unversioned key, so a stored zero or negative
     * names no versioned vault entry.
     *
     * <p>
     * Deliberately NOT just the declared list. The vault entry and the
     * {@code identity.keys} entry are two separate writes — the private key into
     * the vault, the version into the agent's configuration — and either can land
     * without the other; {@code keys[]} is also operator-editable JSON that nothing
     * prunes. Sweeping only what is listed therefore left an Ed25519 private key in
     * the vault forever after a permanent delete, while the tally reported "Deleted
     * N signing key(s)": the same leak wearing a success message that this class's
     * Javadoc warns about, on a narrower path. Covering gaps and skipped numbers
     * costs a handful of extra round trips for a normal agent — not the 101 the
     * blind sweep cost, which is what the declared-versions path exists to avoid.
     * </p>
     *
     * <p>
     * The range is capped at {@value #MAX_KEY_VERSION_SCAN} so a stored version of,
     * say, 50 000 cannot turn one delete into 50 000 vault calls. Declared versions
     * ABOVE the cap are still attempted individually — they are known to exist,
     * where the range is only a guess.
     * </p>
     *
     * <p>
     * An empty list of valid versions is an UNKNOWN bound, not a known-empty one,
     * and falls back to the full sweep exactly as {@code null} does. It is the same
     * argument as the gaps above, at its extreme: the vault entry and
     * {@code identity.keys} are written separately, so a first rotation whose
     * config update never landed leaves v1 in the vault with {@code keys[]} still
     * empty — and treating that as "no rotated versions, nothing to sweep" left an
     * Ed25519 private key behind after a permanent delete while the tally reported
     * success. An operator who pruned {@code keys[]} by hand produces the same
     * shape. The 101 round trips are the price of not leaking a private key on a
     * rare, irreversible operation.
     * </p>
     */
    private static List<Integer> versionsToSweep(Collection<Integer> knownVersions) {
        if (knownVersions == null) {
            return IntStream.rangeClosed(1, MAX_KEY_VERSION_SCAN).boxed().toList();
        }
        List<Integer> declared = knownVersions.stream().filter(v -> v != null && v > 0).distinct().sorted().toList();
        if (declared.isEmpty()) {
            // Nothing usable to bound the sweep with — see above.
            return IntStream.rangeClosed(1, MAX_KEY_VERSION_SCAN).boxed().toList();
        }
        int highest = Math.min(declared.get(declared.size() - 1), MAX_KEY_VERSION_SCAN);
        Set<Integer> versions = new TreeSet<>(declared);
        IntStream.rangeClosed(1, highest).forEach(versions::add);
        return List.copyOf(versions);
    }

    /** Running count of one {@link #deleteKeyPair} call's vault deletions. */
    private static final class DeleteTally {
        private int deleted;
        private int failed;
        private String firstFailure;
    }

    /**
     * Removes one vault entry and its cache line, treating "no such key" as the
     * expected case and anything else as a leak worth counting.
     *
     * <p>
     * The cache line is dropped in a {@code finally}: a vault delete that fails
     * leaves the private key in the vault, but leaving the loaded key in
     * {@link #keyCache} as well would let this process keep SIGNING as the deleted
     * agent from memory until the entry expires — a strictly worse outcome than the
     * vault entry the WARN is about.
     * </p>
     */
    private void deleteVaultKey(String tenantId, String vaultKey, String cacheKeyStr, DeleteTally tally) {
        try {
            secretProvider.delete(new SecretReference(tenantId, vaultKey));
            tally.deleted++;
        } catch (ISecretProvider.SecretNotFoundException e) {
            // Expected for every version this agent never had.
        } catch (Exception e) {
            tally.failed++;
            if (tally.firstFailure == null) {
                tally.firstFailure = vaultKey + ": " + e.getMessage();
            }
        } finally {
            keyCache.invalidate(cacheKeyStr);
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
     * The vault round-trip happens OUTSIDE a computing load ({@code Cache.get(key,
     * loader)}): that holds a map bin lock across network I/O, blocking unrelated
     * agents that hash to the same bin. A racing loser simply loads the same key
     * twice, which is harmless. A load racing this node's {@link #deleteKeyPair}
     * can put back the key the delete has just dropped; it then lives at most
     * {@link #KEY_CACHE_TTL_NANOS}, as it does on every other node.
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

        Optional<PrivateKey> cached = keyCache.getIfPresent(cacheKeyStr);
        if (cached != null) {
            return cached.orElseThrow(() -> new PrivateKeyLoadException(agentId,
                    new ISecretProvider.SecretNotFoundException("No signing key for agent " + agentId + " (checked recently)")));
        }

        String vaultKey = keyVersion > 0 ? vaultKeyNameVersioned(agentId, keyVersion) : vaultKeyName(agentId);
        PrivateKey loaded;
        try {
            SecretReference ref = new SecretReference(tenantId, vaultKey);
            String privateKeyB64 = secretProvider.resolve(ref);
            byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyB64);
            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
            loaded = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
        } catch (ISecretProvider.SecretNotFoundException e) {
            keyCache.put(cacheKeyStr, Optional.empty());
            throw new PrivateKeyLoadException(agentId, e);
        } catch (Exception e) {
            throw new PrivateKeyLoadException(agentId, e);
        }

        keyCache.put(cacheKeyStr, Optional.of(loaded));
        return loaded;
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
