/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import ai.labs.eddi.engine.net.SafeSocketConnector;
import ai.labs.eddi.engine.net.SocketPolicyException;
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
import org.jboss.logging.Logger;

import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * One SMTP submission over Angus Mail on a {@link SafeSocketConnector}
 * endpoint: the one place EDDI talks SMTP, shared by the operator's
 * {@link SystemMailer} and the email channel's conversation-mode replies.
 * <p>
 * <b>The policy is the caller's.</b> This class takes an
 * {@link SafeSocketConnector.Endpoint}, which only the connector can mint — the
 * system mailer gets one from {@code resolveOperator}, a channel from the
 * author-policy {@code resolve} — and never resolves a host itself. TLS follows
 * the endpoint ({@link SafeSocketConnector#angusProperties}): implicit on 465,
 * required STARTTLS on 587, certificates and host names verified.
 * <p>
 * One connection per message: replies and system mail are a few messages per
 * turn or pause, and a pooled connection would need its own lifecycle for a
 * volume that does not need one.
 */
public final class SmtpSubmitter {

    private static final Logger LOGGER = Logger.getLogger(SmtpSubmitter.class);

    /** The longest relay reply kept as the provider id. */
    static final int MAX_SERVER_RESPONSE = 200;

    /**
     * An SMTP login. {@link #toString()} never prints the secret.
     *
     * @param user
     *            the SMTP user (for XOAUTH2 the mailbox address)
     * @param secret
     *            the password or access token
     * @param mechanisms
     *            {@code PLAIN LOGIN} or {@code XOAUTH2}
     */
    public record Login(String user, String secret, String mechanisms) {
        @Override
        public String toString() {
            return "Login[user=" + user + ", mechanisms=" + mechanisms + "]";
        }
    }

    private SmtpSubmitter() {
    }

    /**
     * Submits {@code mime} to {@code endpoint}, to exactly {@code recipients} (the
     * envelope; the headers are the renderer's), with {@code envelopeFrom} as
     * {@code MAIL FROM} — where bounces go.
     *
     * @param login
     *            the credential, or null for an unauthenticated relay
     * @return the relay's reply, single-line and capped
     * @throws MailSendException
     *             when the relay did not accept it —
     *             {@link MailSendException#isPermanent()} only for a 5xx refusal
     */
    public static String submit(SafeSocketConnector connector, SafeSocketConnector.Endpoint endpoint, String envelopeFrom, Login login, byte[] mime,
                                List<MailAddress> recipients)
            throws MailSendException {
        if (recipients == null || recipients.isEmpty()) {
            throw new IllegalArgumentException("A message needs at least one envelope recipient.");
        }
        if (endpoint.protocol() != SafeSocketConnector.Protocol.SMTP) {
            throw new IllegalArgumentException("Not an SMTP endpoint: " + endpoint);
        }
        Properties properties = connector.angusProperties(endpoint);
        properties.setProperty("mail.smtp.from", envelopeFrom);
        if (login != null) {
            properties.setProperty("mail.smtp.auth", "true");
            properties.setProperty("mail.smtp.auth.mechanisms", login.mechanisms());
        } else {
            properties.setProperty("mail.smtp.auth", "false");
        }
        Session session = Session.getInstance(properties);
        Transport transport = null;
        try {
            MimeMessage message = new MimeMessage(session, new ByteArrayInputStream(mime));
            InternetAddress[] envelope = new InternetAddress[recipients.size()];
            for (int i = 0; i < recipients.size(); i++) {
                envelope[i] = new InternetAddress(recipients.get(i).address());
            }
            transport = session.getTransport("smtp");
            if (login != null) {
                transport.connect(endpoint.host(), endpoint.port(), login.user(), login.secret());
            } else {
                transport.connect(endpoint.host(), endpoint.port(), null, null);
            }
            transport.sendMessage(message, envelope);
            String response = transport instanceof SMTPTransport smtp ? smtp.getLastServerResponse() : null;
            return cleanResponse(response);
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
