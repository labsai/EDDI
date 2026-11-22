/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.mail.smime.SMIMESigned;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S/MIME clear-signing of a rendered message, verified with Bouncy Castle's own
 * {@link SMIMESigned} — independent of {@link SmimeSigner#verify}.
 */
public class SmimeSignerTest {

    private static final String ADDRESS = "agent@contoso.com";

    public static byte[] rendered(String text) {
        return PlainTextMailRenderer.render(new OutboundMail(new MailAddress(ADDRESS, "Agent"), List.of(new MailAddress("alice@partner.com", null)),
                List.of(), "Re: Offer", text, "<eddi.0000@contoso.com>", "<orig@partner.com>", List.of("<orig@partner.com>"),
                OutboundMail.Kind.AUTO_REPLIED, Instant.parse("2026-11-21T10:00:00Z"))).mime();
    }

    static MimeMessage parse(byte[] raw) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw));
    }

    /**
     * Verifies with Bouncy Castle directly; returns whether every signer verifies.
     */
    public static boolean bouncyCastleVerifies(byte[] raw) throws Exception {
        MimeMessage message = parse(raw);
        SMIMESigned signed = new SMIMESigned((MimeMultipart) message.getContent());
        Collection<SignerInformation> signers = signed.getSignerInfos().getSigners();
        if (signers.isEmpty()) {
            return false;
        }
        for (SignerInformation signer : signers) {
            @SuppressWarnings("unchecked")
            Collection<X509CertificateHolder> certificates = signed.getCertificates().getMatches(signer.getSID());
            try {
                if (!signer.verify(new JcaSimpleSignerInfoVerifierBuilder().build(certificates.iterator().next()))) {
                    return false;
                }
            } catch (CMSException e) {
                // A digest mismatch is thrown, not returned.
                return false;
            }
        }
        return true;
    }

    @Nested
    class Signing {

        @Test
        void producesMultipartSignedSha256ThatBouncyCastleVerifies() throws Exception {
            var issued = SmimeTestCertificates.issue(SmimeTestCertificates.Leaf.valid(ADDRESS));
            byte[] signed = SmimeSigner.sign(rendered("Here is the offer.\nPrices in EUR, incl. Umlaute: äöü."), issued.identity());

            MimeMessage message = parse(signed);
            assertTrue(message.isMimeType("multipart/signed"), message.getContentType());
            String contentType = message.getContentType().toLowerCase();
            assertTrue(contentType.contains("application/pkcs7-signature"), contentType);
            assertTrue(contentType.contains("micalg=sha-256"), contentType);
            assertTrue(bouncyCastleVerifies(signed));
        }

        @Test
        void keepsTheMessageHeadersAndTheText() throws Exception {
            var issued = SmimeTestCertificates.issue(SmimeTestCertificates.Leaf.valid(ADDRESS));
            byte[] original = rendered("Hello Alice");
            MimeMessage before = parse(original);
            MimeMessage after = parse(SmimeSigner.sign(original, issued.identity()));

            assertEquals(before.getMessageID(), after.getMessageID());
            assertEquals(before.getHeader("In-Reply-To")[0], after.getHeader("In-Reply-To")[0]);
            assertEquals(before.getHeader("Auto-Submitted")[0], after.getHeader("Auto-Submitted")[0]);
            assertEquals(before.getSubject(), after.getSubject());
            assertEquals(before.getFrom()[0].toString(), after.getFrom()[0].toString());
            MimeMultipart parts = (MimeMultipart) after.getContent();
            assertEquals(2, parts.getCount());
            assertTrue(parts.getBodyPart(0).isMimeType("text/plain"));
            assertTrue(parts.getBodyPart(0).getContent().toString().contains("Hello Alice"));
        }

        @Test
        void signsWithAnEcKeyToo() throws Exception {
            var issued = SmimeTestCertificates.issue(SmimeTestCertificates.Leaf.valid(ADDRESS).ec());
            byte[] signed = SmimeSigner.sign(rendered("EC signed"), issued.identity());
            assertTrue(bouncyCastleVerifies(signed));
            assertEquals(SmimeSigner.Verification.VERIFIED, SmimeSigner.verify(signed).verification());
        }

        @Test
        void carriesTheCertificateChain() throws Exception {
            var issued = SmimeTestCertificates.issue(SmimeTestCertificates.Leaf.valid(ADDRESS));
            MimeMessage message = parse(SmimeSigner.sign(rendered("chain"), issued.identity()));
            SMIMESigned signed = new SMIMESigned((MimeMultipart) message.getContent());
            assertEquals(2, signed.getCertificates().getMatches(null).size());
        }
    }

    @Nested
    class Verifying {

        @Test
        void readsItsOwnSignatureAndTheSignerAddress() throws Exception {
            var issued = SmimeTestCertificates.issue(SmimeTestCertificates.Leaf.valid(ADDRESS));
            var result = SmimeSigner.verify(SmimeSigner.sign(rendered("x"), issued.identity()));
            assertEquals(SmimeSigner.Verification.VERIFIED, result.verification());
            assertEquals(List.of(ADDRESS), result.signerAddresses());
        }

        @Test
        void anUnsignedMessageIsNotSigned() {
            assertEquals(SmimeSigner.Verification.NOT_SIGNED, SmimeSigner.verify(rendered("plain")).verification());
        }

        @Test
        void aChangedBodyDoesNotVerify() throws Exception {
            var issued = SmimeTestCertificates.issue(SmimeTestCertificates.Leaf.valid(ADDRESS));
            String signed = new String(SmimeSigner.sign(rendered("Pay 100 EUR to account A"), issued.identity()), StandardCharsets.ISO_8859_1);
            assertTrue(signed.contains("Pay 100 EUR"));
            byte[] tampered = signed.replace("Pay 100 EUR", "Pay 900 EUR").getBytes(StandardCharsets.ISO_8859_1);

            assertFalse(bouncyCastleVerifies(tampered));
            assertEquals(SmimeSigner.Verification.INVALID, SmimeSigner.verify(tampered).verification());
        }
    }
}
