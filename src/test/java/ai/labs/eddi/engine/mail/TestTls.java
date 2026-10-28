/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A throwaway TLS identity for {@code localhost} / {@code 127.0.0.1}, made once
 * per JVM with the JDK's own {@code keytool} (no test dependency on a crypto
 * library), and the client trust that accepts exactly it.
 * <p>
 * The mail client's TLS layer is Angus Mail's, which wraps the pinned socket
 * with the JVM's default {@code SSLSocketFactory}; {@link #trustingDefault}
 * swaps the default context for the duration of a test so that layer — the
 * production code path, with certificate and host-name checks on — accepts this
 * certificate and nothing else.
 */
public final class TestTls {

    public static final String PASSWORD = "changeit";

    private static Path keystore;

    private TestTls() {
    }

    /** The PKCS12 keystore holding the server key and certificate. */
    public static synchronized Path keystore() throws IOException, InterruptedException {
        if (keystore != null) {
            return keystore;
        }
        Path dir = Files.createTempDirectory("eddi-mail-tls");
        Path file = dir.resolve("server.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "keytool.exe" : "keytool").toString();
        Process process = new ProcessBuilder(List.of(keytool, "-genkeypair", "-alias", "mail", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-storetype", "PKCS12", "-keystore",
                file.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD)).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + new String(output));
        }
        file.toFile().deleteOnExit();
        dir.toFile().deleteOnExit();
        keystore = file;
        return file;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static KeyStore load() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore())) {
            store.load(in, PASSWORD.toCharArray());
        }
        return store;
    }

    /** A server-side context presenting the test certificate. */
    public static SSLContext serverContext() throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(load(), PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }

    /** A client context that trusts the test certificate only. */
    public static SSLContext clientContext() throws Exception {
        Certificate certificate = load().getCertificate("mail");
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("mail", certificate);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    /** Runs {@code body} with {@link #clientContext()} as the JVM default. */
    public static <T> T trustingDefault(ThrowingSupplier<T> body) throws Exception {
        SSLContext previous = SSLContext.getDefault();
        SSLContext.setDefault(clientContext());
        try {
            return body.get();
        } finally {
            SSLContext.setDefault(previous);
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
