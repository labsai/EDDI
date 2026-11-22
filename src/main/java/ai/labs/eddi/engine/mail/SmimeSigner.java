/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.smime.SMIMECapabilitiesAttribute;
import org.bouncycastle.asn1.smime.SMIMECapability;
import org.bouncycastle.asn1.smime.SMIMECapabilityVector;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationVerifier;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.mail.smime.SMIMESigned;
import org.bouncycastle.mail.smime.SMIMESignedGenerator;
import org.bouncycastle.util.Store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

/**
 * S/MIME clear-signing of a rendered message (email channel v3 plan §6.4), and
 * the verification of one that arrived.
 * <p>
 * <b>Signing</b> turns a finished RFC 5322 message into a
 * {@code multipart/signed; protocol="application/pkcs7-signature";
 * micalg=sha-256} whose first part is the original content, byte for byte, and
 * whose second part is the detached CMS signature (SHA-256; RSA PKCS#1 v1.5 or
 * ECDSA by key type). The message's own headers — From, To, Subject,
 * Message-ID, the threading and {@code Auto-Submitted} headers — stay on the
 * outer message, unchanged. A client without S/MIME still shows the text.
 * <p>
 * Built on the Angus {@code MimeMessage} the renderer produced — there is no
 * second MIME stack. The content part is carried over as its raw, already
 * transfer-encoded bytes: the renderer only produces 7-bit content
 * (quoted-printable or base64 for anything else), which is what a signature
 * over MIME needs to survive relays.
 */
public final class SmimeSigner {

    /** The {@code micalg} of every signature EDDI makes. */
    public static final String MICALG = "sha-256";

    private static final Session SESSION = Session.getInstance(new Properties());

    private SmimeSigner() {
    }

