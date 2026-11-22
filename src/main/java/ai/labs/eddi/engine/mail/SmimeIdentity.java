/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An S/MIME signing identity: a private key and its certificate chain, leaf
 * first (email channel v3 plan §6.2).
 * <p>
 * Loaded from what an {@code SMIME_SIGNING} connection's vault reference
 * resolves to: a PKCS#12 keystore, base64-encoded, or PEM text holding the
 * private key (PKCS#8, PKCS#1 or SEC1, optionally encrypted) and the
 * certificates. The first certificate whose public key matches the private key
 * is the leaf; the others follow as the chain, in the order given.
 * <p>
 * No message of a {@link IllegalArgumentException} thrown here quotes the
 * material: it is a private key.
 */
public record SmimeIdentity(PrivateKey privateKey, List<X509Certificate> chain) {

    /** Keystore and PEM inputs larger than this are refused before parsing. */
    static final int MAX_MATERIAL_CHARS = 256 * 1024;

    /** At most this many certificates are read from one input. */
    static final int MAX_CERTIFICATES = 10;

    public SmimeIdentity {
        Objects.requireNonNull(privateKey, "privateKey");
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("An S/MIME identity needs its certificate.");
        }
        chain = List.copyOf(chain);
    }

    /** The signer's own certificate. */
    public X509Certificate certificate() {
        return chain.getFirst();
    }

    /** No key material in log lines. */
    @Override
    public String toString() {
        return "SmimeIdentity[subject=" + certificate().getSubjectX500Principal().getName() + "]";
    }

    /**
     * Loads an identity from PEM text or a base64 PKCS#12 keystore.
     *
     * @param material
     *            the resolved {@code smimeSigning.certificate}
     * @param password
     *            the resolved {@code smimeSigning.password}, or null
     * @throws IllegalArgumentException
     *             when the material cannot be read, holds no private key, or no
     *             certificate matches the key — in words that never quote it
     */
    public static SmimeIdentity load(String material, String password) {
        if (material == null || material.isBlank()) {
            throw new IllegalArgumentException("The signing certificate is empty.");
        }
        if (material.length() > MAX_MATERIAL_CHARS) {
            throw new IllegalArgumentException("The signing certificate is larger than " + MAX_MATERIAL_CHARS + " characters.");
        }
        char[] secret = password == null ? new char[0] : password.toCharArray();
        return material.contains("-----BEGIN") ? fromPem(material, secret) : fromPkcs12(material, secret);
    }

    private static SmimeIdentity fromPkcs12(String base64, char[] password) {
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The signing certificate is neither PEM text nor a base64 PKCS#12 keystore.");
        }
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(new ByteArrayInputStream(bytes), password);
            for (String alias : Collections.list(store.aliases())) {
                if (!store.isKeyEntry(alias)) {
                    continue;
                }
                Key key = store.getKey(alias, password);
                Certificate[] certificates = store.getCertificateChain(alias);
                if (key instanceof PrivateKey privateKey && certificates != null && certificates.length > 0) {
                    List<X509Certificate> chain = new ArrayList<>();
                    for (Certificate certificate : certificates) {
                        if (certificate instanceof X509Certificate x509 && chain.size() < MAX_CERTIFICATES) {
                            chain.add(x509);
                        }
                    }
                    return new SmimeIdentity(privateKey, orderLeafFirst(privateKey, chain));
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("The PKCS#12 keystore could not be opened: the password is wrong, or the data is not a "
                    + "PKCS#12 keystore.");
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("The PKCS#12 keystore could not be read (" + e.getClass().getSimpleName() + ").");
        }
        throw new IllegalArgumentException("The PKCS#12 keystore holds no private key with a certificate.");
    }

    private static SmimeIdentity fromPem(String pem, char[] password) {
        PrivateKey privateKey = null;
        List<X509Certificate> certificates = new ArrayList<>();
        JcaPEMKeyConverter keys = new JcaPEMKeyConverter();
        JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object item;
            int items = 0;
            while ((item = parser.readObject()) != null && items++ < MAX_CERTIFICATES + 2) {
                if (item instanceof X509CertificateHolder holder) {
                    if (certificates.size() < MAX_CERTIFICATES) {
                        certificates.add(converter.getCertificate(holder));
                    }
                } else if (privateKey == null) {
                    privateKey = privateKeyOf(item, password, keys);
                }
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalArgumentException("The PEM signing certificate could not be read (" + e.getClass().getSimpleName() + ").");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The PEM signing certificate could not be read: the key password is wrong or the key is "
                    + "damaged.");
        }
        if (privateKey == null) {
            throw new IllegalArgumentException("The PEM signing certificate holds no private key. Put the key and the certificate chain into "
                    + "one PEM text.");
        }
        if (certificates.isEmpty()) {
            throw new IllegalArgumentException("The PEM signing certificate holds no certificate.");
        }
        return new SmimeIdentity(privateKey, orderLeafFirst(privateKey, certificates));
    }

    private static PrivateKey privateKeyOf(Object item, char[] password, JcaPEMKeyConverter keys) throws IOException {
        try {
            if (item instanceof PEMKeyPair pair) {
                return keys.getKeyPair(pair).getPrivate();
            }
            if (item instanceof PrivateKeyInfo info) {
                return keys.getPrivateKey(info);
            }
            if (item instanceof PEMEncryptedKeyPair encrypted) {
                return keys.getKeyPair(encrypted.decryptKeyPair(new JcePEMDecryptorProviderBuilder().build(password))).getPrivate();
            }
            if (item instanceof PKCS8EncryptedPrivateKeyInfo encrypted) {
                return keys.getPrivateKey(encrypted.decryptPrivateKeyInfo(
                        new JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider(new BouncyCastleProvider()).build(password)));
            }
        } catch (OperatorCreationException | PKCSException e) {
            throw new IllegalArgumentException("The encrypted PEM key could not be decrypted: the password is wrong.");
        }
        return null;
    }

    /**
     * The certificate matching {@code key} first, the rest in their given order.
     *
     * @throws IllegalArgumentException
     *             when no certificate belongs to the key
     */
    private static List<X509Certificate> orderLeafFirst(PrivateKey key, List<X509Certificate> certificates) {
        X509Certificate leaf = null;
        for (X509Certificate certificate : certificates) {
            if (SmimeCertificateCheck.matches(key, certificate)) {
                leaf = certificate;
                break;
            }
        }
        if (leaf == null) {
            throw new IllegalArgumentException("No certificate in the signing material belongs to its private key.");
        }
        List<X509Certificate> ordered = new ArrayList<>();
        ordered.add(leaf);
        for (X509Certificate certificate : certificates) {
            if (certificate != leaf) {
                ordered.add(certificate);
            }
        }
        return ordered;
    }
}
