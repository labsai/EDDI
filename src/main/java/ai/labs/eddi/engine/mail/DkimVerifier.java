/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import ai.labs.eddi.engine.mail.DkimResult.Outcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * RFC 6376 DKIM verification on the raw RFC 5322 bytes of a message (email plan
 * §8).
 * <p>
 * What is accepted:
 * <ul>
 * <li>{@code rsa-sha256} (keys of 1024 to 8192 bits) and {@code ed25519-sha256}
 * (RFC 8463, the JDK's own EdDSA). {@code rsa-sha1} is refused as a failure
 * (RFC 8301), as is any other algorithm.</li>
 * <li>{@code simple} and {@code relaxed} canonicalisation of header and body,
 * in any combination.</li>
 * </ul>
 * What is refused, as a {@link Outcome#FAIL} of that signature:
 * <ul>
 * <li>{@code l=}. A body-length limit lets anyone append unsigned text to a
 * signed message and keep the signature valid; EDDI would then read the
 * appended text as the sender's.</li>
 * <li>A signature that does not cover {@code From} (RFC 6376 §5.4).</li>
 * <li>{@code i=} outside {@code d=}, or not equal to it under a {@code t=s}
 * key; an expired {@code x=}; a {@code t=} in the future or after
 * {@code x=}.</li>
 * <li>A revoked key (empty {@code p=}), no key, more than one key record, a key
 * whose {@code k=}, {@code h=} or {@code s=} does not admit the signature, an
 * RSA key under 1024 bits.</li>
 * </ul>
 * Header selection follows RFC 6376 §5.4.2: each name in {@code h=} takes the
 * next instance of that header <em>from the bottom</em>, and a name listed more
 * often than the header occurs contributes nothing — which is what makes
 * over-signing ({@code h=from:from}) bind the <em>absence</em> of a second
 * {@code From}: one added on top changes which instance is hashed, and the
 * signature breaks. That is also why a duplicated header which is signed only
 * once is dangerous ({@code From} added above a signed one is shown by many
 * clients but not hashed), and why {@link SenderAuthenticator} counts the
 * {@code From} fields itself rather than trusting the signature.
 * <p>
 * Line endings: canonicalisation is defined on CRLF. Bytes that use bare LF (a
 * message stored on a Unix file system) are converted to CRLF first, as a
 * signing MTA saw them on the wire; CRLF input is used as is.
 * <p>
 * Bounded: at most {@value #MAX_SIGNATURES} signatures are evaluated (from the
 * top, the most recently added first), a header block of at most
 * {@link MailLimits#DEFAULT_MAX_HEADER_BYTES} bytes,
 * {@value #MAX_SIGNED_HEADERS} names per {@code h=}, one body hash per
 * canonicalisation however many signatures there are, one DNS lookup per
 * signature against the message's {@link MailDnsResolver.Budget}. Never throws
 * on hostile input.
 * <p>
 * Stateless and thread-safe.
 */
@ApplicationScoped
public class DkimVerifier {

    /** Signatures evaluated per message; the rest are counted, not checked. */
    public static final int MAX_SIGNATURES = 5;

    /** Names in one {@code h=} tag. */
    public static final int MAX_SIGNED_HEADERS = 100;

    /** Characters of one DKIM-Signature field. */
    static final int MAX_SIGNATURE_FIELD_LENGTH = 8192;

    /** Raw bytes accepted at all. */
    static final long MAX_RAW_BYTES = 64L * 1024 * 1024;

    static final int MIN_RSA_BITS = 1024;
    static final int MAX_RSA_BITS = 8192;

    /** A public exponent this long makes verification cost what signing should. */
    private static final int MAX_RSA_EXPONENT_BITS = 64;

    /** Tolerated clock difference for {@code t=} and {@code x=}. */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private static final int MAX_TIMESTAMP_DIGITS = 12;

    private static final byte[] ED25519_SPKI_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
    private static final byte[] RSA_ALGORITHM_IDENTIFIER = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7,
            0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};

    /**
     * The signatures of one message.
     *
     * @param results
     *            one per evaluated signature, top to bottom
     * @param signaturesFound
     *            DKIM-Signature fields in the message, including those over
     *            {@link #MAX_SIGNATURES} that were not evaluated
     * @param fromFieldCount
     *            how many {@code From} fields the header block has
     * @param messageFailure
     *            why the message could not be read at all (then {@code results} is
     *            empty), or null
     */
    public record Verification(List<DkimResult> results, int signaturesFound, int fromFieldCount, String messageFailure) {
        public Verification {
            results = results == null ? List.of() : List.copyOf(results);
        }

        /** No DKIM-Signature at all (and the message itself was readable). */
        public boolean unsigned() {
            return messageFailure == null && signaturesFound == 0;
        }
    }

    private enum Algorithm {
        RSA_SHA256("rsa"), ED25519_SHA256("ed25519");

        final String keyType;

        Algorithm(String keyType) {
            this.keyType = keyType;
        }
    }

    private enum Canon {
        SIMPLE, RELAXED
    }

    private final MailDnsResolver dns;
    private final Clock clock;

    @Inject
    public DkimVerifier(MailDnsResolver dns) {
        this(dns, Clock.systemUTC());
    }

    /** For tests: a fixed clock. */
    public DkimVerifier(MailDnsResolver dns, Clock clock) {
        this.dns = Objects.requireNonNull(dns, "dns");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Verifies every DKIM signature of a message, up to {@link #MAX_SIGNATURES}.
     *
     * @param raw
     *            the message as the transport delivered it
     * @param budget
     *            DNS lookups this message may still cause
     */
    public Verification verify(byte[] raw, MailDnsResolver.Budget budget) {
        return verify(raw, budget, null);
    }

    /**
     * Verifies every DKIM signature, judging {@code x=} (expiry) and {@code t=}
     * (not in the future) against a given instant instead of the clock.
     * <p>
     * The instant is taken as is: bounding it (never after now, never before a
     * maximum age) is the caller's job — {@link SenderAuthenticator} does it.
     *
     * @param raw
     *            the message as the transport delivered it
     * @param budget
     *            DNS lookups this message may still cause
     * @param evaluatedAt
     *            the instant the timestamps are judged at, or null for now
     */
    public Verification verify(byte[] raw, MailDnsResolver.Budget budget, Instant evaluatedAt) {
        long at = (evaluatedAt != null ? evaluatedAt : clock.instant()).getEpochSecond();
        if (raw == null || raw.length == 0) {
            return new Verification(List.of(), 0, 0, "the message is empty");
        }
        if (raw.length > MAX_RAW_BYTES) {
            return new Verification(List.of(), 0, 0, "the message is too large to verify");
        }
        byte[] bytes = toCrlf(raw);
        ParsedMessage message = ParsedMessage.parse(bytes, MailLimits.DEFAULT_MAX_HEADER_BYTES);
        if (message.failure != null) {
            return new Verification(List.of(), 0, 0, message.failure);
        }
        int fromCount = message.indexes.getOrDefault("from", List.of()).size();
        List<Integer> signatures = message.indexes.getOrDefault("dkim-signature", List.of());
        List<DkimResult> results = new ArrayList<>();
        Map<Canon, byte[]> bodyHashes = new EnumMap<>(Canon.class);
        for (int i = 0; i < signatures.size() && i < MAX_SIGNATURES; i++) {
            results.add(verifyOne(message, signatures.get(i), bodyHashes, budget, at));
        }
        return new Verification(results, signatures.size(), fromCount, null);
    }

    // ---------------------------------------------------------------------
    // One signature
    // ---------------------------------------------------------------------

    private DkimResult verifyOne(ParsedMessage message, int fieldIndex, Map<Canon, byte[]> bodyHashes, MailDnsResolver.Budget budget,
                                 long evaluatedAtSecond) {
        Field field = message.fields.get(fieldIndex);
        String value = field.value();
        if (value.length() > MAX_SIGNATURE_FIELD_LENGTH) {
            return new DkimResult(null, null, null, Outcome.FAIL, "the signature field is too long");
        }
        TagList tags = TagList.parse(value);
        if (tags == null) {
            return new DkimResult(null, null, null, Outcome.FAIL, "the signature's tag list is malformed");
        }
        String domain = validDomain(tags.value("d"));
        String selector = validSelector(tags.value("s"));
        String a = tags.value("a");
        String algorithmText = a == null ? null : a.length() > 40 ? a.substring(0, 40) : a;
        Sig sig = new Sig(domain, selector, algorithmText);

        if (!"1".equals(tags.value("v"))) {
            return sig.fail("v= is missing or not 1");
        }
        for (String required : List.of("a", "b", "bh", "d", "h", "s")) {
            if (tags.value(required) == null) {
                return sig.fail("a required tag is missing (" + required + "=)");
            }
        }
        Algorithm algorithm;
        String alg = a.toLowerCase(Locale.ROOT);
        if ("rsa-sha256".equals(alg)) {
            algorithm = Algorithm.RSA_SHA256;
        } else if ("ed25519-sha256".equals(alg)) {
            algorithm = Algorithm.ED25519_SHA256;
        } else if ("rsa-sha1".equals(alg)) {
            return sig.fail("rsa-sha1 is refused (RFC 8301)");
        } else {
            return sig.fail("unsupported algorithm");
        }
        if (domain == null) {
            return sig.fail("d= is not a domain");
        }
        if (selector == null) {
            return sig.fail("s= is not a selector");
        }
        if (tags.value("l") != null) {
            return sig.fail("l= (body length limit) is refused: it lets unsigned text be appended");
        }
        String q = tags.value("q");
        if (q != null && !listContains(q, "dns/txt")) {
            return sig.fail("q= does not allow dns/txt");
        }
        Canon[] canons = canonicalisation(tags.value("c"));
        if (canons == null) {
            return sig.fail("c= is not a known canonicalisation");
        }
        List<String> signedHeaders = new ArrayList<>();
        for (String name : stripFws(tags.value("h")).split(":", -1)) {
            if (name.isEmpty() || !isFieldName(name)) {
                return sig.fail("h= holds an invalid header name");
            }
            signedHeaders.add(name.toLowerCase(Locale.ROOT));
        }
        if (signedHeaders.size() > MAX_SIGNED_HEADERS) {
            return sig.fail("h= names too many headers");
        }
        if (!signedHeaders.contains("from")) {
            return sig.fail("the signature does not cover From");
        }
        String identityDomain = domain;
        String i = tags.value("i");
        if (i != null) {
            int at = i.lastIndexOf('@');
            if (at < 0) {
                return sig.fail("i= is not an identity");
            }
            identityDomain = validDomain(i.substring(at + 1));
            if (identityDomain == null || !(identityDomain.equals(domain) || identityDomain.endsWith("." + domain))) {
                return sig.fail("i= is not within d=");
            }
        }
        DkimResult timing = checkTimestamps(sig, tags.value("t"), tags.value("x"), evaluatedAtSecond);
        if (timing != null) {
            return timing;
        }
        byte[] signature = base64(tags.value("b"));
        if (signature == null || signature.length == 0 || signature.length > 1024) {
            return sig.fail("b= is not a signature");
        }
        byte[] claimedBodyHash = base64(tags.value("bh"));
        if (claimedBodyHash == null || claimedBodyHash.length != 32) {
            return sig.fail("bh= is not a SHA-256 hash");
        }

        // The body first: local, and a tampered message then spends no lookup.
        byte[] bodyHash = bodyHashes.computeIfAbsent(canons[1], c -> bodyHash(message, c));
        if (!MessageDigest.isEqual(bodyHash, claimedBodyHash)) {
            return sig.fail("the body hash does not match (the body was changed)");
        }

        MailDnsResolver.TxtLookup lookup = dns.lookupTxt(selector + "._domainkey." + domain, budget);
        switch (lookup.status()) {
            case FOUND -> {
            }
            case NOT_FOUND -> {
                return sig.fail("no key is published for the selector");
            }
            case TEMPFAIL -> {
                return sig.temperror("the key lookup failed temporarily");
            }
            case BUDGET_EXHAUSTED -> {
                return sig.temperror("the message's DNS lookup budget is spent");
            }
            case OVERSIZE -> {
                return sig.fail("the key record is too large");
            }
            default -> {
                return sig.fail("the key name is invalid");
            }
        }
        if (lookup.records().size() != 1) {
            return sig.fail("more than one key record is published for the selector");
        }
        KeyRecord key = KeyRecord.parse(lookup.records().get(0));
        if (key.failure != null) {
            return sig.fail(key.failure);
        }
        if (!key.keyType.equals(algorithm.keyType)) {
            return sig.fail("the key type does not match the algorithm");
        }
        if (!key.hashAllowed) {
            return sig.fail("the key does not allow sha256");
        }
        if (!key.serviceAllowed) {
            return sig.fail("the key is not for email");
        }
        if (key.strictIdentity && !identityDomain.equals(domain)) {
            return sig.fail("the key requires i= to equal d= (t=s)");
        }
        PublicKey publicKey;
        try {
            publicKey = algorithm == Algorithm.RSA_SHA256 ? rsaKey(key.publicKey) : ed25519Key(key.publicKey);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return sig.fail("the published key cannot be read");
        }
        if (publicKey instanceof RSAPublicKey rsa) {
            int bits = rsa.getModulus().bitLength();
            if (bits < MIN_RSA_BITS) {
                return sig.fail("the RSA key is shorter than 1024 bits");
            }
            if (bits > MAX_RSA_BITS || rsa.getPublicExponent().bitLength() > MAX_RSA_EXPONENT_BITS) {
                return sig.fail("the RSA key is outside the accepted size");
            }
        }

        byte[] signedData = headerHashInput(message, fieldIndex, signedHeaders, canons[0]);
        boolean verified;
        try {
            verified = verifySignature(algorithm, publicKey, signedData, signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            verified = false;
        }
        if (!verified) {
            return sig.fail("the signature does not verify (a signed header was changed)");
        }
        if (key.testing) {
            return new DkimResult(domain, selector, algorithmText, Outcome.TESTING, "the key is in testing mode (t=y)");
        }
        return new DkimResult(domain, selector, algorithmText, Outcome.PASS, "verified");
    }

    private record Sig(String domain, String selector, String algorithm) {
        DkimResult fail(String reason) {
            return new DkimResult(domain, selector, algorithm, Outcome.FAIL, reason);
        }

        DkimResult temperror(String reason) {
            return new DkimResult(domain, selector, algorithm, Outcome.TEMPERROR, reason);
        }
    }

    private static DkimResult checkTimestamps(Sig sig, String t, String x, long now) {
        Long signed = null;
        if (t != null) {
            signed = timestamp(t);
            if (signed == null) {
                return sig.fail("t= is not a timestamp");
            }
        }
        Long expires = null;
        if (x != null) {
            expires = timestamp(x);
            if (expires == null) {
                return sig.fail("x= is not a timestamp");
            }
        }
        long skew = CLOCK_SKEW.toSeconds();
        if (signed != null && signed > now + skew) {
            return sig.fail("t= is in the future");
        }
        if (expires != null && signed != null && expires < signed) {
            return sig.fail("x= is before t=");
        }
        if (expires != null && now > expires + skew) {
            return sig.fail("the signature has expired (x=)");
        }
        return null;
    }

    private static Long timestamp(String value) {
        if (value.isEmpty() || value.length() > MAX_TIMESTAMP_DIGITS) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        return Long.parseLong(value);
    }

    private static boolean verifySignature(Algorithm algorithm, PublicKey key, byte[] data, byte[] signature)
            throws GeneralSecurityException {
        if (algorithm == Algorithm.RSA_SHA256) {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(signature);
        }
        // RFC 8463 §3: the SHA-256 of the canonical headers is what Ed25519 signs.
        if (signature.length != 64) {
            return false;
        }
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(sha256().digest(data));
        return verifier.verify(signature);
    }

    private static PublicKey rsaKey(byte[] encoded) throws GeneralSecurityException {
        KeyFactory factory = KeyFactory.getInstance("RSA");
        try {
            return factory.generatePublic(new X509EncodedKeySpec(encoded));
        } catch (InvalidKeySpecException e) {
            // Some publish the bare PKCS#1 RSAPublicKey (RFC 6376 §3.6.1's wording);
            // wrap it in a SubjectPublicKeyInfo.
            ByteArrayOutputStream bitString = new ByteArrayOutputStream();
            bitString.write(0);
            bitString.writeBytes(encoded);
            byte[] content = concat(RSA_ALGORITHM_IDENTIFIER, der(0x03, bitString.toByteArray()));
            return factory.generatePublic(new X509EncodedKeySpec(der(0x30, content)));
        }
    }

    private static PublicKey ed25519Key(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 32) {
            throw new InvalidKeySpecException("An Ed25519 key is 32 bytes");
        }
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(concat(ED25519_SPKI_PREFIX, raw)));
    }

    private static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            byte[] lengthBytes = BigInteger.valueOf(length).toByteArray();
            int start = lengthBytes[0] == 0 ? 1 : 0;
            out.write(0x80 | (lengthBytes.length - start));
            out.write(lengthBytes, start, lengthBytes.length - start);
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ---------------------------------------------------------------------
    // Canonicalisation (RFC 6376 §3.4)
    // ---------------------------------------------------------------------

    /** {@code c=}: header/body, body defaulting to simple, both by default. */
    private static Canon[] canonicalisation(String c) {
        if (c == null) {
            return new Canon[]{Canon.SIMPLE, Canon.SIMPLE};
        }
        String[] parts = c.toLowerCase(Locale.ROOT).split("/", -1);
        if (parts.length > 2) {
            return null;
        }
        Canon header = canon(parts[0]);
        Canon body = parts.length == 2 ? canon(parts[1]) : Canon.SIMPLE;
        return header == null || body == null ? null : new Canon[]{header, body};
    }

    private static Canon canon(String name) {
        return switch (name) {
            case "simple" -> Canon.SIMPLE;
            case "relaxed" -> Canon.RELAXED;
            default -> null;
        };
    }

    /**
     * The bytes the header signature covers: each selected header, canonicalised,
     * then this signature's own field with the {@code b=} value removed and no
     * trailing CRLF (§3.7).
     */
    private static byte[] headerHashInput(ParsedMessage message, int signatureIndex, List<String> signedHeaders, Canon canon) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, Integer> used = new HashMap<>();
        for (String name : signedHeaders) {
            List<Integer> instances = message.indexes.getOrDefault(name, List.of());
            int next = used.getOrDefault(name, instances.size() - 1);
            // Bottom-up; never the signature being verified.
            while (next >= 0 && instances.get(next) == signatureIndex) {
                next--;
            }
            if (next < 0) {
                // Listed more often than present: contributes nothing (§5.4.2).
                used.put(name, -1);
                continue;
            }
            used.put(name, next - 1);
            Field field = message.fields.get(instances.get(next));
            out.writeBytes(canonicalHeader(field.name(), field.value(), canon).getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes(CRLF);
        }
        Field signature = message.fields.get(signatureIndex);
        String blanked = TagList.blankValue(signature.value(), "b");
        out.writeBytes(canonicalHeader(signature.name(), blanked, canon).getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    /** One header field, canonicalised, without its CRLF. */
    private static String canonicalHeader(String rawName, String rawValue, Canon canon) {
        if (canon == Canon.SIMPLE) {
            return rawName + ":" + rawValue;
        }
        String name = rawName.strip().toLowerCase(Locale.ROOT);
        StringBuilder value = new StringBuilder(rawValue.length());
        boolean pendingSpace = false;
        for (int i = 0; i < rawValue.length(); i++) {
            char c = rawValue.charAt(i);
            if (c == '\r' || c == '\n') {
                continue;
            }
            if (c == ' ' || c == '\t') {
                pendingSpace = true;
                continue;
            }
            if (pendingSpace && value.length() > 0) {
                value.append(' ');
            }
            pendingSpace = false;
            value.append(c);
        }
        return name + ":" + value;
    }

    private static final byte[] CRLF = {'\r', '\n'};

    private static byte[] bodyHash(ParsedMessage message, Canon canon) {
        MessageDigest digest = sha256();
        byte[] b = message.bytes;
        int start = message.bodyStart;
        int end = b.length;
        if (canon == Canon.SIMPLE) {
            // Strip every trailing CRLF, then end with exactly one (an empty body is
            // one CRLF).
            int e = end;
            while (e - start >= 2 && b[e - 2] == '\r' && b[e - 1] == '\n') {
                e -= 2;
            }
            digest.update(b, start, e - start);
            digest.update(CRLF);
            return digest.digest();
        }
        // Relaxed: WSP runs to one SP, no WSP at line end, no empty lines at the end;
        // an empty body is empty.
        DigestSink sink = new DigestSink(digest);
        int pendingEmptyLines = 0;
        int lineStart = start;
        while (lineStart < end) {
            int lineEnd = lineStart;
            while (lineEnd < end && !(b[lineEnd] == '\r' && lineEnd + 1 < end && b[lineEnd + 1] == '\n')) {
                lineEnd++;
            }
            // Trailing whitespace is ignored.
            int contentEnd = lineEnd;
            while (contentEnd > lineStart && (b[contentEnd - 1] == ' ' || b[contentEnd - 1] == '\t')) {
                contentEnd--;
            }
            if (contentEnd == lineStart) {
                pendingEmptyLines++;
            } else {
                for (; pendingEmptyLines > 0; pendingEmptyLines--) {
                    sink.write('\r');
                    sink.write('\n');
                }
                boolean inSpace = false;
                for (int k = lineStart; k < contentEnd; k++) {
                    byte c = b[k];
                    if (c == ' ' || c == '\t') {
                        inSpace = true;
                        continue;
                    }
                    if (inSpace) {
                        sink.write(' ');
                        inSpace = false;
                    }
                    sink.write(c);
                }
                sink.write('\r');
                sink.write('\n');
            }
            lineStart = lineEnd + 2;
        }
        sink.flush();
        return digest.digest();
    }

    /** Buffers single bytes into the digest. */
    private static final class DigestSink {
        private final MessageDigest digest;
        private final byte[] buffer = new byte[8192];
        private int size;

        DigestSink(MessageDigest digest) {
            this.digest = digest;
        }

        void write(int b) {
            if (size == buffer.length) {
                flush();
            }
            buffer[size++] = (byte) b;
        }

        void flush() {
            digest.update(buffer, 0, size);
            size = 0;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }

    // ---------------------------------------------------------------------
    // Raw message
    // ---------------------------------------------------------------------

    /** Bare LF becomes CRLF; input that has none is returned as is. */
    static byte[] toCrlf(byte[] raw) {
        int bare = 0;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '\n' && (i == 0 || raw[i - 1] != '\r')) {
                bare++;
            }
        }
        if (bare == 0) {
            return raw;
        }
        byte[] out = new byte[raw.length + bare];
        int o = 0;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '\n' && (i == 0 || raw[i - 1] != '\r')) {
                out[o++] = '\r';
            }
            out[o++] = raw[i];
        }
        return out;
    }

    /**
     * A header field: its name as written (up to the colon) and its value with
     * folding kept, without the final CRLF. Text is ISO-8859-1, one character per
     * byte, so canonicalisation hashes exactly the bytes that arrived, 8-bit
     * included.
     */
    record Field(String name, String value) {
    }

    /** The header fields of a message, where its body starts, or why neither. */
    static final class ParsedMessage {
        final byte[] bytes;
        final List<Field> fields = new ArrayList<>();
        /** Lower-case name to field positions, top to bottom. */
        final Map<String, List<Integer>> indexes = new LinkedHashMap<>();
        int bodyStart;
        String failure;

        private ParsedMessage(byte[] bytes) {
            this.bytes = bytes;
        }

        static ParsedMessage parse(byte[] bytes, int maxHeaderBytes) {
            ParsedMessage message = new ParsedMessage(bytes);
            int pos = 0;
            int n = bytes.length;
            StringBuilder current = null;
            while (true) {
                if (pos >= n) {
                    // No empty line: all header, no body.
                    message.bodyStart = n;
                    break;
                }
                if (pos > maxHeaderBytes) {
                    message.failure = "the header block is too large";
                    return message;
                }
                int lineEnd = pos;
                while (lineEnd < n && !(bytes[lineEnd] == '\r' && lineEnd + 1 < n && bytes[lineEnd + 1] == '\n')) {
                    lineEnd++;
                }
                if (lineEnd - pos > maxHeaderBytes) {
                    message.failure = "the header block is too large";
                    return message;
                }
                int next = Math.min(n, lineEnd + 2);
                if (lineEnd == pos) {
                    // The empty line ends the header block.
                    message.bodyStart = next;
                    break;
                }
                String line = new String(bytes, pos, lineEnd - pos, StandardCharsets.ISO_8859_1);
                char first = line.charAt(0);
                if (first == ' ' || first == '\t') {
                    if (current == null) {
                        message.failure = "the header block starts with a continuation line";
                        return message;
                    }
                    current.append("\r\n").append(line);
                } else {
                    if (current != null && !message.add(current.toString())) {
                        return message;
                    }
                    current = new StringBuilder(line);
                }
                pos = next;
            }
            if (current != null && !message.add(current.toString())) {
                return message;
            }
            return message;
        }

        private boolean add(String raw) {
            int colon = raw.indexOf(':');
            if (colon <= 0) {
                failure = "a header line is not a field";
                return false;
            }
            String name = raw.substring(0, colon);
            String bare = name.stripTrailing();
            if (bare.isEmpty() || !isFieldName(bare)) {
                failure = "a header field has an invalid name";
                return false;
            }
            fields.add(new Field(name, raw.substring(colon + 1)));
            indexes.computeIfAbsent(bare.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(fields.size() - 1);
            return true;
        }
    }

    /** RFC 5322 ftext: printable ASCII except the colon. */
    static boolean isFieldName(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 33 || c > 126 || c == ':') {
                return false;
            }
        }
        return !name.isEmpty();
    }

    // ---------------------------------------------------------------------
    // Tag lists (RFC 6376 §3.2)
    // ---------------------------------------------------------------------

    /** A parsed tag list: name to value, whitespace at both ends removed. */
    static final class TagList {
        private final Map<String, String> values;
        private final List<String> order;

        private TagList(Map<String, String> values, List<String> order) {
            this.values = values;
            this.order = order;
        }

        String value(String name) {
            return values.get(name);
        }

        String first() {
            return order.isEmpty() ? null : order.get(0);
        }

        /** Null when malformed: a segment without {@code =}, a bad name, a repeat. */
        static TagList parse(String text) {
            Map<String, String> values = new HashMap<>();
            List<String> order = new ArrayList<>();
            String[] segments = text.split(";", -1);
            for (int k = 0; k < segments.length; k++) {
                String segment = segments[k];
                if (segment.isBlank()) {
                    // Only a trailing ";" may leave an empty segment.
                    if (k == segments.length - 1) {
                        continue;
                    }
                    return null;
                }
                int equals = segment.indexOf('=');
                if (equals < 0) {
                    return null;
                }
                String name = stripFws(segment.substring(0, equals));
                if (!isTagName(name) || values.containsKey(name)) {
                    return null;
                }
                values.put(name, trimFws(segment.substring(equals + 1)));
                order.add(name);
            }
            return new TagList(values, order);
        }

        /**
         * The text with one tag's value removed, everything else (whitespace and
         * folding included) kept — how the {@code b=} tag is hashed.
         */
        static String blankValue(String text, String tag) {
            StringBuilder out = new StringBuilder(text.length());
            int start = 0;
            while (start <= text.length()) {
                int end = text.indexOf(';', start);
                if (end < 0) {
                    end = text.length();
                }
                String segment = text.substring(start, end);
                int equals = segment.indexOf('=');
                if (equals >= 0 && stripFws(segment.substring(0, equals)).equals(tag)) {
                    out.append(segment, 0, equals + 1);
                } else {
                    out.append(segment);
                }
                if (end < text.length()) {
                    out.append(';');
                }
                start = end + 1;
            }
            return out.toString();
        }
    }

    private static boolean isTagName(String name) {
        if (name.isEmpty() || !isAlpha(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!isAlpha(c) && !(c >= '0' && c <= '9') && c != '_') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** All whitespace, folding included, removed. */
    static String stripFws(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String trimFws(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isFws(text.charAt(start))) {
            start++;
        }
        while (end > start && isFws(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isFws(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private static boolean listContains(String list, String item) {
        for (String entry : stripFws(list).toLowerCase(Locale.ROOT).split(":", -1)) {
            if (entry.equals(item)) {
                return true;
            }
        }
        return false;
    }

    /** Base64 with whitespace removed; null when it is not base64. */
    private static byte[] base64(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(stripFws(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A lower-case DNS name of at least two labels, or null. */
    static String validDomain(String value) {
        if (value == null) {
            return null;
        }
        String normalised = MailDnsResolver.normalise(value);
        if (normalised == null) {
            return null;
        }
        String domain = normalised.substring(0, normalised.length() - 1);
        return domain.indexOf('.') < 0 ? null : domain;
    }

    private static String validSelector(String value) {
        if (value == null) {
            return null;
        }
        String normalised = MailDnsResolver.normalise(value);
        return normalised == null ? null : normalised.substring(0, normalised.length() - 1);
    }

    // ---------------------------------------------------------------------
    // Key records (RFC 6376 §3.6.1)
    // ---------------------------------------------------------------------

    private static final class KeyRecord {
        String failure;
        String keyType = "rsa";
        boolean hashAllowed = true;
        boolean serviceAllowed = true;
        boolean testing;
        boolean strictIdentity;
        byte[] publicKey;

        static KeyRecord parse(String text) {
            KeyRecord key = new KeyRecord();
            TagList tags = TagList.parse(text);
            if (tags == null) {
                key.failure = "the key record is malformed";
                return key;
            }
            String v = tags.value("v");
            if (v != null && (!"DKIM1".equals(v) || !"v".equals(tags.first()))) {
                key.failure = "the key record's v= is not DKIM1 in first place";
                return key;
            }
            String k = tags.value("k");
            if (k != null) {
                key.keyType = k.toLowerCase(Locale.ROOT);
            }
            String h = tags.value("h");
            if (h != null) {
                key.hashAllowed = listContains(h, "sha256");
            }
            String s = tags.value("s");
            if (s != null) {
                key.serviceAllowed = listContains(s, "*") || listContains(s, "email");
            }
            String t = tags.value("t");
            if (t != null) {
                key.testing = listContains(t, "y");
                key.strictIdentity = listContains(t, "s");
            }
            String p = tags.value("p");
            if (p == null) {
                key.failure = "the key record has no p=";
                return key;
            }
            if (stripFws(p).isEmpty()) {
                key.failure = "the key is revoked (empty p=)";
                return key;
            }
            key.publicKey = base64(p);
            if (key.publicKey == null || key.publicKey.length == 0) {
                key.failure = "the key record's p= is not base64";
            }
            return key;
        }
    }
}
