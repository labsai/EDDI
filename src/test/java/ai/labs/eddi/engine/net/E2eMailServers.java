/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.net;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shared state of the full-application email IT: the mail test hosts, the ports
 * of the GreenMail servers standing in for them, and a TLS identity for those
 * host names. The test class and {@link E2eMailSocketConnector} run in the same
 * Quarkus class loader, so the statics are one.
 * <p>
 * The host names are real DNS names under {@code .test} (RFC 6761), resolved by
 * the connector's injected resolver to a public documentation address, so the
 * author policy — names only, TLS ports, no private address — checks them as it
 * would any host. Only after it has, the endpoint is pinned to the loopback
 * GreenMail port (the same substitution {@link SafeSocketTestSupport} makes for
 * the hand-wired ITs).
 */
public final class E2eMailServers {

    public static final String IMAP_HOST = "imap.mail.test";
    public static final String SMTP_HOST = "smtp.mail.test";
    public static final String PASSWORD = "changeit";

    /** What the test DNS answers for the test hosts: TEST-NET-3, a public range. */
    static final byte[] PUBLIC_ADDRESS = {(byte) 203, 0, 113, 10};

    public static volatile int imapsPort = -1;
    public static volatile int smtpsPort = -1;

    private static Path keystore;
    private static SSLContext clientContext;

    private E2eMailServers() {
    }

    static boolean isTestHost(String host) {
        return IMAP_HOST.equals(host) || SMTP_HOST.equals(host);
    }

    static InetAddress[] resolve(String host) throws UnknownHostException {
        if (isTestHost(host)) {
            return new InetAddress[]{InetAddress.getByAddress(host, PUBLIC_ADDRESS)};
        }
        return InetAddress.getAllByName(host);
    }

    /** A PKCS12 keystore with a certificate for the test hosts and localhost. */
    public static synchronized Path keystore() throws Exception {
        if (keystore != null) {
            return keystore;
        }
        Path dir = Files.createTempDirectory("eddi-e2e-mail-tls");
        Path file = dir.resolve("server.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        Process process = new ProcessBuilder(List.of(keytool, "-genkeypair", "-alias", "mail", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=" + IMAP_HOST, "-ext", "SAN=dns:" + IMAP_HOST + ",dns:" + SMTP_HOST + ",dns:localhost,ip:127.0.0.1",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", file.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD))
                .redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + new String(output));
        }
        file.toFile().deleteOnExit();
        dir.toFile().deleteOnExit();
        keystore = file;
        return file;
    }

    /**
     * A client context trusting the JDK's CA certificates plus the test certificate
     * — verification stays on, host names included.
     */
    public static synchronized SSLContext clientContext() throws Exception {
        if (clientContext != null) {
            return clientContext;
        }
        KeyStore server = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore())) {
            server.load(in, PASSWORD.toCharArray());
        }
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        Path cacerts = Path.of(System.getProperty("java.home"), "lib", "security", "cacerts");
        try (InputStream in = Files.newInputStream(cacerts)) {
            trust.load(in, "changeit".toCharArray());
        }
        trust.setCertificateEntry("eddi-e2e-mail", server.getCertificate("mail"));
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        clientContext = context;
        return context;
    }
}
