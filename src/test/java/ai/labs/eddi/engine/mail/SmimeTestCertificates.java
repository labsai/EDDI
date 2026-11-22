/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * S/MIME test certificates, generated in the test — never a checked-in key. One
 * CA (self-signed) issues leaf certificates whose address, key usages and
 * validity a test chooses.
 */
public final class SmimeTestCertificates {

    private static final AtomicLong SERIAL = new AtomicLong(System.nanoTime());
    private static KeyPair rsaCa;
    private static X509Certificate caCertificate;

    private SmimeTestCertificates() {
    }

    /** What the leaf certificate carries. */
    public record Leaf(String address, boolean useEc, boolean digitalSignature, boolean emailProtection, Instant notBefore, Instant notAfter,
            boolean selfSigned) {

        /** A usable RSA certificate for {@code address}, valid for a year. */
        public static Leaf valid(String address) {
            Instant now = Instant.now();
            return new Leaf(address, false, true, true, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(365)), false);
        }

        public Leaf ec() {
            return new Leaf(address, true, digitalSignature, emailProtection, notBefore, notAfter, selfSigned);
        }

        public Leaf withoutDigitalSignature() {
            return new Leaf(address, useEc, false, emailProtection, notBefore, notAfter, selfSigned);
        }

        public Leaf withoutEmailProtection() {
            return new Leaf(address, useEc, digitalSignature, false, notBefore, notAfter, selfSigned);
        }

        public Leaf expiringIn(Duration duration) {
            return new Leaf(address, useEc, digitalSignature, emailProtection, Instant.now().minus(Duration.ofDays(300)),
                    Instant.now().plus(duration), selfSigned);
        }

        public Leaf expired() {
            return new Leaf(address, useEc, digitalSignature, emailProtection, Instant.now().minus(Duration.ofDays(400)),
                    Instant.now().minus(Duration.ofDays(1)), selfSigned);
        }

        public Leaf selfSignedOnly() {
            return new Leaf(address, useEc, digitalSignature, emailProtection, notBefore, notAfter, true);
        }
    }

    /** A key and its chain: leaf, then the CA (absent for a self-signed leaf). */
    public record Issued(PrivateKey key, List<X509Certificate> chain) {
        public SmimeIdentity identity() {
            return new SmimeIdentity(key, chain);
        }

        public X509Certificate leaf() {
            return chain.getFirst();
        }
    }

    public static synchronized X509Certificate ca() throws Exception {
        if (caCertificate == null) {
            rsaCa = rsa();
            X500Name name = new X500Name("CN=EDDI Test S/MIME CA");
            Instant now = Instant.now();
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(SERIAL.incrementAndGet()),
                    Date.from(now.minus(Duration.ofDays(30))), Date.from(now.plus(Duration.ofDays(3650))), name, rsaCa.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            caCertificate = new JcaX509CertificateConverter()
                    .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(rsaCa.getPrivate())));
        }
        return caCertificate;
    }

    public static Issued issue(Leaf leaf) throws Exception {
        X509Certificate ca = ca();
        KeyPair pair = leaf.useEc() ? ec() : rsa();
        X500Name subject = new X500Name("CN=EDDI Test Mailbox");
        X500Name issuer = leaf.selfSigned() ? subject : new X500Name(ca.getSubjectX500Principal().getName());
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuer, BigInteger.valueOf(SERIAL.incrementAndGet()),
                Date.from(leaf.notBefore()), Date.from(leaf.notAfter()), subject, pair.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.rfc822Name, leaf.address())));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(leaf.digitalSignature() ? KeyUsage.digitalSignature | KeyUsage.keyEncipherment : KeyUsage.keyEncipherment));
        builder.addExtension(Extension.extendedKeyUsage, false,
                new ExtendedKeyUsage(leaf.emailProtection() ? KeyPurposeId.id_kp_emailProtection : KeyPurposeId.id_kp_clientAuth));
        PrivateKey signer = leaf.selfSigned() ? pair.getPrivate() : rsaCa.getPrivate();
        String algorithm = leaf.selfSigned() && leaf.useEc() ? "SHA256withECDSA" : "SHA256withRSA";
        X509Certificate certificate = new JcaX509CertificateConverter()
                .getCertificate(builder.build(new JcaContentSignerBuilder(algorithm).build(signer)));
        List<X509Certificate> chain = new ArrayList<>();
        chain.add(certificate);
        if (!leaf.selfSigned()) {
            chain.add(ca);
        }
        return new Issued(pair.getPrivate(), chain);
    }

    /** The key and chain as PEM text: key first, then the certificates. */
    public static String pem(Issued issued) throws Exception {
        StringWriter out = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(issued.key());
            for (X509Certificate certificate : issued.chain()) {
                writer.writeObject(certificate);
            }
        }
        return out.toString();
    }

    /** The key and chain as a base64 PKCS#12 keystore. */
    public static String pkcs12(Issued issued, String password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("smime", issued.key(), password.toCharArray(), issued.chain().toArray(new Certificate[0]));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.store(out, password.toCharArray());
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static KeyPair ec() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
}
