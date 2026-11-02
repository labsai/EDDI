/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Signs messages for the DKIM tests.
 * <p>
 * Written independently of {@link DkimVerifier} on purpose — regular
 * expressions over strings where the verifier walks bytes — so that a
 * canonicalisation bug is not simply mirrored on both sides and passed. The RFC
 * 8463 vector and the RFC 6376 §3.4.6 example in {@code DkimVerifierTest} are
 * the ground truth that ties both to the standard.
 */
final class DkimTestSigner {

    static final String CRLF = "\r\n";

    /** A key pair and the DNS TXT record that publishes it. */
    record TestKey(String algorithm, KeyPair pair, String keyType) {

        String publicKeyBase64() {
            byte[] encoded = pair.getPublic().getEncoded();
            if ("ed25519".equals(keyType)) {
                // The record holds the raw 32-byte key, the tail of the SPKI.
                encoded = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
            }
            return Base64.getEncoder().encodeToString(encoded);
        }

        String record() {
            return "v=DKIM1; k=" + keyType + "; p=" + publicKeyBase64();
        }
    }

    private static TestKey rsa2048;
    private static TestKey ed25519;

    static synchronized TestKey rsa2048() {
        if (rsa2048 == null) {
            rsa2048 = rsa(2048);
        }
        return rsa2048;
    }

