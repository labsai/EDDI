/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLContextSpi;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.SecureRandom;

/**
 * An {@link SSLContext} whose client sockets and engines verify the server's
 * host name against its certificate.
 * <p>
 * jnats upgrades its TCP socket with {@code sslContext.getSocketFactory()} and
 * starts the handshake without setting an endpoint identification algorithm, so
 * the JDK checks only that the broker's certificate chains to the trust store —
 * not that it was issued for the broker it dialled. Any certificate the trust
 * store accepts, issued for any host, then authenticated an impersonating
 * broker, which received the node's CONNECT credentials. This wrapper sets
 * {@code endpointIdentificationAlgorithm=HTTPS} on every socket and engine
 * before it is handed back, the same check an HTTPS client makes.
 */
final class HostnameVerifyingSslContext extends SSLContext {

    static final String ENDPOINT_IDENTIFICATION = "HTTPS";

    HostnameVerifyingSslContext(SSLContext delegate) {
        super(new Spi(delegate), delegate.getProvider(), delegate.getProtocol());
    }

    static <T extends Socket> T verifying(T socket) {
        if (socket instanceof SSLSocket ssl) {
            SSLParameters parameters = ssl.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm(ENDPOINT_IDENTIFICATION);
            ssl.setSSLParameters(parameters);
        }
        return socket;
    }

    private static SSLEngine verifying(SSLEngine engine) {
        SSLParameters parameters = engine.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm(ENDPOINT_IDENTIFICATION);
        engine.setSSLParameters(parameters);
        return engine;
    }

    private static final class Spi extends SSLContextSpi {
        private final SSLContext delegate;

        Spi(SSLContext delegate) {
            this.delegate = delegate;
        }

        @Override
        protected void engineInit(KeyManager[] km, TrustManager[] tm, SecureRandom random) {
            throw new IllegalStateException("already initialised");
        }

        @Override
        protected SSLSocketFactory engineGetSocketFactory() {
            return new VerifyingSocketFactory(delegate.getSocketFactory());
        }

        @Override
        protected SSLServerSocketFactory engineGetServerSocketFactory() {
            return delegate.getServerSocketFactory();
        }

        @Override
        protected SSLEngine engineCreateSSLEngine() {
            return delegate.createSSLEngine();
        }

        @Override
        protected SSLEngine engineCreateSSLEngine(String host, int port) {
            return verifying(delegate.createSSLEngine(host, port));
        }

        @Override
        protected SSLSessionContext engineGetServerSessionContext() {
            return delegate.getServerSessionContext();
        }

        @Override
        protected SSLSessionContext engineGetClientSessionContext() {
            return delegate.getClientSessionContext();
        }

        @Override
        protected SSLParameters engineGetDefaultSSLParameters() {
            return delegate.getDefaultSSLParameters();
        }

        @Override
        protected SSLParameters engineGetSupportedSSLParameters() {
            return delegate.getSupportedSSLParameters();
        }
    }

    static final class VerifyingSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        VerifyingSocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket() throws IOException {
            return verifying(delegate.createSocket());
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return verifying(delegate.createSocket(socket, host, port, autoClose));
        }

        @Override
        public Socket createSocket(Socket socket, InputStream consumed, boolean autoClose) throws IOException {
            return verifying(delegate.createSocket(socket, consumed, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return verifying(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return verifying(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return verifying(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return verifying(delegate.createSocket(address, port, localAddress, localPort));
        }
    }
}
