/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.engine.net.SafeSocketConnector;
import ai.labs.eddi.engine.net.SocketPolicyException;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * The deployment's one SMTP sender for system mail — today the HITL approval
 * notifications (email plan §16). Not a channel and not a tool: its relay,
 * sender and credential are <b>operator</b> configuration
 * ({@code eddi.mail.smtp.*} in {@code application.properties} or the
 * environment), and no agent or config author can change where it connects or
 * who it sends as.
 * <p>
 * <b>Off unless configured.</b> Without {@code eddi.mail.smtp.host} nothing is
 * sent and one INFO line says so at startup. A host with an unusable
 * configuration (no {@code from}, plaintext without the opt-out, a credential
 * over plaintext, two credential sources) disables the mailer with one ERROR
 * line naming the property — it never falls back to a weaker setting.
 * <p>
 * <b>Sockets.</b> Every connection goes through
 * {@link SafeSocketConnector#resolveOperator}: the operator may name a relay on
 * a private address or on port 25, but the socket is still pinned to one
 * resolution and TLS still verifies the certificate. TLS is STARTTLS (required,
 * not opportunistic) unless the port is 465 (implicit TLS) or
 * {@code eddi.mail.smtp.tls} says otherwise. {@code tls=none} sends in
 * plaintext and is refused unless {@code eddi.mail.smtp.allow-plaintext=true};
 * even then no credential is ever sent over it.
 * <p>
 * <b>Credentials</b>, at most one source: {@code eddi.mail.smtp.connection}
 * names a connection — {@code BASIC} (username + vault-backed password, SMTP
 * {@code AUTH PLAIN/LOGIN}) or {@code OAUTH2_CLIENT_CREDENTIALS} (SASL
 * {@code XOAUTH2}, Exchange Online) — whose {@code baseUrlAllowlist} must hold
 * the relay's mail origin ({@code smtp://host}, {@code smtps://host});
 * otherwise {@code eddi.mail.smtp.username} + {@code eddi.mail.smtp.password}
 * from config or the environment; otherwise no {@code AUTH} (an IP-allowlisted
 * relay).
 * <p>
 * One connection per message: system mail is a handful of messages per pause,
 * and a pooled connection would need its own lifecycle for a volume that does
 * not need one.
 */
@ApplicationScoped
public class SystemMailer {

    private static final Logger LOGGER = Logger.getLogger(SystemMailer.class);

    /** The longest relay reply kept as the provider id. */
    static final int MAX_SERVER_RESPONSE = 200;

    /** TLS modes an operator may name in {@code eddi.mail.smtp.tls}. */
    public enum Tls {
        STARTTLS, IMPLICIT, NONE
    }

    /**
     * The validated configuration of an enabled mailer.
     *
     * @param host
     *            the relay as configured
     * @param port
     *            the relay port
     * @param tls
     *            how TLS starts
     * @param from
     *            the sender (address and display name)
     * @param connection
     *            a connection name, or null
     * @param username
     *            a configured username, or null
     * @param password
     *            the configured password, or null
     */
    public record Settings(String host, int port, Tls tls, MailAddress from, String connection, String username, String password) {

        /** No password: this record reaches log lines through exceptions. */
        @Override
        public String toString() {
            return "Settings[" + host + ":" + port + " " + tls + (connection != null ? " connection=" + connection : "")
                    + (username != null ? " username" : "") + "]";
        }

        boolean authenticates() {
            return connection != null || username != null;
        }
    }

    /** What the relay said when it accepted the message. */
    public record SendResult(String serverResponse) {
    }

    private final SafeSocketConnector connector;
    private final Instance<ConnectionResolver> connectionResolver;
    private final Settings settings;
    private final String disabledReason;
    private final boolean misconfigured;

    @Inject
    public SystemMailer(SafeSocketConnector connector, Instance<ConnectionResolver> connectionResolver,
            @ConfigProperty(name = "eddi.mail.smtp.host") Optional<String> host,
            @ConfigProperty(name = "eddi.mail.smtp.port", defaultValue = "587") int port,
            @ConfigProperty(name = "eddi.mail.smtp.tls") Optional<String> tls,
            @ConfigProperty(name = "eddi.mail.smtp.allow-plaintext", defaultValue = "false") boolean allowPlaintext,
            @ConfigProperty(name = "eddi.mail.smtp.from") Optional<String> from,
            @ConfigProperty(name = "eddi.mail.smtp.from-name", defaultValue = "EDDI") String fromName,
            @ConfigProperty(name = "eddi.mail.smtp.connection") Optional<String> connection,
            @ConfigProperty(name = "eddi.mail.smtp.username") Optional<String> username,
            @ConfigProperty(name = "eddi.mail.smtp.password") Optional<String> password) {
        this.connector = connector;
        this.connectionResolver = connectionResolver;
        Settings parsed = null;
        String reason;
        boolean invalid = false;
        if (host.isEmpty() || host.get().isBlank()) {
            reason = "eddi.mail.smtp.host is not set";
        } else {
            try {
                parsed = parse(host.get(), port, tls.orElse(null), allowPlaintext, from.orElse(null), fromName, connection.orElse(null),
                        username.orElse(null), password.orElse(null));
                reason = null;
            } catch (IllegalArgumentException e) {
                reason = e.getMessage();
                invalid = true;
            }
        }
        this.settings = parsed;
        this.disabledReason = reason;
        this.misconfigured = invalid;
    }

    /** With settings already validated — the test seam. */
    SystemMailer(SafeSocketConnector connector, Instance<ConnectionResolver> connectionResolver, Settings settings) {
        this.connector = connector;
        this.connectionResolver = connectionResolver;
        this.settings = settings;
        this.disabledReason = settings == null ? "not configured" : null;
        this.misconfigured = false;
    }

    /**
     * Validates the operator's settings.
     *
     * @throws IllegalArgumentException
     *             naming the property that is wrong
     */
    static Settings parse(String host, int port, String tls, boolean allowPlaintext, String from, String fromName, String connection,
                          String username, String password) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("eddi.mail.smtp.port=" + port + " is not a TCP port.");
        }
        Tls mode;
        if (tls == null || tls.isBlank()) {
            mode = port == 465 ? Tls.IMPLICIT : Tls.STARTTLS;
        } else {
            try {
                mode = Tls.valueOf(tls.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("eddi.mail.smtp.tls must be starttls, implicit or none, not '" + tls.strip() + "'.");
            }
        }
        if (mode == Tls.NONE && !allowPlaintext) {
            throw new IllegalArgumentException("eddi.mail.smtp.tls=none sends system mail unencrypted; it is refused unless "
                    + "eddi.mail.smtp.allow-plaintext=true says so explicitly.");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("eddi.mail.smtp.from is not set: system mail needs a sender address.");
        }
        String fromAddress;
        try {
            fromAddress = AddressNormalizer.normalize(from);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("eddi.mail.smtp.from is not a valid address: " + e.getMessage());
        }
        String name = fromName == null || fromName.isBlank() ? null : fromName.strip();
        if (name != null) {
            try {
                PlainTextMailRenderer.requireHeaderSafe("eddi.mail.smtp.from-name", name);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("eddi.mail.smtp.from-name contains a line break or control character.");
            }
        }
        String connectionName = blankToNull(connection);
        // A stored connection's name always matches this grammar, so a name that does
        // not could never resolve: refuse it here, which disables the mailer at startup
        // with the reason, rather than report it enabled and fail every send.
        if (connectionName != null && !connectionName.matches(ConnectionConfiguration.NAME_GRAMMAR)) {
            throw new IllegalArgumentException("eddi.mail.smtp.connection is not a usable connection name: it must match "
                    + ConnectionConfiguration.NAME_GRAMMAR + " (letters, digits, '.', '_' and '-').");
        }
        String user = blankToNull(username);
        String secret = password == null || password.isEmpty() ? null : password;
        if (connectionName != null && (user != null || secret != null)) {
            throw new IllegalArgumentException("Set either eddi.mail.smtp.connection or eddi.mail.smtp.username/password, not both.");
        }
        if ((user == null) != (secret == null)) {
            throw new IllegalArgumentException("eddi.mail.smtp.username and eddi.mail.smtp.password must be set together.");
        }
        if (mode == Tls.NONE && (connectionName != null || user != null)) {
            throw new IllegalArgumentException("A credential is never sent over a plaintext connection: remove the SMTP credential, or "
                    + "use eddi.mail.smtp.tls=starttls or implicit.");
        }
        return new Settings(host.strip(), port, mode, new MailAddress(fromAddress, name), connectionName, user, secret);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    void logStatus(@Observes StartupEvent event) {
        if (settings != null) {
            LOGGER.infof("System mailer enabled: %s (%s)%s", settings.host() + ":" + settings.port(), settings.tls(),
                    settings.tls() == Tls.NONE ? " — PLAINTEXT, eddi.mail.smtp.allow-plaintext=true" : "");
            if (settings.tls() == Tls.NONE) {
                LOGGER.warn("System mail is sent UNENCRYPTED to the relay (eddi.mail.smtp.tls=none). Use only for a relay on the same "
                        + "host or a trusted network segment.");
            }
        } else if (misconfigured) {
            LOGGER.errorf("System mailer DISABLED, its configuration is unusable: %s No system mail (HITL email notifications) will be sent.",
                    disabledReason);
        } else {
            LOGGER.infof("System mailer disabled (%s): no system mail, such as HITL email notifications, is sent.", disabledReason);
        }
    }

    /** Whether system mail can be sent at all. */
    public boolean isEnabled() {
        return settings != null;
    }

    /** The sender, or empty when disabled. */
    public Optional<MailAddress> from() {
        return settings == null ? Optional.empty() : Optional.of(settings.from());
    }

    /**
     * Submits one rendered message to the relay, to exactly {@code recipients} (the
     * envelope; the {@code To:} header is the renderer's).
     *
     * @throws MailSendException
     *             when the relay did not accept it —
     *             {@link MailSendException#isPermanent()} only for a 5xx refusal
     */
    public SendResult send(PlainTextMailRenderer.RenderedMail mail, List<MailAddress> recipients) throws MailSendException {
        if (settings == null) {
            throw new MailSendException(MailSendException.Category.NOT_CONFIGURED, true, "The system mailer is disabled: " + disabledReason,
                    null);
        }
        if (recipients == null || recipients.isEmpty()) {
            throw new IllegalArgumentException("A message needs at least one envelope recipient.");
        }
        SafeSocketConnector.Endpoint endpoint;
        try {
            endpoint = connector.resolveOperator(settings.host(), settings.port(), SafeSocketConnector.Protocol.SMTP, socketTls(settings.tls()));
        } catch (SocketPolicyException e) {
            boolean transientDns = e.getReason() == SocketPolicyException.Reason.UNRESOLVABLE;
            throw new MailSendException(MailSendException.Category.SOCKET_POLICY, !transientDns, e.getMessage(), e);
        }
        Properties properties = connector.angusProperties(endpoint);
        properties.setProperty("mail.smtp.from", settings.from().address());
        String user = null;
        String secret = null;
        if (settings.authenticates()) {
            properties.setProperty("mail.smtp.auth", "true");
            String[] login = login(endpoint);
            user = login[0];
            secret = login[1];
            properties.setProperty("mail.smtp.auth.mechanisms", login[2]);
        } else {
            properties.setProperty("mail.smtp.auth", "false");
        }
        // angusProperties already set it under the protocol's prefix; stated here as a
        // literal so the guarantee is visible where the session is built.
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        Session session = Session.getInstance(properties);
        Transport transport = null;
        try {
            MimeMessage message = new MimeMessage(session, new ByteArrayInputStream(mail.mime()));
            InternetAddress[] envelope = new InternetAddress[recipients.size()];
            for (int i = 0; i < recipients.size(); i++) {
                envelope[i] = new InternetAddress(recipients.get(i).address());
            }
            transport = session.getTransport("smtp");
            if (user != null) {
                transport.connect(endpoint.host(), endpoint.port(), user, secret);
            } else {
                transport.connect(endpoint.host(), endpoint.port(), null, null);
            }
            transport.sendMessage(message, envelope);
            String response = transport instanceof SMTPTransport smtp ? smtp.getLastServerResponse() : null;
            return new SendResult(cleanResponse(response));
        } catch (MessagingException e) {
            throw classify(e);
        } finally {
            if (transport != null) {
                try {
                    transport.close();
                } catch (MessagingException e) {
                    LOGGER.debugf("Closing the SMTP connection failed: %s", e.getMessage());
                }
            }
        }
    }

    /**
     * {@code [username, secret, mechanisms]} for the configured credential source.
     */
    private String[] login(SafeSocketConnector.Endpoint endpoint) throws MailSendException {
        if (settings.connection() == null) {
            return new String[]{settings.username(), settings.password(), "PLAIN LOGIN"};
        }
        if (!connectionResolver.isResolvable()) {
            throw new MailSendException(MailSendException.Category.AUTHENTICATION, false, "Connections are not available in this deployment.",
                    null);
        }
        String reference = "${connection:" + settings.connection() + "}";
        URI target = URI.create((endpoint.tlsMode() == SafeSocketConnector.TlsMode.IMPLICIT ? "smtps" : "smtp") + "://" + hostForUri(endpoint) + ":"
                + endpoint.port());
        ConnectionResolver resolver = connectionResolver.get();
        try {
            if (resolver.authTypeOf(reference).orElse(null) == AuthType.BASIC) {
                ConnectionResolver.BasicLogin login = resolver.resolveBasicLogin(reference, target);
                return new String[]{login.username(), login.password(), "PLAIN LOGIN"};
            }
            // XOAUTH2 carries the mailbox as the user and the token as the secret. The
            // mailbox of a client-credentials grant is the sender itself.
            String token = resolver.resolveAccessToken(reference, target, null);
            return new String[]{settings.from().address(), token, "XOAUTH2"};
        } catch (ConnectionException e) {
            throw new MailSendException(MailSendException.Category.AUTHENTICATION, false,
                    "The SMTP credential from connection '" + settings.connection() + "' could not be resolved: " + e.getMessage(), e);
        }
    }

    private static String hostForUri(SafeSocketConnector.Endpoint endpoint) {
        String host = endpoint.host();
        return host.contains(":") ? "[" + host + "]" : host;
    }

    private static SafeSocketConnector.TlsMode socketTls(Tls tls) {
        return switch (tls) {
            case STARTTLS -> SafeSocketConnector.TlsMode.STARTTLS;
            case IMPLICIT -> SafeSocketConnector.TlsMode.IMPLICIT;
            case NONE -> SafeSocketConnector.TlsMode.PLAINTEXT;
        };
    }

    /** Sorts an Angus failure into a category, permanent only for a 5xx refusal. */
    static MailSendException classify(MessagingException e) {
        int code = returnCode(e);
        if (code >= 500 && code < 600) {
            return new MailSendException(MailSendException.Category.REJECTED, true, "The relay refused the message (" + code + ").", e);
        }
        if (code >= 400) {
            return new MailSendException(MailSendException.Category.REJECTED, false, "The relay deferred the message (" + code + ").", e);
        }
        if (e instanceof AuthenticationFailedException) {
            return new MailSendException(MailSendException.Category.AUTHENTICATION, false, "The relay rejected the SMTP credential.", e);
        }
        if (hasCause(e, SocketPolicyException.class)) {
            return new MailSendException(MailSendException.Category.SOCKET_POLICY, false, rootMessage(e), e);
        }
        String text = String.valueOf(e.getMessage()).toUpperCase(Locale.ROOT);
        if (hasCause(e, SSLException.class) || text.contains("STARTTLS")) {
            return new MailSendException(MailSendException.Category.TLS, false, "TLS with the relay failed: " + rootMessage(e), e);
        }
        if (hasCause(e, IOException.class)) {
            return new MailSendException(MailSendException.Category.CONNECT, false, "The relay could not be reached: " + rootMessage(e), e);
        }
        return new MailSendException(MailSendException.Category.OTHER, false, "Sending failed: " + rootMessage(e), e);
    }

    private static int returnCode(MessagingException e) {
        Exception current = e;
        while (current != null) {
            if (current instanceof SMTPAddressFailedException address) {
                return address.getReturnCode();
            }
            if (current instanceof SMTPSenderFailedException sender) {
                return sender.getReturnCode();
            }
            if (current instanceof SMTPSendFailedException send) {
                return send.getReturnCode();
            }
            if (current instanceof SendFailedException failed && failed.getNextException() != null) {
                current = failed.getNextException();
            } else if (current instanceof MessagingException messaging && messaging.getNextException() != null
                    && messaging.getNextException() != current) {
                current = messaging.getNextException();
            } else {
                return -1;
            }
        }
        return -1;
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage());
    }

    /** The relay's reply, single-line and capped — it is stored and logged. */
    static String cleanResponse(String response) {
        if (response == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(Math.min(response.length(), MAX_SERVER_RESPONSE));
        for (int i = 0; i < response.length() && out.length() < MAX_SERVER_RESPONSE; i++) {
            char c = response.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        return out.toString().strip();
    }
}
