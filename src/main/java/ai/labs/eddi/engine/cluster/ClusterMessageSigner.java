/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.impl.Headers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Signs and verifies the messages of EDDI's cluster control plane — node RPCs,
 * their replies and cluster events — with an HMAC-SHA256 key that every node
 * holds and NATS does not ({@link ClusterSigningConfig}).
 * <p>
 * <b>Threat model.</b> Whoever holds the shared NATS credentials (every pod
 * does), or can read and write the NATS traffic, could until now publish an
 * {@code admin-drain} or {@code gdpr-stop} RPC, an undeploy event or a
 * cache-resync storm, and every node obeyed. With a key configured a message is
 * accepted only with a valid signature made with the key, so NATS access alone
 * no longer controls the nodes. What is signed is the subject, a timestamp, a
 * random nonce and the SHA-256 of the body: a signature cannot be moved to
 * another subject or body; a captured message replayed later is refused once
 * its timestamp is older than the window, and within the window by its nonce
 * (remembered per node for the window); a reply is bound to the nonce of the
 * request it answers. Not covered: confidentiality of the payload (use NATS
 * TLS), denial of service by flooding NATS, an attacker who holds the signing
 * key (rotate it with {@code previous-keys}), and the NATS state that is not a
 * message (KV buckets, streams — see the per-node NATS permissions in the Helm
 * chart). The comparison is constant time ({@link MessageDigest#isEqual}).
 * <p>
 * <b>Rolling upgrade.</b> Without a key messages go out unsigned and are
 * accepted unsigned. With a key they are signed, and an unsigned message — from
 * a node of the previous version — is still accepted (counted and logged once)
 * unless {@code eddi.cluster.signing.required=true}. A message carrying an
 * invalid signature is always refused. The order is in
 * {@code docs/clustering.md}.
 * <p>
 * Also derives, from the same key, a key that seals user-id hashes travelling
 * on NATS (B5: an unsalted SHA-256 of an e-mail address is reversible by
 * dictionary): {@link #seal}/{@link #unseal} (AES-GCM).
 */
@ApplicationScoped
public class ClusterMessageSigner {

    private static final Logger LOGGER = Logger.getLogger(ClusterMessageSigner.class);

    /**
     * The header carrying the signature:
     * {@code v1;k=<kid>;t=<millis>;n=<nonce>;s=<sig>}.
     */
    public static final String HEADER_SIGNATURE = "Eddi-Sig";
    /** The header carrying the payload format version (C15). */
    public static final String HEADER_VERSION = "Eddi-V";

    private static final String SCHEME = "v1";
    private static final String DOMAIN = "eddi-cluster-msg-v1";
    private static final int NONCE_BYTES = 16;
    /**
     * A sealed value is a user-id hash; anything larger is a caller bug, not data
     * to seal.
     */
    private static final int MAX_SEALED_VALUE_BYTES = 4096;

    /**
     * Why a message was refused; the tag of {@code eddi.cluster.signing.rejected}.
     */
    public enum Rejection {
        UNSIGNED, MALFORMED, UNKNOWN_KEY, BAD_SIGNATURE, EXPIRED, REPLAYED,
        /**
         * Valid and fresh, but this node already remembers
         * {@code eddi.cluster.signing.max-remembered-nonces} unexpired nonces and could
         * not remember this one, so it could not refuse a replay of it.
         */
        NONCE_CAPACITY
    }

    /**
     * The {@code kind} tags {@link #verify} is called with. Their rejection
     * counters are registered at zero up front: a series born on its first refusal
     * starts at 1, and Prometheus {@code increase()} cannot see that first sample.
     */
    static final List<String> VERIFIED_KINDS = List.of("event", "rpc", "rpc-reply");

    /** The outcome of a verification. */
    public record Verdict(boolean accepted, Rejection rejection, String nonce) {

        static Verdict accept(String nonce) {
            return new Verdict(true, null, nonce);
        }

        static Verdict reject(Rejection why) {
            return new Verdict(false, why, null);
        }
    }

    private final ClusterSigningConfig config;
    private final MeterRegistry meterRegistry;
    private final byte[] currentKey;
    private final String currentKid;
    private final Map<String, byte[]> verificationKeys = new LinkedHashMap<>();
    private final SecretKeySpec sealKey;
    /**
     * The seal keys {@link #unseal} tries: the current one, then one per previous
     * key, so a value sealed by a node still on the old key opens during a
     * rotation.
     */
    private final List<SecretKeySpec> unsealKeys = new ArrayList<>();
    private final SecureRandom random = new SecureRandom();
    private final Set<String> warnedUnsigned = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedRejections = ConcurrentHashMap.newKeySet();
    /** Nonce of every accepted message, until that message's own expiry. */
    private final Cache<String, Long> seenNonces;
    private final long maxRememberedNonces;
    private LongSupplier clock = System::currentTimeMillis;

    @Inject
    public ClusterMessageSigner(ClusterSigningConfig config, MeterRegistry meterRegistry) {
        this.config = config;
        this.meterRegistry = meterRegistry;
        Optional<String> key = config.key();
        if (key.isPresent() && key.get().length() < ClusterSigningConfig.MIN_KEY_LENGTH) {
            throw new IllegalStateException("eddi.cluster.signing.key is shorter than " + ClusterSigningConfig.MIN_KEY_LENGTH
                    + " characters — use a random token of at least 32 characters (e.g. openssl rand -base64 48)");
        }
        if (key.isEmpty() && config.required()) {
            throw new IllegalStateException("eddi.cluster.signing.required=true but eddi.cluster.signing.key is not set: this node "
                    + "could neither sign nor verify cluster messages. Set the same key on every node.");
        }
        this.currentKey = key.map(k -> k.getBytes(StandardCharsets.UTF_8)).orElse(null);
        this.currentKid = currentKey == null ? null : kid(currentKey);
        if (currentKey != null) {
            verificationKeys.put(currentKid, currentKey);
        }
        for (String previous : config.previousKeys()) {
            byte[] bytes = previous.getBytes(StandardCharsets.UTF_8);
            verificationKeys.putIfAbsent(kid(bytes), bytes);
        }
        this.sealKey = currentKey == null ? null : new SecretKeySpec(derive(currentKey, "eddi-cluster-seal-v1"), "AES");
        if (sealKey != null) {
            unsealKeys.add(sealKey);
            for (byte[] verification : verificationKeys.values()) {
                if (verification != currentKey) {
                    unsealKeys.add(new SecretKeySpec(derive(verification, "eddi-cluster-seal-v1"), "AES"));
                }
            }
        }
        this.maxRememberedNonces = config.maxRememberedNonces() > 0
                ? config.maxRememberedNonces()
                : ClusterSigningConfig.DEFAULT_MAX_REMEMBERED_NONCES;
        // No maximumSize: a size bound EVICTS, and an evicted nonce whose message is
        // still inside its window can be replayed. Each nonce is kept exactly until
        // its own message expires (ts + window, on this signer's clock) and no
        // longer, and a full cache refuses new messages instead (see verify).
        this.seenNonces = Caffeine.newBuilder()
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.getAsLong()))
                .expireAfter(new Expiry<String, Long>() {
                    @Override
                    public long expireAfterCreate(String nonce, Long expiresAt, long currentTime) {
                        return TimeUnit.MILLISECONDS.toNanos(Math.max(1, expiresAt - clock.getAsLong()));
                    }

                    @Override
                    public long expireAfterUpdate(String nonce, Long expiresAt, long currentTime, long currentDuration) {
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(String nonce, Long expiresAt, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                }).build();
        if (config.required() || !verificationKeys.isEmpty()) {
            // Only a node that can refuse anything gets the zero series.
            for (String kind : VERIFIED_KINDS) {
                for (Rejection why : Rejection.values()) {
                    rejectionCounter(kind, why);
                }
            }
        }
    }

    /**
     * A signer without a key: signs nothing, accepts everything. For tests and
     * in-JVM ITs.
     */
    public static ClusterMessageSigner unsigned() {
        return new ClusterMessageSigner(ClusterSigningConfig.of(null, List.of(), false, Duration.ofSeconds(60)),
                new SimpleMeterRegistry());
    }

    /** For tests: the clock signatures are stamped and checked with. */
    public ClusterMessageSigner withClock(LongSupplier clock) {
        this.clock = clock;
        return this;
    }

    /** Logs the mode once at boot (cluster mode only). */
    public void logMode() {
        if (currentKey == null) {
            LOGGER.warn("Cluster messages (node RPCs and events) are NOT signed: eddi.cluster.signing.key is not set. Anyone "
                    + "who can publish on NATS can drain nodes, stop work and undeploy agents. Set the same random key "
                    + "(>= 32 characters, kept outside NATS) on every node; see docs/clustering.md#signed-control-plane.");
        } else {
            LOGGER.infof("Cluster messages are signed (key id %s%s); unsigned messages are %s.", currentKid,
                    verificationKeys.size() > 1 ? ", plus " + (verificationKeys.size() - 1) + " previous key(s) for verification" : "",
                    config.required() ? "refused" : "accepted (eddi.cluster.signing.required=false)");
        }
    }

    public boolean isSigning() {
        return currentKey != null;
    }

    public boolean isRequired() {
        return config.required();
    }

    /** The freshness and replay window of an RPC. */
    public Duration maxAge() {
        return config.maxAge();
    }

    /**
     * Adds the version header and, with a key, the signature to {@code headers}.
     *
     * @param subject
     *            what the signature is bound to: the NATS subject of a request or
     *            event, or {@link #replySubject} of the request a reply answers
     * @return the headers, for chaining
     */
    public Headers sign(String subject, byte[] body, Headers headers, int payloadVersion) {
        Headers h = headers == null ? new Headers() : headers;
        h.put(HEADER_VERSION, Integer.toString(payloadVersion));
        if (currentKey == null) {
            return h;
        }
        long ts = clock.getAsLong();
        byte[] nonceBytes = new byte[NONCE_BYTES];
        random.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        String sig = Base64.getUrlEncoder().withoutPadding().encodeToString(mac(currentKey, signedText(subject, ts, nonce, body)));
        h.put(HEADER_SIGNATURE, SCHEME + ";k=" + currentKid + ";t=" + ts + ";n=" + nonce + ";s=" + sig);
        return h;
    }

    /**
     * What a reply's signature is bound to: the nonce of the request it answers.
     */
    public static String replySubject(String requestNonce) {
        return "reply:" + (requestNonce == null ? "" : requestNonce);
    }

    /** The nonce of a signed message, or null. */
    public static String nonceOf(Headers headers) {
        Map<String, String> fields = fields(headers);
        return fields == null ? null : fields.get("n");
    }

    /**
     * Verifies a received message.
     *
     * @param kind
     *            {@code rpc}, {@code rpc-reply} or {@code event} — the metric tag
     * @param window
     *            how old (or how far in the future) the signature may be; the nonce
     *            is remembered for that long
     * @param rememberNonce
     *            refuse a nonce seen before (requests and events); false for a
     *            reply, which is bound to its request's nonce instead
     */
    public Verdict verify(String kind, String subject, Headers headers, byte[] body, Duration window, boolean rememberNonce) {
        String header = headers == null ? null : headers.getFirst(HEADER_SIGNATURE);
        if (header == null) {
            if (config.required()) {
                return rejected(kind, Rejection.UNSIGNED);
            }
            if (currentKey != null && warnedUnsigned.add(kind)) {
                LOGGER.warnf("Accepted an unsigned cluster %s although this node signs: a node without "
                        + "eddi.cluster.signing.key (or of an older version) is in the cluster. Set "
                        + "eddi.cluster.signing.required=true once every node signs.", kind);
            }
            meterRegistry.counter("eddi.cluster.signing.unsigned", "kind", kind).increment();
            return Verdict.accept(null);
        }
        if (verificationKeys.isEmpty()) {
            // This node has no key (yet): it cannot check, and must not refuse, the
            // messages of nodes that already sign — the second step of the upgrade.
            return Verdict.accept(null);
        }
        Map<String, String> fields = fields(headers);
        if (fields == null || !SCHEME.equals(fields.get("scheme")) || fields.get("k") == null || fields.get("n") == null
                || fields.get("s") == null || fields.get("t") == null) {
            return rejected(kind, Rejection.MALFORMED);
        }
        byte[] key = verificationKeys.get(fields.get("k"));
        if (key == null) {
            return rejected(kind, Rejection.UNKNOWN_KEY);
        }
        long ts;
        byte[] provided;
        try {
            ts = Long.parseLong(fields.get("t"));
            provided = Base64.getUrlDecoder().decode(fields.get("s"));
        } catch (IllegalArgumentException e) {
            return rejected(kind, Rejection.MALFORMED);
        }
        byte[] expected = mac(key, signedText(subject, ts, fields.get("n"), body));
        if (!MessageDigest.isEqual(expected, provided)) {
            return rejected(kind, Rejection.BAD_SIGNATURE);
        }
        long now = clock.getAsLong();
        if (Math.abs(now - ts) > window.toMillis()) {
            return rejected(kind, Rejection.EXPIRED);
        }
        if (rememberNonce) {
            String nonceKey = kind + ":" + fields.get("n");
            if (seenNonces.getIfPresent(nonceKey) != null) {
                return rejected(kind, Rejection.REPLAYED);
            }
            if (!hasRoomForNonce()) {
                // Fail closed: accepting a message whose nonce cannot be remembered
                // is accepting its replay too.
                return rejected(kind, Rejection.NONCE_CAPACITY);
            }
            // Kept through the last millisecond the freshness check above still passes.
            if (seenNonces.asMap().putIfAbsent(nonceKey, ts + window.toMillis() + 1) != null) {
                return rejected(kind, Rejection.REPLAYED);
            }
        }
        return Verdict.accept(fields.get("n"));
    }

    /**
     * Whether another nonce may be remembered. Expired nonces are swept first, so
     * only nonces whose messages could still be replayed count. Checked before the
     * insert, so concurrent verifications may overshoot the bound by at most the
     * number of verifying threads.
     */
    private boolean hasRoomForNonce() {
        if (seenNonces.estimatedSize() < maxRememberedNonces) {
            return true;
        }
        seenNonces.cleanUp();
        return seenNonces.estimatedSize() < maxRememberedNonces;
    }

    private Counter rejectionCounter(String kind, Rejection why) {
        return meterRegistry.counter("eddi.cluster.signing.rejected", "kind", kind, "reason", why.name().toLowerCase());
    }

    private Verdict rejected(String kind, Rejection why) {
        rejectionCounter(kind, why).increment();
        if (warnedRejections.add(kind + "/" + why)) {
            LOGGER.warnf("Refused a cluster %s: %s (logged once per reason; see eddi.cluster.signing.rejected)", kind,
                    why.name().toLowerCase().replace('_', ' '));
        } else {
            LOGGER.debugf("Refused a cluster %s: %s", kind, why);
        }
        return Verdict.reject(why);
    }

    /**
     * The payload version a message declares; 1 when it declares none (an older
     * node).
     */
    public static int versionOf(Headers headers) {
        String v = headers == null ? null : headers.getFirst(HEADER_VERSION);
        if (v == null) {
            return 1;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    // ------------------------------------------------------------- user ids (B5)

    /**
     * Seals a value (a user-id hash) for transport, or returns null without a key —
     * the caller then sends the plain hash, as before.
     */
    public String seal(String value) {
        if (sealKey == null || value == null) {
            return null;
        }
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, sealKey, new GCMParameterSpec(128, iv));
            byte[] plain = value.getBytes(StandardCharsets.UTF_8);
            if (plain.length > MAX_SEALED_VALUE_BYTES) {
                throw new IllegalArgumentException("A cluster value to seal is larger than " + MAX_SEALED_VALUE_BYTES + " bytes");
            }
            byte[] sealed = cipher.doFinal(plain);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(ByteBuffer.allocate(Math.addExact(iv.length, sealed.length)).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not seal a cluster payload", e);
        }
    }

    /**
     * Opens a value sealed by {@link #seal} on any node with the same key, or with
     * a key this node lists in {@code previous-keys} (a node not yet rotated);
     * empty if it cannot. A node still on the old key cannot open what a rotated
     * node sealed with the new one — see the rotation steps in clustering.md.
     */
    public Optional<String> unseal(String sealed) {
        if (sealKey == null || sealed == null) {
            return Optional.empty();
        }
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(sealed);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (raw.length < 13) {
            return Optional.empty();
        }
        for (SecretKeySpec key : unsealKeys) {
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, raw, 0, 12));
                return Optional.of(new String(cipher.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8));
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                // not sealed with this key: try the next
            }
        }
        return Optional.empty();
    }

    /**
     * A keyed hash of {@code value} ({@code k_} + base64url HMAC), or null without
     * a key. For identifiers that only have to be matched, never recovered.
     */
    public String keyedHash(String value) {
        if (currentKey == null || value == null) {
            return null;
        }
        return "k_" + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(derive(currentKey, "eddi-cluster-keyhash-v1"),
                value.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] signedText(String subject, long ts, String nonce, byte[] body) {
        String text = DOMAIN + "\n" + subject + "\n" + ts + "\n" + nonce + "\n"
                + Base64.getEncoder().encodeToString(sha256(body == null ? new byte[0] : body));
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> fields(Headers headers) {
        String header = headers == null ? null : headers.getFirst(HEADER_SIGNATURE);
        if (header == null) {
            return null;
        }
        String[] parts = header.split(";");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("scheme", parts[0].trim());
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) {
                return null;
            }
            fields.put(parts[i].substring(0, eq).trim(), parts[i].substring(eq + 1).trim());
        }
        return fields;
    }

    private static String kid(byte[] key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(key)).substring(0, 8);
    }

    private static byte[] derive(byte[] key, String purpose) {
        return mac(key, purpose.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] mac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** For tests: the verification key ids. */
    List<String> keyIds() {
        return new ArrayList<>(verificationKeys.keySet());
    }
}