    /**
     * Signs {@code mime} with {@code identity}.
     *
     * @return the signed message's bytes
     * @throws MailSendException
     *             ({@code SIGNING}, not permanent) when the message cannot be
     *             parsed or the signature cannot be made
     */
    public static byte[] sign(byte[] mime, SmimeIdentity identity) throws MailSendException {
        try {
            MimeMessage original = new MimeMessage(SESSION, new ByteArrayInputStream(mime));
            String messageId = original.getMessageID();

            InternetHeaders contentHeaders = new InternetHeaders();
            contentHeaders.setHeader("Content-Type", original.getContentType());
            String encoding = original.getEncoding();
            if (encoding != null) {
                contentHeaders.setHeader("Content-Transfer-Encoding", encoding);
            }
            byte[] rawContent;
            try (InputStream raw = original.getRawInputStream()) {
                rawContent = raw.readAllBytes();
            }
            MimeBodyPart content = new MimeBodyPart(contentHeaders, rawContent);

            SMIMESignedGenerator generator = new SMIMESignedGenerator();
            generator.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder().setSignedAttributeGenerator(capabilities())
                    .build(algorithmFor(identity), identity.privateKey(), identity.certificate()));
            generator.addCertificates(new JcaCertStore(identity.chain()));
            MimeMultipart signed = generator.generate(content);

            MimeMessage out = new KeepIdMimeMessage(SESSION, messageId);
            Enumeration<String> headers = original.getNonMatchingHeaderLines(new String[]{"Content-Type", "Content-Transfer-Encoding",
                    "MIME-Version"});
            while (headers.hasMoreElements()) {
                out.addHeaderLine(headers.nextElement());
            }
            out.setContent(signed, signed.getContentType());
            out.saveChanges();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(mime.length + 8192);
            out.writeTo(bytes);
            return bytes.toByteArray();
        } catch (MessagingException | IOException e) {
            throw new MailSendException(MailSendException.Category.SIGNING, false, "The message could not be S/MIME-signed: " + e.getMessage(), e);
        } catch (Exception e) {
            // Bouncy Castle's operator and SMIME exceptions; never the key itself.
            throw new MailSendException(MailSendException.Category.SIGNING, false,
                    "The message could not be S/MIME-signed (" + e.getClass().getSimpleName() + ").", e);
        }
    }

    /** {@code SHA256withRSA} (PKCS#1 v1.5) or {@code SHA256withECDSA}. */
    static String algorithmFor(SmimeIdentity identity) {
        String algorithm = identity.privateKey().getAlgorithm();
        if ("EC".equalsIgnoreCase(algorithm) || "ECDSA".equalsIgnoreCase(algorithm)) {
            return "SHA256withECDSA";
        }
        if ("RSA".equalsIgnoreCase(algorithm)) {
            return "SHA256withRSA";
        }
        throw new IllegalArgumentException("S/MIME signing supports RSA and EC keys, not " + algorithm + ".");
    }

    /** The S/MIME capabilities a signature advertises. */
    private static AttributeTable capabilities() {
        SMIMECapabilityVector vector = new SMIMECapabilityVector();
        vector.addCapability(SMIMECapability.aES256_CBC);
        vector.addCapability(SMIMECapability.aES128_CBC);
        ASN1EncodableVector attributes = new ASN1EncodableVector();
        attributes.add(new SMIMECapabilitiesAttribute(vector));
        return new AttributeTable(attributes);
    }

    /** What a received message's S/MIME signature says. */
    public enum Verification {
        /** Not a {@code multipart/signed} S/MIME message. */
        NOT_SIGNED,
        /** Signed, and the signature verifies against the certificate it carries. */
        VERIFIED,
        /**
         * Signed, but the signature does not verify (altered in transit, or forged).
         */
        INVALID
    }

    /**
     * The outcome of {@link #verify}.
     *
     * @param verification
     *            the verdict
     * @param signerAddresses
     *            the e-mail addresses the signer's certificate names (empty unless
     *            verified)
     */
    public record VerificationResult(Verification verification, List<String> signerAddresses) {
        public VerificationResult {
            signerAddresses = signerAddresses == null ? List.of() : List.copyOf(signerAddresses);
        }
    }

    /**
     * Verifies the S/MIME signature of a received message against the certificate
     * it carries. Whether that certificate is trusted, or names the sender, is the
     * caller's question; this answers only whether the bytes are what was signed.
     */
    public static VerificationResult verify(byte[] raw) {
        try {
            MimeMessage message = new MimeMessage(SESSION, new ByteArrayInputStream(raw));
            if (!message.isMimeType("multipart/signed")) {
                return new VerificationResult(Verification.NOT_SIGNED, List.of());
            }
            Object content = message.getContent();
            if (!(content instanceof MimeMultipart multipart)) {
                return new VerificationResult(Verification.NOT_SIGNED, List.of());
            }
            SMIMESigned signed = new SMIMESigned(multipart);
            Store<X509CertificateHolder> certificates = signed.getCertificates();
            Collection<SignerInformation> signers = signed.getSignerInfos().getSigners();
            if (signers.isEmpty()) {
                return new VerificationResult(Verification.INVALID, List.of());
            }
            List<String> addresses = List.of();
            JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
            for (SignerInformation signer : signers) {
                @SuppressWarnings("unchecked")
                Collection<X509CertificateHolder> matches = certificates.getMatches(signer.getSID());
                if (matches.isEmpty()) {
                    return new VerificationResult(Verification.INVALID, List.of());
                }
                X509Certificate certificate = converter.getCertificate(matches.iterator().next());
                SignerInformationVerifier verifier = new JcaSimpleSignerInfoVerifierBuilder().build(certificate);
                if (!signer.verify(verifier)) {
                    return new VerificationResult(Verification.INVALID, List.of());
                }
                addresses = SmimeCertificateCheck.emailAddresses(certificate);
            }
            return new VerificationResult(Verification.VERIFIED, addresses);
        } catch (Exception e) {
            return new VerificationResult(Verification.INVALID, List.of());
        }
    }

    /** A message that keeps the Message-ID it was given on save. */
    private static final class KeepIdMimeMessage extends MimeMessage {
        private final String messageId;

        KeepIdMimeMessage(Session session, String messageId) {
            super(session);
            this.messageId = messageId;
        }

        @Override
        protected void updateMessageID() throws MessagingException {
            if (messageId != null) {
                setHeader("Message-ID", messageId);
            } else {
                super.updateMessageID();
            }
        }
    }
}
