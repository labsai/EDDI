/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.net;

import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.net.SocketFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The only way EDDI opens a raw TCP connection to a host a config author named:
 * the IMAP and SMTP servers of mail channels and mail RAG sources (email plan
 * §7.1 "Sockets", RAG plan §3.10).
 * <p>
 * {@code OutboundHttp} guards HTTP. IMAP and SMTP are raw sockets it does not
 * cover, and a host an author may type into a channel is otherwise an internal
 * port scanner — "connect to 10.0.0.5:993" answers whether that host exists.
 * So, for every connection:
 * <ol>
 * <li><b>The host is a DNS name.</b> IP literals, including the decimal, octal
 * and hex spellings resolvers accept ({@code 2130706433}), are refused: a mail
 * server has a name, and TLS validates that name.</li>
 * <li><b>Allowed ports only</b> — IMAP 993 (TLS) or 143 (STARTTLS, required),
 * SMTP 465 (TLS) or 587 (STARTTLS, required). No port 25, no plaintext.</li>
 * <li><b>{@code eddi.email.allowed-hosts}</b>, when set, must list the host
 * ({@code host} or {@code *.domain}). Empty means any public host.</li>
 * <li><b>Resolved once, every address checked</b> with
 * {@link UrlValidationUtils#isPrivateAddress} — the one definition of "unsafe
 * outbound address" in the codebase, so loopback, RFC 1918, ULA, CGNAT,
 * link-local, cloud metadata, multicast, unspecified and every IPv6 embedding
 * of those are refused exactly as for HTTP. Known internal names
 * ({@code localhost}, {@code *.internal}, metadata hosts) are refused before
 * any lookup.</li>
 * <li><b>Pinned.</b> The socket connects to the first address of that one
 * resolution, whatever a later lookup says ({@link PinnedSocket}).</li>
 * <li><b>TLS verified</b>: certificate chain against the JVM trust store and
 * the host name against the certificate, always. There is no switch to turn
 * either off.</li>
 * <li><b>Timeouts</b> on connect and on every read.</li>
 * </ol>
 * There is deliberately no exception for private addresses, not even through
 * the allowlist: an on-premises mail server on a private network is a real
 * deployment, but the decision to allow one belongs in its own, explicit
 * setting, not in a side effect of naming a host.
 * <h2>Two policies</h2> Everything above is {@link Policy#AUTHOR}: the rules
 * for a host a config author typed into a channel or a knowledge source, which
 * {@link #resolve} applies. {@link Policy#OPERATOR} is for a host the
 * deployment's operator set in {@code application.properties} — today only the
 * system mailer's {@code eddi.mail.smtp.host} — and is reached only through
 * {@link #resolveOperator}. The operator already controls the network the
 * process runs in, so the rules that stop an author from probing it do not
 * apply: a private or loopback address (an on-premises relay, a Postfix
 * sidecar), an IP literal, a single-label name and any port, including 25, are
 * allowed, and {@code eddi.email.allowed-hosts}, which confines authors, is not
 * consulted. What stays: the host is resolved once and the socket pinned to
 * that address; cloud metadata and other link-local addresses are refused (no
 * mail server lives there, and a typo should not reach one); TLS is verified as
 * for authors; and the TLS mode is the operator's explicit choice — including
 * {@link TlsMode#PLAINTEXT}, which no author endpoint can ever carry.
 * <p>
 * The transports of later phases use {@link #angusProperties(Endpoint)} to give
 * Angus Mail this connector's socket factory; nothing else in EDDI should open
 * mail sockets.
 */
@ApplicationScoped
public class SafeSocketConnector {

    /**
     * The mail protocols this connector serves, with the property prefix Angus Mail
     * reads.
     */
    public enum Protocol {
        IMAP("mail.imap", 993, 143), SMTP("mail.smtp", 465, 587);

        private final String propertyPrefix;
        private final int implicitTlsPort;
        private final int startTlsPort;

        Protocol(String propertyPrefix, int implicitTlsPort, int startTlsPort) {
            this.propertyPrefix = propertyPrefix;
            this.implicitTlsPort = implicitTlsPort;
            this.startTlsPort = startTlsPort;
        }

        /** {@code mail.imap} / {@code mail.smtp}. */
        public String propertyPrefix() {
            return propertyPrefix;
        }

        /** The TLS mode of an allowed port, or null for any other port. */
        TlsMode tlsModeOf(int port) {
            if (port == implicitTlsPort) {
                return TlsMode.IMPLICIT;
            }
            return port == startTlsPort ? TlsMode.STARTTLS : null;
        }
    }

    /** How TLS starts on a connection. */
    public enum TlsMode {
        /** TLS from the first byte (993, 465). */
        IMPLICIT,
        /** Plaintext greeting, then {@code STARTTLS}, which is required (143, 587). */
        STARTTLS,
        /**
         * No TLS at all. Only {@link #resolveOperator} creates an endpoint with this
         * mode, and only because the operator chose it explicitly — never an author
         * endpoint, whatever its port.
         */
        PLAINTEXT
    }

    /** Whose configuration named the host — which decides the rules that apply. */
    public enum Policy {
        /**
         * A config author (channels, knowledge sources): DNS names only, the protocol's
         * TLS ports, {@code eddi.email.allowed-hosts}, no private address.
         */
        AUTHOR,
        /**
         * The deployment operator ({@code application.properties}): private and
         * loopback addresses, IP literals and any port allowed; still pinned, still no
         * metadata address, TLS as the operator chose it.
         */
        OPERATOR
    }

    /**
     * A host and port that passed the policy, with the one address it may connect
     * to. Only {@link #resolve} creates one, so holding an {@code Endpoint} is
     * proof of the check.
     */
    public static final class Endpoint {
        private final String host;
        private final int port;
        private final Protocol protocol;
        private final TlsMode tlsMode;
        private final InetAddress address;
        private final Policy policy;

        Endpoint(String host, int port, Protocol protocol, TlsMode tlsMode, InetAddress address) {
            this(host, port, protocol, tlsMode, address, Policy.AUTHOR);
        }

        Endpoint(String host, int port, Protocol protocol, TlsMode tlsMode, InetAddress address, Policy policy) {
            if (tlsMode == TlsMode.PLAINTEXT && policy != Policy.OPERATOR) {
                // The constructor is the last place this can be refused: an Endpoint is
                // proof of the check, so a plaintext author endpoint must not exist.
                throw new IllegalArgumentException("Only an operator endpoint may be plaintext.");
            }
            this.host = host;
            this.port = port;
            this.protocol = protocol;
            this.tlsMode = tlsMode;
            this.address = address;
            this.policy = policy;
        }

        /** The policy the endpoint was vetted under. */
        public Policy policy() {
            return policy;
        }

        /** The normalised (lower-case, ASCII) host name. */
        public String host() {
            return host;
        }

        public int port() {
            return port;
        }

        public Protocol protocol() {
            return protocol;
        }

        public TlsMode tlsMode() {
            return tlsMode;
        }

        /** The pinned address. */
        public InetAddress address() {
            return address;
        }

        /**
         * Host and port only: which internal address a name points at is not for logs.
         */
        @Override
        public String toString() {
            return "Endpoint[" + protocol + " " + host + ":" + port + " " + tlsMode + " " + policy + "]";
        }
    }

    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);

    private static final String[] TLS_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private final List<String> allowedHosts;
    private final UrlValidationUtils.HostResolver resolver;
    private final Predicate<InetAddress> refusedAddress;
    private final SSLContext sslContext;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    @Inject
    public SafeSocketConnector(@ConfigProperty(name = "eddi.email.allowed-hosts") Optional<List<String>> allowedHosts) {
        this(allowedHosts.orElse(List.of()), InetAddress::getAllByName, UrlValidationUtils::isPrivateAddress, defaultSslContext(),
                DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    /**
     * For tests: an injectable resolver (DNS rebinding cases need no network), and
     * an injectable address rule (so a loopback test server can stand in for a mail
     * host — production always uses {@link UrlValidationUtils#isPrivateAddress}).
     */
    SafeSocketConnector(List<String> allowedHosts, UrlValidationUtils.HostResolver resolver, Predicate<InetAddress> refusedAddress,
            SSLContext sslContext, Duration connectTimeout, Duration readTimeout) {
        this.allowedHosts = normalizeAllowlist(allowedHosts);
        this.resolver = resolver;
        this.refusedAddress = refusedAddress;
        this.sslContext = sslContext;
        this.connectTimeoutMillis = Math.toIntExact(connectTimeout.toMillis());
        this.readTimeoutMillis = Math.toIntExact(readTimeout.toMillis());
    }

    private static SSLContext defaultSslContext() {
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("No default TLS context is available", e);
        }
    }

    /**
     * Checks a configured host and port, resolves the host once and returns the
     * vetted endpoint. Safe to call at save time to validate a channel's host.
     *
     * @throws SocketPolicyException
     *             naming the rule the host or port broke
     */
    public Endpoint resolve(String host, int port, Protocol protocol) throws SocketPolicyException {
        String normalized = normalizeHost(host);
        if (UrlValidationUtils.isBlockedHostname(normalized)) {
            throw new SocketPolicyException(SocketPolicyException.Reason.PRIVATE_ADDRESS,
                    "Host " + normalized + " names an internal or local service, which mail connections may not reach.");
        }
        if (!isAllowedHost(normalized)) {
            throw new SocketPolicyException(SocketPolicyException.Reason.HOST_NOT_ALLOWED,
                    "Host " + normalized + " is not on eddi.email.allowed-hosts.");
        }
        TlsMode tlsMode = protocol.tlsModeOf(port);
        if (tlsMode == null) {
            throw new SocketPolicyException(SocketPolicyException.Reason.PORT_NOT_ALLOWED,
                    protocol + " connections may use port " + protocol.implicitTlsPort + " (TLS) or " + protocol.startTlsPort
                            + " (STARTTLS), not " + port + ".");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolveAll(normalized);
        } catch (UnknownHostException e) {
            throw new SocketPolicyException(SocketPolicyException.Reason.UNRESOLVABLE, "Host " + normalized + " does not resolve.");
        }
        if (addresses == null || addresses.length == 0) {
            throw new SocketPolicyException(SocketPolicyException.Reason.UNRESOLVABLE, "Host " + normalized + " does not resolve.");
        }
        for (InetAddress address : addresses) {
            if (refusedAddress.test(address)) {
                // Every address, not only the first: a name with one public and one
                // private A record is a name that may be steered to the private one.
                throw new SocketPolicyException(SocketPolicyException.Reason.PRIVATE_ADDRESS,
                        "Host " + normalized + " resolves to a private, loopback, link-local or metadata address.");
            }
        }
        return new Endpoint(normalized, port, protocol, tlsMode, addresses[0]);
    }

    /**
     * Resolves a host the <b>operator</b> configured ({@link Policy#OPERATOR}): a
     * private, loopback or IP-literal host and any port are allowed, because the
     * operator owns the network this process runs in — but the host is still
     * resolved once and pinned, cloud-metadata and other link-local addresses are
     * still refused, and TLS still verifies the certificate unless the operator
     * chose {@link TlsMode#PLAINTEXT}.
     * <p>
     * Never call this with a host a config author supplied: that is what
     * {@link #resolve} is for. The only caller is the system mailer, whose host is
     * {@code eddi.mail.smtp.host}.
     *
     * @param tlsMode
     *            the operator's explicit choice; for {@code PLAINTEXT} the caller
     *            must already have checked the operator's opt-out
     * @throws SocketPolicyException
     *             naming the rule the host or port broke
     */
    public Endpoint resolveOperator(String host, int port, Protocol protocol, TlsMode tlsMode) throws SocketPolicyException {
        if (tlsMode == null) {
            throw new IllegalArgumentException("An operator endpoint needs an explicit TLS mode.");
        }
        if (port < 1 || port > 65535) {
            throw new SocketPolicyException(SocketPolicyException.Reason.PORT_NOT_ALLOWED, "Port " + port + " is not a TCP port.");
        }
        InetAddress literal = ipLiteral(host);
        String normalized;
        InetAddress[] addresses;
        if (literal != null) {
            normalized = literal.getHostAddress();
            addresses = new InetAddress[]{literal};
        } else {
            normalized = normalizeOperatorHost(host);
            try {
                addresses = resolver.resolveAll(normalized);
            } catch (UnknownHostException e) {
                throw new SocketPolicyException(SocketPolicyException.Reason.UNRESOLVABLE, "Host " + normalized + " does not resolve.");
            }
            if (addresses == null || addresses.length == 0) {
                throw new SocketPolicyException(SocketPolicyException.Reason.UNRESOLVABLE, "Host " + normalized + " does not resolve.");
            }
        }
        for (InetAddress address : addresses) {
            if (UrlValidationUtils.isMetadataAddress(address)) {
                throw new SocketPolicyException(SocketPolicyException.Reason.PRIVATE_ADDRESS,
                        "Host " + normalized + " is, or resolves to, a link-local or cloud-metadata address, where no mail server lives.");
            }
        }
        return new Endpoint(normalized, port, protocol, tlsMode, addresses[0], Policy.OPERATOR);
    }

    /** The address an IP literal spells, or null when {@code host} is not one. */
    private static InetAddress ipLiteral(String host) {
        if (host == null) {
            return null;
        }
        String candidate = host.strip();
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        try {
            // Parses literals only — never a DNS lookup.
            return InetAddress.ofLiteral(candidate);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * An operator host name: lower-case ASCII, trailing dot removed, one label or
     * more ({@code localhost} and a bare {@code relay} are fine).
     */
    static String normalizeOperatorHost(String host) throws SocketPolicyException {
        if (host == null || host.isBlank()) {
            throw invalidHost("Host is empty.");
        }
        String candidate = host.strip();
        if (candidate.endsWith(".")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        String ascii;
        try {
            ascii = IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw invalidHost("Host is not a valid host name.");
        }
        if (ascii.isEmpty() || ascii.length() > 253) {
            throw invalidHost("Host is not a valid host name.");
        }
        for (String label : ascii.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                throw invalidHost("Host is not a valid host name.");
            }
        }
        return ascii;
    }

    /**
     * A plain socket factory whose sockets connect only to the endpoint's pinned
     * address. TLS is layered on top by the caller (or by Angus Mail, see
     * {@link #angusProperties}).
     */
    public SocketFactory socketFactory(Endpoint endpoint) {
        return new PinnedSocketFactory(endpoint, connectTimeoutMillis, readTimeoutMillis);
    }

    /**
     * The Angus Mail session properties for {@code endpoint}: this connector's
     * factory as the socket factory for both plain and TLS connections, no fallback
     * to an unguarded socket, TLS or required STARTTLS, server identity checked,
     * timeouts set. A caller adds its own authentication properties (e.g.
     * {@code mail.imap.auth.mechanisms=XOAUTH2}) and must not add
     * {@code *.ssl.trust}, {@code *.socketFactory.*}, proxy or SOCKS settings.
     */
    public Properties angusProperties(Endpoint endpoint) {
        String prefix = endpoint.protocol().propertyPrefix();
        SocketFactory factory = socketFactory(endpoint);
        Properties properties = new Properties();
        properties.setProperty(prefix + ".host", endpoint.host());
        properties.setProperty(prefix + ".port", Integer.toString(endpoint.port()));
        // The same plain factory for both keys. Angus asks the ssl key first for a
        // TLS connection; were that an SSLSocketFactory, Angus would open its own
        // unpinned Socket and only wrap it — so the factory is a plain one, and the
        // JVM's default (verifying) TLS factory layers on top.
        properties.put(prefix + ".socketFactory", factory);
        properties.put(prefix + ".ssl.socketFactory", factory);
        // Without this, a failure inside the factory — including a refusal — makes
        // Angus retry with a socket of its own.
        properties.setProperty(prefix + ".socketFactory.fallback", "false");
        properties.setProperty(prefix + ".ssl.checkserveridentity", "true");
        properties.setProperty(prefix + ".ssl.protocols", String.join(" ", TLS_PROTOCOLS));
        switch (endpoint.tlsMode()) {
            case IMPLICIT -> properties.setProperty(prefix + ".ssl.enable", "true");
            case STARTTLS -> {
                properties.setProperty(prefix + ".ssl.enable", "false");
                properties.setProperty(prefix + ".starttls.enable", "true");
                properties.setProperty(prefix + ".starttls.required", "true");
            }
            case PLAINTEXT -> {
                // Operator endpoints only: the Endpoint constructor refuses the rest.
                properties.setProperty(prefix + ".ssl.enable", "false");
                properties.setProperty(prefix + ".starttls.enable", "false");
                properties.setProperty(prefix + ".starttls.required", "false");
            }
        }
        properties.setProperty(prefix + ".connectiontimeout", Integer.toString(connectTimeoutMillis));
        properties.setProperty(prefix + ".timeout", Integer.toString(readTimeoutMillis));
        properties.setProperty(prefix + ".writetimeout", Integer.toString(readTimeoutMillis));
        return properties;
    }

    /**
     * Opens a connection to {@code endpoint}: TLS with the handshake done and the
     * host name verified for {@link TlsMode#IMPLICIT}; plaintext, to be upgraded
     * with {@link #startTls} after the protocol's STARTTLS exchange, for
     * {@link TlsMode#STARTTLS}.
     */
    public Socket connect(Endpoint endpoint) throws IOException {
        Socket socket = socketFactory(endpoint).createSocket();
        try {
            socket.connect(InetSocketAddress.createUnresolved(endpoint.host(), endpoint.port()), connectTimeoutMillis);
            return endpoint.tlsMode() == TlsMode.IMPLICIT ? startTls(socket, endpoint) : socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /**
     * Layers TLS over a connected socket: SNI for the host, TLS 1.2+, the
     * certificate verified against the JVM trust store and the host name verified
     * against the certificate — then the handshake, so a failure surfaces here.
     */
    public SSLSocket startTls(Socket plain, Endpoint endpoint) throws IOException {
        SSLSocket tls = (SSLSocket) sslContext.getSocketFactory().createSocket(plain, endpoint.host(), endpoint.port(), true);
        SSLParameters parameters = tls.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("LDAPS");
        parameters.setServerNames(List.of(new SNIHostName(endpoint.host())));
        parameters.setProtocols(supported(tls.getSupportedProtocols()));
        tls.setSSLParameters(parameters);
        tls.setSoTimeout(readTimeoutMillis);
        tls.startHandshake();
        return tls;
    }

    private static String[] supported(String[] available) {
        List<String> offered = Arrays.asList(available);
        return Arrays.stream(TLS_PROTOCOLS).filter(offered::contains).toArray(String[]::new);
    }

    /**
     * Lower-case ASCII host, trailing dot removed.
     *
     * @throws SocketPolicyException
     *             when it is not a DNS host name of at least two labels with a
     *             non-numeric top-level label — which also refuses every IP literal
     */
    static String normalizeHost(String host) throws SocketPolicyException {
        if (host == null || host.isBlank()) {
            throw invalidHost("Host is empty.");
        }
        String candidate = host.strip();
        if (candidate.endsWith(".")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        String ascii;
        try {
            ascii = IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw invalidHost("Host is not a valid host name.");
        }
        if (ascii.isEmpty() || ascii.length() > 253) {
            throw invalidHost("Host is not a valid host name.");
        }
        String[] labels = ascii.split("\\.", -1);
        if (labels.length < 2) {
            throw invalidHost("Host must be a fully qualified name such as imap.example.com.");
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                throw invalidHost("Host is not a valid host name.");
            }
        }
        if (labels[labels.length - 1].chars().allMatch(Character::isDigit)) {
            throw invalidHost("Host must be a name, not an IP address.");
        }
        return ascii;
    }

    private static SocketPolicyException invalidHost(String message) {
        return new SocketPolicyException(SocketPolicyException.Reason.INVALID_HOST, message);
    }

    private boolean isAllowedHost(String host) {
        if (allowedHosts.isEmpty()) {
            return true;
        }
        for (String allowed : allowedHosts) {
            if (allowed.startsWith("*.") ? host.endsWith(allowed.substring(1)) : host.equals(allowed)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> normalizeAllowlist(List<String> entries) {
        List<String> normalized = new ArrayList<>();
        if (entries == null) {
            return normalized;
        }
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String trimmed = entry.strip();
            boolean wildcard = trimmed.startsWith("*.");
            try {
                String host = normalizeHost(wildcard ? trimmed.substring(2) : trimmed);
                normalized.add(wildcard ? "*." + host : host);
            } catch (SocketPolicyException e) {
                // A typo here would otherwise read as "nothing is allowed" or, worse,
                // be silently dropped and widen the policy to any host.
                throw new IllegalArgumentException("eddi.email.allowed-hosts entry '" + trimmed + "' is not a host name or *.domain pattern: "
                        + e.getMessage(), e);
            }
        }
        return List.copyOf(normalized);
    }

    /** Plain sockets pinned to one endpoint. */
    private static final class PinnedSocketFactory extends SocketFactory {
        private final Endpoint endpoint;
        private final int connectTimeoutMillis;
        private final int readTimeoutMillis;

        PinnedSocketFactory(Endpoint endpoint, int connectTimeoutMillis, int readTimeoutMillis) {
            this.endpoint = endpoint;
            this.connectTimeoutMillis = connectTimeoutMillis;
            this.readTimeoutMillis = readTimeoutMillis;
        }

        @Override
        public Socket createSocket() {
            return new PinnedSocket(endpoint, connectTimeoutMillis, readTimeoutMillis);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket socket = createSocket();
            socket.connect(InetSocketAddress.createUnresolved(host, port), connectTimeoutMillis);
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress address, int port) throws IOException {
            Socket socket = createSocket();
            socket.connect(new InetSocketAddress(address, port), connectTimeoutMillis);
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localAddress, int localPort) throws IOException {
            throw new SocketPolicyException(SocketPolicyException.Reason.ENDPOINT_MISMATCH, "Binding a local address is not supported.");
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            throw new SocketPolicyException(SocketPolicyException.Reason.ENDPOINT_MISMATCH, "Binding a local address is not supported.");
        }
    }
}