    static synchronized TestKey ed25519() {
        if (ed25519 == null) {
            try {
                ed25519 = new TestKey("ed25519-sha256", KeyPairGenerator.getInstance("Ed25519").generateKeyPair(), "ed25519");
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
        return ed25519;
    }

    static TestKey rsa(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return new TestKey("rsa-sha256", generator.generateKeyPair(), "rsa");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * What to sign and how. Mutable builder; defaults are a plain relaxed/relaxed
     * signature.
     */
    static final class Options {
        TestKey key = rsa2048();
        String algorithmTag;
        String domain = "example.com";
        String selector = "sel";
        String canonicalisation = "relaxed/relaxed";
        List<String> headers = List.of("from", "to", "subject", "date", "message-id");
        /** Extra tags inserted before bh=, e.g. "i=@example.com; t=1". */
        String extraTags = "";

        Options key(TestKey value) {
            key = value;
            return this;
        }

        Options algorithmTag(String value) {
            algorithmTag = value;
            return this;
        }

        Options domain(String value) {
            domain = value;
            return this;
        }

        Options selector(String value) {
            selector = value;
            return this;
        }

        Options canonicalisation(String value) {
            canonicalisation = value;
            return this;
        }

        Options headers(String... value) {
            headers = List.of(value);
            return this;
        }

        Options extraTags(String value) {
            extraTags = value;
            return this;
        }
    }

    private DkimTestSigner() {
    }

    /** Signs a CRLF message and returns it with the DKIM-Signature prepended. */
    static byte[] sign(byte[] message, Options options) {
        String text = new String(message, StandardCharsets.ISO_8859_1);
        int split = text.indexOf(CRLF + CRLF);
        String headerBlock = split < 0 ? text : text.substring(0, split + 2);
        String body = split < 0 ? "" : text.substring(split + 4);
        String[] canon = options.canonicalisation.split("/");
        boolean relaxedHeaders = "relaxed".equals(canon[0]);
        boolean relaxedBody = canon.length > 1 && "relaxed".equals(canon[1]);

        String bodyHash = Base64.getEncoder().encodeToString(sha256(relaxedBody ? relaxedBody(body) : simpleBody(body)));
        String algorithm = options.algorithmTag != null ? options.algorithmTag : options.key.algorithm();
        String signatureValue = " v=1; a=" + algorithm + "; c=" + options.canonicalisation + "; d=" + options.domain + "; s="
                + options.selector + ";" + CRLF + "\th=" + String.join(":", options.headers) + ";"
                + (options.extraTags.isEmpty() ? "" : " " + options.extraTags + ";") + CRLF + "\tbh=" + bodyHash + "; b=";

        StringBuilder signed = new StringBuilder();
        List<String[]> fields = fields(headerBlock);
        Map<String, Integer> next = new HashMap<>();
        for (String name : options.headers) {
            String lower = name.toLowerCase(Locale.ROOT);
            List<String[]> matching = new ArrayList<>();
            for (String[] field : fields) {
                if (field[0].strip().equalsIgnoreCase(lower)) {
                    matching.add(field);
                }
            }
            int index = next.getOrDefault(lower, matching.size() - 1);
            next.put(lower, index - 1);
            if (index >= 0) {
                String[] field = matching.get(index);
                signed.append(relaxedHeaders ? relaxedHeader(field[0], field[1]) : field[0] + ":" + field[1]).append(CRLF);
            }
        }
        String signatureHeader = relaxedHeaders ? relaxedHeader("DKIM-Signature", signatureValue) : "DKIM-Signature:" + signatureValue;
        signed.append(signatureHeader);
        byte[] b = signRaw(options.key, signed.toString().getBytes(StandardCharsets.ISO_8859_1));
        String encoded = Base64.getEncoder().encodeToString(b);
        String folded = fold(encoded);
        String header = "DKIM-Signature:" + signatureValue + folded + CRLF;
        return (header + text).getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Signs canonical header bytes directly, for vectors whose canonical form is
     * given.
     */
    static byte[] signRaw(TestKey key, byte[] canonicalHeaders) {
        try {
            if ("ed25519".equals(key.keyType())) {
                Signature signer = Signature.getInstance("Ed25519");
                signer.initSign(key.pair().getPrivate());
                signer.update(sha256(canonicalHeaders));
                return signer.sign();
            }
            PrivateKey privateKey = key.pair().getPrivate();
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(canonicalHeaders);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String fold(String base64) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < base64.length(); i += 60) {
            if (i > 0) {
                out.append(CRLF).append('\t');
            }
            out.append(base64, i, Math.min(base64.length(), i + 60));
        }
        return out.toString();
    }

    /** Header fields as {name, value} with folding kept, top to bottom. */
    private static List<String[]> fields(String headerBlock) {
        List<String[]> fields = new ArrayList<>();
        for (String field : headerBlock.split("\r\n(?![ \t])")) {
            if (field.isEmpty()) {
                continue;
            }
            int colon = field.indexOf(':');
            fields.add(new String[]{field.substring(0, colon), field.substring(colon + 1)});
        }
        return fields;
    }

    static String relaxedHeader(String name, String value) {
        return name.strip().toLowerCase(Locale.ROOT) + ":" + value.replace(CRLF, "").replaceAll("[ \t]+", " ").strip();
    }

    static byte[] simpleBody(String body) {
        String stripped = body.replaceAll("(\r\n)+$", "");
        return (stripped + CRLF).getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] relaxedBody(String body) {
        String[] lines = body.split(CRLF, -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].replaceAll("[ \t]+", " ").replaceAll(" $", "");
            out.append(line);
            if (i < lines.length - 1) {
                out.append(CRLF);
            }
        }
        String joined = out.toString().replaceAll("(\r\n)+$", "");
        return (joined.isEmpty() ? "" : joined + CRLF).getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A simple CRLF test message. */
    static byte[] message(String... headersThenBody) {
        StringBuilder out = new StringBuilder();
        boolean inBody = false;
        for (String line : headersThenBody) {
            out.append(line).append(CRLF);
            inBody |= line.isEmpty();
        }
        if (!inBody) {
            out.append(CRLF);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] standardMessage() {
        return message("From: Alice <alice@example.com>", "To: bob@example.org", "Subject: Quarterly numbers",
                "Date: Fri, 23 Oct 2026 10:00:00 +0000", "Message-ID: <q3-1@example.com>", "", "Hello Bob,", "", "the numbers are in.",
                "", "Alice");
    }

    /** Ordered TXT records by name, for {@link FakeDns}. */
    static Map<String, String> records(Object... nameThenRecord) {
        if (nameThenRecord.length % 2 != 0) {
            throw new IllegalArgumentException("records() takes name/record pairs, got " + nameThenRecord.length + " arguments");
        }
        Map<String, String> records = new LinkedHashMap<>();
        for (int i = 0; i < nameThenRecord.length; i += 2) {
            records.put((String) nameThenRecord[i], (String) nameThenRecord[i + 1]);
        }
        return records;
    }
}
