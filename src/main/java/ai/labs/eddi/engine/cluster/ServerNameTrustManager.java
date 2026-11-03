/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Trusts a NATS server certificate only if the trust store accepts its chain
 * AND it names one of the NATS hosts EDDI was configured with.
 * <p>
 * Endpoint identification on the socket cannot do this for NATS: jnats resolves
 * every server host name to its addresses and dials — and hands the TLS layer —
 * the bare IP, so an HTTPS-style check compared the certificate with an IP
 * address and refused every certificate issued for a host name. (jnats'
 * {@code Unresolved} mode, which would keep the name, then fails to connect at
 * all.) This check uses the configured names instead: a certificate for some
 * other host — what a man in the middle with any CA-signed certificate holds —
 * is refused, a certificate for the configured {@code nats.example.com}, or a
 * {@code *.example.com} wildcard, is accepted on whichever of its addresses
 * jnats dials. An IP address in {@code eddi.nats.url} needs an IP SAN.
 */
final class ServerNameTrustManager extends X509ExtendedTrustManager {

    private final X509ExtendedTrustManager delegate;
    private final List<String> names;

    ServerNameTrustManager(X509ExtendedTrustManager delegate, List<String> names) {
        this.delegate = delegate;
        this.names = List.copyOf(names);
    }

    /**
     * The host of every server URL ({@code tls://host:4222}, or a bare
     * {@code host:port}).
     */
    static List<String> hostsOf(List<String> servers) {
        List<String> hosts = new ArrayList<>();
        for (String server : servers) {
            String url = server.contains("://") ? server : "nats://" + server;
            String host = URI.create(url.trim()).getHost();
            if (host != null && !host.isBlank()) {
                hosts.add(host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host);
            }
        }
        return hosts;
    }

    private void verifyName(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("The NATS server presented no certificate");
        }
        for (String name : names) {
            if (matches(chain[0], name)) {
                return;
            }
        }
        throw new CertificateException("The NATS server certificate (" + chain[0].getSubjectX500Principal().getName()
                + ") names none of the configured NATS hosts " + names + " — eddi.nats.tls.verify-hostname");
    }

    /**
     * Whether {@code certificate} names {@code host}: an IP SAN for an IP, a DNS
     * SAN (one leading wildcard label) otherwise.
     */
    static boolean matches(X509Certificate certificate, String host) throws CertificateParsingException {
        Collection<List<?>> alternativeNames = certificate.getSubjectAlternativeNames();
        if (alternativeNames == null) {
            return false;
        }
        boolean ip = isIpLiteral(host);
        for (List<?> entry : alternativeNames) {
            int type = (Integer) entry.get(0);
            String value = String.valueOf(entry.get(1));
            if (ip && type == 7 && sameAddress(host, value)) {
                return true;
            }
            if (!ip && type == 2 && dnsMatches(value, host)) {
                return true;
            }
        }
        return false;
    }

    private static boolean dnsMatches(String pattern, String host) {
        String p = pattern.toLowerCase(Locale.ROOT);
        String h = host.toLowerCase(Locale.ROOT);
        if (p.endsWith(".")) {
            p = p.substring(0, p.length() - 1);
        }
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (!p.startsWith("*.")) {
            return p.equals(h);
        }
        int dot = h.indexOf('.');
        // The wildcard stands for exactly one label, never for a whole name. A single
        // label after it is allowed: *.<release>-nats is how an in-cluster headless
        // Service is covered.
        return dot > 0 && h.substring(dot).equals(p.substring(1));
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private static boolean sameAddress(String a, String b) {
        try {
            // Both are literals, so nothing is looked up.
            return InetAddress.getByName(a).equals(InetAddress.getByName(b));
        } catch (UnknownHostException e) {
            return false;
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        delegate.checkServerTrusted(chain, authType, socket);
        verifyName(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        delegate.checkServerTrusted(chain, authType, engine);
        verifyName(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
        verifyName(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        delegate.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        delegate.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }
}
