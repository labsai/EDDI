package ai.labs.eddi.engine.mail;

import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the findings of the first review round on the S/MIME
 * work: every one fails with its fix reverted.
 */
class SmimeReviewFindingsTest {

    private static final String ADDRESS = "invoices@contoso.com";

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(String issuer, String subject, KeyPair subjectKey, KeyPair signerKey, Instant from, Instant to,
                                               BasicConstraints constraints, KeyUsage usage)
            throws Exception {
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(new X500Name(issuer), BigInteger.valueOf(System.nanoTime()),
                Date.from(from), Date.from(to), new X500Name(subject), subjectKey.getPublic());
        if (constraints != null) {
            builder.addExtension(Extension.basicConstraints, true, constraints);
        }
        if (usage != null) {
            builder.addExtension(Extension.keyUsage, true, usage);
        }
        return new JcaX509CertificateConverter()
                .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(signerKey.getPrivate())));
    }

    private static SmimeCertificateCheck.Chain chain(List<X509Certificate> bundle) {
        return SmimeCertificateCheck.internalChain(bundle, Instant.now());
    }

    private record Pki(X509Certificate root, X509Certificate intermediate, X509Certificate leaf, KeyPair intermediateKey, KeyPair rootKey) {
    }

    private static Pki pki(Instant intermediateFrom, Instant intermediateTo, BasicConstraints intermediateConstraints, KeyUsage intermediateUsage)
            throws Exception {
        Instant now = Instant.now();
        KeyPair rootKey = rsa();
        KeyPair intermediateKey = rsa();
        KeyPair leafKey = rsa();
        X509Certificate root = certificate("CN=Root", "CN=Root", rootKey, rootKey, now.minus(Duration.ofDays(60)), now.plus(Duration.ofDays(900)),
                new BasicConstraints(true), new KeyUsage(KeyUsage.keyCertSign));
        X509Certificate intermediate = certificate("CN=Root", "CN=Intermediate", intermediateKey, rootKey, intermediateFrom, intermediateTo,
                intermediateConstraints, intermediateUsage);
        X509Certificate leaf = certificate("CN=Intermediate", "CN=Leaf", leafKey, intermediateKey, now.minus(Duration.ofDays(10)),
                now.plus(Duration.ofDays(300)), null, new KeyUsage(KeyUsage.digitalSignature));
        return new Pki(root, intermediate, leaf, intermediateKey, rootKey);
    }

    @Test
    @DisplayName("an intermediate that expired is not a path: the chain is broken, not just untrusted")
    void anExpiredIntermediateBreaksTheChain() throws Exception {
        Instant now = Instant.now();
        Pki pki = pki(now.minus(Duration.ofDays(50)), now.minus(Duration.ofDays(1)), new BasicConstraints(0), new KeyUsage(KeyUsage.keyCertSign));
        assertEquals(SmimeCertificateCheck.Chain.BROKEN, chain(List.of(pki.leaf(), pki.intermediate())));
        assertEquals(SmimeCertificateCheck.Chain.BROKEN, chain(List.of(pki.leaf(), pki.intermediate(), pki.root())));
    }

    @Test
    @DisplayName("a shipped issuer that is not a CA, or may not sign certificates, is not a path")
    void anIssuerThatMayNotIssueBreaksTheChain() throws Exception {
        Instant now = Instant.now();
        Pki notCa = pki(now.minus(Duration.ofDays(5)), now.plus(Duration.ofDays(500)), new BasicConstraints(false), null);
        assertEquals(SmimeCertificateCheck.Chain.BROKEN, chain(List.of(notCa.leaf(), notCa.intermediate())));
        Pki noCertSign = pki(now.minus(Duration.ofDays(5)), now.plus(Duration.ofDays(500)), new BasicConstraints(0),
                new KeyUsage(KeyUsage.digitalSignature));
        assertEquals(SmimeCertificateCheck.Chain.BROKEN, chain(List.of(noCertSign.leaf(), noCertSign.intermediate())));
    }

    @Test
    @DisplayName("a valid CA intermediate with an absent root stays usable but untrusted; with its root it is complete")
    void aValidIntermediateKeepsItsVerdicts() throws Exception {
        Instant now = Instant.now();
        Pki pki = pki(now.minus(Duration.ofDays(5)), now.plus(Duration.ofDays(500)), new BasicConstraints(0), new KeyUsage(KeyUsage.keyCertSign));
        assertEquals(SmimeCertificateCheck.Chain.UNTRUSTED_ROOT, chain(List.of(pki.leaf(), pki.intermediate())));
        assertEquals(SmimeCertificateCheck.Chain.COMPLETE, chain(List.of(pki.leaf(), pki.intermediate(), pki.root())));
    }

    @Test
    @DisplayName("the verdict does not depend on the order of two intermediates that share a subject and key")
    void theBundleOrderDoesNotMatter() throws Exception {
        Instant now = Instant.now();
        Pki pki = pki(now.minus(Duration.ofDays(5)), now.plus(Duration.ofDays(500)), new BasicConstraints(0), new KeyUsage(KeyUsage.keyCertSign));
        // The same intermediate (subject and key) cross-signed by a key that is not the
        // root's.
        KeyPair stranger = rsa();
        X509Certificate lookalike = certificate("CN=Root", "CN=Intermediate", pki.intermediateKey(), stranger, now.minus(Duration.ofDays(5)),
                now.plus(Duration.ofDays(500)), new BasicConstraints(0), new KeyUsage(KeyUsage.keyCertSign));
        assertEquals(SmimeCertificateCheck.Chain.COMPLETE, chain(List.of(pki.leaf(), pki.intermediate(), lookalike, pki.root())));
        assertEquals(SmimeCertificateCheck.Chain.COMPLETE, chain(List.of(pki.leaf(), lookalike, pki.intermediate(), pki.root())));
    }

    @Test
    @DisplayName("a subject e-mail with a plus is read from the structured subject, unescaped")
    void aPlusAddressInTheSubjectIsRead() throws Exception {
        KeyPair key = rsa();
        Instant now = Instant.now();
        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, "Agent").addRDN(BCStyle.EmailAddress, "agent+ops@contoso.com")
                .build();
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject, BigInteger.ONE, Date.from(now.minus(Duration.ofDays(1))),
                Date.from(now.plus(Duration.ofDays(30))), subject, key.getPublic());
        X509Certificate certificate = new JcaX509CertificateConverter()
                .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(key.getPrivate())));
        assertEquals(List.of("agent+ops@contoso.com"), SmimeCertificateCheck.emailAddresses(certificate));
    }

    @Test
    @DisplayName("a bundle over the certificate limit is refused, not cut")
    void anOversizedBundleIsRefused() throws Exception {
        KeyPair key = rsa();
        Instant now = Instant.now();
        StringWriter pem = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(pem)) {
            writer.writeObject(key.getPrivate());
            for (int i = 0; i <= SmimeIdentity.MAX_CERTIFICATES; i++) {
                writer.writeObject(certificate("CN=C" + i, "CN=C" + i, key, key, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(30)), null,
                        null));
            }
        }
        var thrown = assertThrows(IllegalArgumentException.class, () -> SmimeIdentity.load(pem.toString(), null));
        assertTrue(thrown.getMessage().contains("more than " + SmimeIdentity.MAX_CERTIFICATES), thrown.getMessage());
    }

    @Test
    @DisplayName("a multipart/signed container with a third part does not verify")
    void aThirdPartIsInvalid() throws Exception {
        var issued = SmimeTestCertificates.issue(SmimeTestCertificates.Leaf.valid(ADDRESS));
        byte[] signed = SmimeSigner.sign(SmimeSignerTest.rendered("Hello Alice"), issued.identity());
        assertEquals(SmimeSigner.Verification.VERIFIED, SmimeSigner.verify(signed).verification());

        MimeMessage message = SmimeSignerTest.parse(signed);
        MimeMultipart multipart = (MimeMultipart) message.getContent();
        MimeBodyPart extra = new MimeBodyPart();
        extra.setText("appended", StandardCharsets.UTF_8.name());
        multipart.addBodyPart(extra);
        message.setContent(multipart);
        message.saveChanges();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);

        assertEquals(SmimeSigner.Verification.INVALID, SmimeSigner.verify(out.toByteArray()).verification());
    }
}
