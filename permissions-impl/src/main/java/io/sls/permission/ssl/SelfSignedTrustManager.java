package io.sls.permission.ssl;

/**
 * Copyright by Spoken Language System. All rights reserved.
 * User: jarisch
 * Date: 28.01.13
 * Time: 13:22
 */

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * This Trust Manager is "naive" because it trusts everyone.
 */
public class SelfSignedTrustManager implements X509TrustManager {
    /**
     * Doesn't throw an exception, so this is how it approves a certificate.
     *
     * @see javax.net.ssl.X509TrustManager#checkClientTrusted(java.security.cert.X509Certificate[], String)
     */
    public void checkClientTrusted(X509Certificate[] cert, String authType)
            throws CertificateException {
    }

    /**
     * Doesn't throw an exception, so this is how it approves a certificate.
     *
     * @see javax.net.ssl.X509TrustManager#checkServerTrusted(java.security.cert.X509Certificate[], String)
     */
    public void checkServerTrusted(X509Certificate[] cert, String authType)
            throws CertificateException {
    }

    /**
     * @see javax.net.ssl.X509TrustManager#getAcceptedIssuers()
     */
    public X509Certificate[] getAcceptedIssuers() {
        return null;  // I've seen someone return new X509Certificate[ 0 ];
    }
}
