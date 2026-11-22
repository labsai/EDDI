/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.model;

/**
 * The {@link AuthType#SMIME_SIGNING} half of a connection: the certificate and
 * private key an email integration signs its outgoing mail with (email channel
 * v3 plan §6.2).
 * <p>
 * Both fields are <b>reference-only</b>, as every secret-bearing field of a
 * connection: {@code ${vault:…}} (or a {@code ${vars:…}} whose variable holds
 * one). The certificate reference resolves to either a PKCS#12 keystore,
 * base64-encoded, or PEM text holding the private key followed by the
 * certificate chain (leaf first). The password unlocks the PKCS#12 keystore or
 * an encrypted PEM key; it is optional.
 * <p>
 * Resolved only by the email transport, never into an HTTP header: the generic
 * resolution paths refuse a connection of this type.
 *
 * @since 6.7.0
 */
public class SmimeSigningConfig {

    /**
     * {@code ${vault:…}}: a base64 PKCS#12 keystore, or PEM private key plus chain.
     */
    private String certificate;

    /** {@code ${vault:…}}: the keystore or key password. Optional. */
    private String password;

    public String getCertificate() {
        return certificate;
    }

    public void setCertificate(String certificate) {
        this.certificate = certificate;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
