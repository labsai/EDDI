/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.bouncycastle.asn1.ASN1String;
import org.bouncycastle.asn1.x500.AttributeTypeAndValue;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CertificateParsingException;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whether an S/MIME signing certificate may sign the mail of one mailbox (email
 * channel v3 plan §6.3), checked at save time and daily.
 * <p>
 * The rules, each a {@link Problem} when broken:
 * <ul>
 * <li>an {@code rfc822Name} subject alternative name — or, without any, the
 * subject's {@code E=} attribute — equals the mailbox address, compared
 * normalised. A recipient's client checks exactly this, so a certificate for
 * another address produces a signature every client flags as forged;</li>
 * <li>key usage, when present, includes {@code digitalSignature} or
 * {@code nonRepudiation} — RFC 8550 §4.4.2, under which a receiving agent
 * accepts a signature from a certificate with at least one of the two; extended
 * key usage, when present, includes {@code emailProtection} (or
 * {@code anyExtendedKeyUsage}). A certificate without either extension is not
 * restricted by it;</li>
 * <li>valid now: not before its {@code notBefore}, not after its
 * {@code notAfter};</li>
 * <li>its chain builds: to a root in the JDK trust store
 * ({@link Chain#TRUSTED}); or, from the leaf through the certificates shipped
 * with it, to a self-signed root that is shipped too ({@link Chain#COMPLETE});
 * or from the leaf through at least one shipped intermediate to a certificate
 * whose issuer is not in the bundle ({@link Chain#UNTRUSTED_ROOT} — the usual
 * {@code .p12} of an S/MIME-only CA, which ships leaf and intermediate but no
 * root). The last two are usable, with a warning: EDDI cannot know whether a
 * recipient trusts that root.</li>
 * </ul>
 * A chain is {@link Chain#BROKEN} — and the certificate unusable — when a
 * certificate's issuer is in the bundle but its signature does not verify with
 * that issuer's key, or when the leaf comes alone and its issuer is not a
 * public root (nothing lets a recipient build the chain: ship the
 * intermediates).
 * <p>
 * <b>What this does not decide.</b> Revocation is not checked, and neither is
 * whether a recipient trusts the root: that is the recipient's decision, made
 * in their mail client. EDDI refuses only what makes a signature unusable for
 * everyone — a wrong address, wrong key usages, a date outside the validity, a
 * chain whose signatures do not verify.
 */
public final class SmimeCertificateCheck {

    /** The OID of the {@code emailProtection} extended key usage. */
    static final String EKU_EMAIL_PROTECTION = "1.3.6.1.5.5.7.3.4";
    /** The OID of {@code anyExtendedKeyUsage}. */
    static final String EKU_ANY = "2.5.29.37.0";
    /** SAN type of an {@code rfc822Name}. */
    private static final int SAN_RFC822 = 1;
    /** Index of keyCertSign in the {@code KeyUsage} bit string. */
    private static final int KEY_USAGE_KEY_CERT_SIGN = 5;
    /** The OID of the PKCS#9 {@code emailAddress} attribute ({@code E=}). */

    /** How soon an expiry is reported as coming. */
    public static final Duration EXPIRY_WARNING = Duration.ofDays(30);

    /** What can be wrong with a certificate, as stable codes. */
    public enum Problem {
        /** No SAN or subject e-mail equals the mailbox address. */
        ADDRESS_MISMATCH("the certificate is issued to %s, not to the mailbox address"),
        /**
         * Key usage present with neither {@code digitalSignature} nor
         * {@code nonRepudiation}.
         */
        KEY_USAGE("its key usage does not allow digital signatures"),
        /** Extended key usage present without {@code emailProtection}. */
        EXTENDED_KEY_USAGE("its extended key usage does not include emailProtection (S/MIME)"),
        /** Before {@code notBefore}. */
        NOT_YET_VALID("it is not valid yet"),
        /** After {@code notAfter}. */
        EXPIRED("it has expired"),
        /**
         * The chain is broken: a signature in it does not verify, or the leaf comes
         * without the intermediates that lead to a root.
         */
        CHAIN("its certificate chain is broken or incomplete: ship the intermediate certificates with it");

        private final String text;

        Problem(String text) {
            this.text = text;
        }

        public String code() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        String text(String issuedTo) {
            return text.contains("%s") ? text.formatted(issuedTo == null ? "no e-mail address" : issuedTo) : text;
        }
    }

    /** How far the chain builds. */
    public enum Chain {
        /** To a root in the JDK trust store. */
        TRUSTED,
        /** Internally, to a root shipped with it that the JDK does not trust. */
        COMPLETE,
        /**
         * Through the shipped intermediates to a certificate whose issuer (the root) is
         * neither shipped nor in the JDK trust store. Usable; recipients decide whether
         * they trust it.
         */
        UNTRUSTED_ROOT,
        /** A signature in the chain does not verify, or the leaf comes alone. */
        BROKEN;

        /** Whether recipients' clients may show the signer as untrusted. */
        public boolean untrusted() {
            return this == COMPLETE || this == UNTRUSTED_ROOT;
        }
    }

    /**
     * The outcome.
     *
     * @param problems
     *            every rule broken, empty when the certificate may sign
     * @param chain
     *            how far the chain builds
     * @param notAfter
     *            the certificate's expiry
     * @param daysLeft
     *            whole days until {@code notAfter}, negative once expired
     * @param issuedTo
     *            the addresses the certificate names, for messages
     */
    public record Result(List<Problem> problems, Chain chain, Instant notAfter, long daysLeft, List<String> issuedTo) {
        public Result {
            problems = List.copyOf(problems);
            issuedTo = List.copyOf(issuedTo);
        }

        /** Whether it may sign. */
        public boolean usable() {
            return problems.isEmpty();
        }

        /** Whether it expires within {@link #EXPIRY_WARNING} (and has not yet). */
        public boolean expiresSoon() {
            return daysLeft >= 0 && daysLeft < EXPIRY_WARNING.toDays();
        }

        /** The problems as one sentence, for a 400 or an owner notice. */
        public String describe() {
            if (problems.isEmpty()) {
                return "The signing certificate is usable.";
            }
            String names = issuedTo.isEmpty() ? null : String.join(", ", issuedTo);
            List<String> parts = new ArrayList<>();
            for (Problem problem : problems) {
                parts.add(problem.text(names));
            }
            return "The signing certificate cannot sign this mailbox's mail: " + String.join("; ", parts) + ".";
        }
    }

    private SmimeCertificateCheck() {
    }

    /**
     * Checks {@code identity}'s certificate for {@code address} at {@code now},
     * against the JDK's trusted roots.
     */
    public static Result check(SmimeIdentity identity, String address, Instant now) {
        return check(identity, address, now, jdkTrustAnchors());
    }

    /** As {@link #check(SmimeIdentity, String, Instant)}, with given roots. */
    public static Result check(SmimeIdentity identity, String address, Instant now, Set<TrustAnchor> trustAnchors) {
        X509Certificate leaf = identity.certificate();
        List<Problem> problems = new ArrayList<>();
        List<String> names = emailAddresses(leaf);
        String wanted = address == null ? null : AddressNormalizer.tryNormalize(address).orElse(address.toLowerCase(Locale.ROOT));
        boolean matched = false;
        for (String name : names) {
            String normalized = AddressNormalizer.tryNormalize(name).orElse(name.toLowerCase(Locale.ROOT));
            if (normalized.equals(wanted)) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            problems.add(Problem.ADDRESS_MISMATCH);
        }
        boolean[] keyUsage = leaf.getKeyUsage();
        // Bit 0 is digitalSignature, bit 1 nonRepudiation (RFC 5280 §4.2.1.3).
        if (keyUsage != null && !(keyUsage.length > 0 && keyUsage[0]) && !(keyUsage.length > 1 && keyUsage[1])) {
            problems.add(Problem.KEY_USAGE);
        }
        try {
            List<String> extended = leaf.getExtendedKeyUsage();
            if (extended != null && !extended.contains(EKU_EMAIL_PROTECTION) && !extended.contains(EKU_ANY)) {
                problems.add(Problem.EXTENDED_KEY_USAGE);
            }
        } catch (CertificateParsingException e) {
            problems.add(Problem.EXTENDED_KEY_USAGE);
        }
        Instant notBefore = leaf.getNotBefore().toInstant();
        Instant notAfter = leaf.getNotAfter().toInstant();
        if (now.isBefore(notBefore)) {
            problems.add(Problem.NOT_YET_VALID);
        }
        if (now.isAfter(notAfter)) {
            problems.add(Problem.EXPIRED);
        }
        Chain chain = chainOf(identity.chain(), now, trustAnchors);
        if (chain == Chain.BROKEN) {
            problems.add(Problem.CHAIN);
        }
        long daysLeft = Math.floorDiv(Duration.between(now, notAfter).toSeconds(), Duration.ofDays(1).toSeconds());
        return new Result(problems, chain, notAfter, daysLeft, names);
    }

    /**
     * The e-mail addresses a certificate names: its {@code rfc822Name} SANs, or,
     * when it has none, the subject's {@code E=} attribute.
     */
    public static List<String> emailAddresses(X509Certificate certificate) {
        List<String> names = new ArrayList<>();
        try {
            Collection<List<?>> alternatives = certificate.getSubjectAlternativeNames();
            if (alternatives != null) {
                for (List<?> entry : alternatives) {
                    if (entry.size() >= 2 && entry.get(0) instanceof Integer type && type == SAN_RFC822 && entry.get(1) instanceof String value) {
                        names.add(value.trim());
                    }
                }
            }
        } catch (CertificateParsingException e) {
            // An unparseable SAN extension names nobody.
        }
        if (names.isEmpty()) {
            // Read the PKCS#9 attribute from the structured subject, not from its
            // RFC 2253 rendering, which escapes characters such as '+' and joins the
            // attributes of a multi-valued RDN.
            try {
                for (RDN rdn : X500Name.getInstance(certificate.getSubjectX500Principal().getEncoded()).getRDNs(BCStyle.EmailAddress)) {
                    for (AttributeTypeAndValue pair : rdn.getTypesAndValues()) {
                        if (BCStyle.EmailAddress.equals(pair.getType()) && pair.getValue() instanceof ASN1String text) {
                            names.add(text.getString().trim());
                        }
                    }
                }
            } catch (RuntimeException e) {
                // An unparseable subject names nobody.
            }
        }
        return names;
    }

    /** Whether {@code certificate} carries the public half of {@code key}. */
    static boolean matches(PrivateKey key, X509Certificate certificate) {
        PublicKey publicKey = certificate.getPublicKey();
        if (key instanceof RSAPrivateCrtKey rsa && publicKey instanceof RSAPublicKey rsaPublic) {
            return rsa.getModulus().equals(rsaPublic.getModulus()) && rsa.getPublicExponent().equals(rsaPublic.getPublicExponent());
        }
        if (key instanceof ECPrivateKey ec && publicKey instanceof ECPublicKey ecPublic) {
            // The public point cannot be read off an EC private key through the JCA, so a
            // probe signature settles it.
            return ec.getParams().getCurve().equals(ecPublic.getParams().getCurve()) && signsFor(key, publicKey, false);
        }
        if (key.getAlgorithm().equals(publicKey.getAlgorithm())) {
            return signsFor(key, publicKey, "RSA".equals(key.getAlgorithm()));
        }
        return false;
    }

    /**
     * The probe signature for the key family; a literal per branch keeps the
     * algorithm inventory checkable.
     */
    private static Signature newProbeSignature(boolean rsa) throws GeneralSecurityException {
        return rsa ? Signature.getInstance("SHA256withRSA") : Signature.getInstance("SHA256withECDSA");
    }

    private static boolean signsFor(PrivateKey key, PublicKey publicKey, boolean rsa) {
        try {
            byte[] probe = "eddi-smime-key-probe".getBytes(StandardCharsets.US_ASCII);
            Signature signer = newProbeSignature(rsa);
            signer.initSign(key);
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = newProbeSignature(rsa);
            verifier.initVerify(publicKey);
            verifier.update(probe);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * How far {@code chain} (leaf first) builds at {@code now} against the JDK's
     * trusted roots — for the certificates a received signature carries.
     */
    public static Chain chainOf(List<X509Certificate> chain, Instant now) {
        return chainOf(chain, now, jdkTrustAnchors());
    }

    static Chain chainOf(List<X509Certificate> chain, Instant now, Set<TrustAnchor> trustAnchors) {
        X509Certificate leaf = chain.getFirst();
        if (trustAnchors != null && !trustAnchors.isEmpty()) {
            try {
                X509CertSelector target = new X509CertSelector();
                target.setCertificate(leaf);
                PKIXBuilderParameters parameters = new PKIXBuilderParameters(trustAnchors, target);
                parameters.setRevocationEnabled(false);
                parameters.setDate(Date.from(now));
                parameters.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(chain)));
                CertPathBuilder.getInstance("PKIX").build(parameters);
                return Chain.TRUSTED;
            } catch (GeneralSecurityException e) {
                // Not to a public root; try the shipped chain on its own.
            }
        }
        return internalChain(chain, now);
    }

    /**
     * The chain judged on its own: walked from the leaf, each certificate verified
     * with the key of the certificate in the list whose subject is its issuer.
     * <ul>
     * <li>{@link Chain#COMPLETE}: the walk ends at a self-signed root in the
     * list;</li>
     * <li>{@link Chain#UNTRUSTED_ROOT}: it passed at least one intermediate and
     * ends at a certificate whose issuer no certificate in the list is;</li>
     * <li>{@link Chain#BROKEN}: a certificate whose issuer <em>is</em> in the list
     * does not verify with its key, a self-signed certificate does not verify with
     * its own, the walk loops, or the leaf's issuer is absent and no intermediate
     * was shipped.</li>
     * </ul>
     */
    static Chain internalChain(List<X509Certificate> chain, Instant now) {
        return walk(chain.getFirst(), chain, new HashSet<>(), now, 0);
    }

    /**
     * One step of the walk. Every candidate issuer is tried, so the verdict is the
     * best path the bundle holds and does not depend on the order of its
     * certificates.
     */
    private static Chain walk(X509Certificate current, List<X509Certificate> chain, Set<X509Certificate> path, Instant now, int below) {
        if (!path.add(current)) {
            return Chain.BROKEN;
        }
        try {
            if (current.getIssuerX500Principal().equals(current.getSubjectX500Principal())) {
                return verifies(current, current.getPublicKey()) ? Chain.COMPLETE : Chain.BROKEN;
            }
            // The CAs between the leaf and any issuer of this certificate: those below it,
            // and it, unless it is the leaf.
            int under = current == chain.getFirst() || selfIssued(current) ? below : below + 1;
            boolean issuerShipped = false;
            Chain best = Chain.BROKEN;
            for (X509Certificate candidate : chain) {
                if (candidate == current || !candidate.getSubjectX500Principal().equals(current.getIssuerX500Principal())) {
                    continue;
                }
                issuerShipped = true;
                if (!mayIssue(candidate, now, under) || !verifies(current, candidate.getPublicKey())) {
                    continue;
                }
                Chain next = walk(candidate, chain, path, now, under);
                if (next == Chain.COMPLETE) {
                    return Chain.COMPLETE;
                }
                if (next == Chain.UNTRUSTED_ROOT) {
                    best = Chain.UNTRUSTED_ROOT;
                }
            }
            if (best != Chain.BROKEN || issuerShipped) {
                return best; // the issuer is in the bundle: it either leads somewhere or the chain is broken
            }
            // The root is not shipped: fine after an intermediate, not for a lone leaf.
            return current == chain.getFirst() ? Chain.BROKEN : Chain.UNTRUSTED_ROOT;
        } finally {
            path.remove(current);
        }
    }

    private static boolean selfIssued(X509Certificate certificate) {
        return certificate.getIssuerX500Principal().equals(certificate.getSubjectX500Principal());
    }

    /**
     * Whether a shipped certificate may vouch for another at {@code now}: valid
     * then, a CA whose path length constraint allows {@code below} CAs under it,
     * and, when it states key usage, allowed to sign certificates. A version 1
     * certificate has no extensions to read, so a self-signed one passes as the
     * root it is.
     */
    private static boolean mayIssue(X509Certificate issuer, Instant now, int below) {
        Date at = Date.from(now);
        if (at.before(issuer.getNotBefore()) || at.after(issuer.getNotAfter())) {
            return false;
        }
        boolean legacyRoot = issuer.getVersion() == 1 && issuer.getIssuerX500Principal().equals(issuer.getSubjectX500Principal());
        int pathLength = issuer.getBasicConstraints(); // -1: not a CA; Integer.MAX_VALUE: no limit
        if (pathLength < 0 && !legacyRoot) {
            return false;
        }
        if (pathLength >= 0 && below > pathLength) {
            return false; // RFC 5280 4.2.1.9: more CAs between it and the leaf than its pathLenConstraint
                          // allows
        }
        boolean[] usage = issuer.getKeyUsage();
        return usage == null || (usage.length > KEY_USAGE_KEY_CERT_SIGN && usage[KEY_USAGE_KEY_CERT_SIGN]);
    }

    private static boolean verifies(X509Certificate certificate, PublicKey key) {
        try {
            certificate.verify(key);
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static volatile Set<TrustAnchor> jdkAnchors;

    /** The JDK's default trusted roots, read once. Empty when unreadable. */
    static Set<TrustAnchor> jdkTrustAnchors() {
        Set<TrustAnchor> anchors = jdkAnchors;
        if (anchors != null) {
            return anchors;
        }
        Set<TrustAnchor> found = new HashSet<>();
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (var manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) {
                    for (X509Certificate root : x509.getAcceptedIssuers()) {
                        found.add(new TrustAnchor(root, null));
                    }
                }
            }
        } catch (GeneralSecurityException e) {
            // No trust store: every chain is judged on its own.
        }
        jdkAnchors = Set.copyOf(found);
        return jdkAnchors;
    }
}
