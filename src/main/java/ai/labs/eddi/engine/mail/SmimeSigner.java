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
import jakarta.mail.internet.MimeUtility;
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
import java.io.OutputStream;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * S/MIME clear-signing of a rendered message (email channel v3 plan §6.4), and
 * the verification of one that arrived.
 * <p>
 * <b>Signing</b> turns a finished RFC 5322 message into a
 * {@code multipart/signed; protocol="application/pkcs7-signature";
 * micalg=sha-256} whose first part is the original content and whose second
 * part is the detached CMS signature (SHA-256; RSA PKCS#1 v1.5 or ECDSA by key
 * type). The message's own headers — From, To, Subject, Message-ID, the
 * threading and {@code Auto-Submitted} headers — stay on the outer message,
 * unchanged. A client without S/MIME still shows the text.
 * <p>
 * Built on the Angus {@code MimeMessage} the renderer produced — there is no
 * second MIME stack. <b>The signed part is always quoted-printable or
 * base64.</b> The renderer writes ASCII {@code format=flowed} text as
 * {@code 7bit}, and flowed text ends every soft-wrapped line in a space (RFC
 * 3676). A relay that strips trailing whitespace — some do — would change the
 * signed bytes and break the signature, and RFC 1847 forbids re-encoding the
 * first part of a {@code multipart/signed} after signing. So a {@code 7bit},
 * {@code 8bit} or {@code binary} text part is re-encoded as quoted-printable
 * <em>before</em> it is signed: a trailing space becomes {@code =20}, which no
 * relay touches (RFC 3676 §4.2 names quoted-printable as the way to protect
 * trailing spaces, and RFC 8551 §3.1.4 encoding trailing whitespace as
 * {@code =20}). A base64 or quoted-printable part is carried over as its raw
 * bytes. The recipient decodes the part and sees the same flowed text.
 * <p>
 * <b>No {@code SMIMECapabilities}.</b> The signature does not carry that
 * attribute (RFC 8551 §2.5.2), which tells a correspondent's client which
 * content-encryption algorithms the signer can decrypt. EDDI never decrypts
 * mail, so advertising AES would invite replies encrypted to the signing
 * certificate that no agent could read. (A client may still encrypt to a
 * certificate it collected from a signature; a signing-only certificate,
 * without {@code keyEncipherment}, prevents that.)
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
            byte[] rawContent;
            try (InputStream raw = original.getRawInputStream()) {
                rawContent = raw.readAllBytes();
            }
            if (original.isMimeType("text/*") && isIdentityEncoding(encoding)) {
                rawContent = quotedPrintable(rawContent);
                encoding = "quoted-printable";
            }
            if (encoding != null) {
                contentHeaders.setHeader("Content-Transfer-Encoding", encoding);
            }
            MimeBodyPart content = new MimeBodyPart(contentHeaders, rawContent);

            SMIMESignedGenerator generator = new SMIMESignedGenerator();
            // The default signed attributes (content type, signing time, digest, algorithm
            // protection) and no SMIMECapabilities: see the class comment.
            generator.addSignerInfoGenerator(
                    new JcaSimpleSignerInfoGeneratorBuilder().build(algorithmFor(identity), identity.privateKey(), identity.certificate()));
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

    /**
     * Whether {@code encoding} leaves the bytes as they are ({@code 7bit},
     * {@code 8bit}, {@code binary}, or none, which means {@code 7bit}).
     */
    static boolean isIdentityEncoding(String encoding) {
        if (encoding == null) {
            return true;
        }
        String normalized = encoding.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || normalized.equals("7bit") || normalized.equals("8bit") || normalized.equals("binary");
    }

    /**
     * {@code bytes} (CRLF line ends) as quoted-printable: line breaks stay hard
     * breaks, a space or tab before one becomes {@code =20} / {@code =09}.
     */
    static byte[] quotedPrintable(byte[] bytes) throws MessagingException, IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(bytes.length + bytes.length / 8 + 16);
        try (OutputStream encoder = MimeUtility.encode(encoded, "quoted-printable")) {
            encoder.write(bytes);
        }
        return encoded.toByteArray();
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
     * @param certificates
     *            the signer's certificate first, then every other certificate the
     *            signature carries (empty unless verified) — what a chain check of
     *            the received signature needs
     */
    public record VerificationResult(Verification verification, List<String> signerAddresses, List<X509Certificate> certificates) {
        public VerificationResult {
            signerAddresses = signerAddresses == null ? List.of() : List.copyOf(signerAddresses);
            certificates = certificates == null ? List.of() : List.copyOf(certificates);
        }

        public VerificationResult(Verification verification, List<String> signerAddresses) {
            this(verification, signerAddresses, List.of());
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
            if (multipart.getCount() != 2) {
                // RFC 1847 section 2.1: exactly the content and the signature. A third part
                // would never be read.
                return new VerificationResult(Verification.INVALID, List.of());
            }
            SMIMESigned signed = new SMIMESigned(multipart);
            Store<X509CertificateHolder> certificates = signed.getCertificates();
            Collection<SignerInformation> signers = signed.getSignerInfos().getSigners();
            if (signers.isEmpty()) {
                return new VerificationResult(Verification.INVALID, List.of());
            }
            List<String> addresses = List.of();
            X509Certificate signerCertificate = null;
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
                signerCertificate = certificate;
            }
            List<X509Certificate> carried = new ArrayList<>();
            carried.add(signerCertificate);
            @SuppressWarnings("unchecked")
            Collection<X509CertificateHolder> all = certificates.getMatches(null);
            for (X509CertificateHolder holder : all) {
                X509Certificate other = converter.getCertificate(holder);
                if (!other.equals(signerCertificate)) {
                    carried.add(other);
                }
            }
            return new VerificationResult(Verification.VERIFIED, addresses, carried);
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
