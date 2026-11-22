/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.channels.model.EmailControl;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.engine.mail.MailSendException;
import ai.labs.eddi.engine.mail.SmimeCertificateCheck;
import ai.labs.eddi.engine.mail.SmimeIdentity;
import ai.labs.eddi.engine.mail.SmimeSigner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Applies {@code emailControl.smime} to a message an email integration is about
 * to submit through its own SMTP relay (email channel v3 plan §6.2–§6.4) — the
 * one place that decides whether a message leaves signed, unsigned, or not at
 * all.
 * <ul>
 * <li>{@code sign: false}: unsigned, always.</li>
 * <li>{@code sign: true} (the default): signed when a signing connection is
 * named and its certificate can sign for the mailbox; otherwise unsigned (the
 * save warned, and the daily check tells the owners).</li>
 * <li>{@code sign: required}: signed, or not sent. A missing, expired or
 * mismatched certificate, an unreadable key or a failed signature is a
 * {@link MailSendException.Category#SIGNING} failure, and the caller holds the
 * mail instead of sending it.</li>
 * </ul>
 * The certificate is checked on every message, not only at save time: it can
 * expire, and the connection can be changed, between the two.
 */
@ApplicationScoped
public class EmailSmimeSigning {

    private static final Logger LOGGER = Logger.getLogger(EmailSmimeSigning.class);

    /** A message ready to submit, and whether it carries a signature. */
    public record Prepared(byte[] mime, boolean signed) {
        @Override
        public String toString() {
            return "Prepared[" + mime.length + " bytes, signed=" + signed + "]";
        }
    }

    private final Instance<ConnectionResolver> connectionResolver;
    private final Clock clock;

    @Inject
    public EmailSmimeSigning(Instance<ConnectionResolver> connectionResolver) {
        this(connectionResolver, Clock.systemUTC());
    }

    /** For tests: a fixed clock. */
    public EmailSmimeSigning(Instance<ConnectionResolver> connectionResolver, Clock clock) {
        this.connectionResolver = connectionResolver;
        this.clock = clock;
    }

    /**
     * Signs {@code mime} as {@code settings} say.
     *
     * @throws MailSendException
     *             {@code SIGNING} under {@code sign: required} when the message
     *             cannot be signed — never thrown under {@code true} or
     *             {@code false}
     */
    public Prepared prepare(String integrationId, EmailChannelSettings settings, byte[] mime) throws MailSendException {
        EmailControl.SignMode sign = settings.smimeSign();
        if (sign == EmailControl.SignMode.FALSE || !settings.wantsSignature()) {
            return new Prepared(mime, false);
        }
        boolean required = sign == EmailControl.SignMode.REQUIRED;
        SmimeIdentity identity;
        try {
            identity = usableIdentity(settings);
        } catch (Unavailable e) {
            if (required) {
                throw new MailSendException(MailSendException.Category.SIGNING, false, "smime_unavailable: " + e.getMessage(), null);
            }
            LOGGER.warnf("Email integration %s: sending unsigned, the S/MIME certificate is not usable: %s", sanitize(integrationId),
                    e.getMessage());
            return new Prepared(mime, false);
        }
        try {
            return new Prepared(SmimeSigner.sign(mime, identity), true);
        } catch (MailSendException e) {
            if (required) {
                throw e;
            }
            LOGGER.warnf("Email integration %s: sending unsigned, the message could not be signed: %s", sanitize(integrationId), e.getMessage());
            return new Prepared(mime, false);
        }
    }

    /**
     * The certificate check of {@code settings}' signing connection, for the save
     * path and the daily check.
     *
     * @throws Unavailable
     *             when there is no certificate to check (no connection named, the
     *             connection cannot be resolved, or its material cannot be read)
     */
    public SmimeCertificateCheck.Result check(EmailChannelSettings settings) throws Unavailable {
        return SmimeCertificateCheck.check(identity(settings), settings.address(), Instant.now(clock));
    }

    /** The identity, if its certificate may sign for the mailbox now. */
    SmimeIdentity usableIdentity(EmailChannelSettings settings) throws Unavailable {
        SmimeIdentity identity = identity(settings);
        SmimeCertificateCheck.Result result = SmimeCertificateCheck.check(identity, settings.address(), Instant.now(clock));
        if (!result.usable()) {
            throw new Unavailable(result.describe());
        }
        return identity;
    }

    /** The signing identity of {@code settings}' connection. */
    SmimeIdentity identity(EmailChannelSettings settings) throws Unavailable {
        String connection = settings.smimeConnection();
        if (connection == null) {
            throw new Unavailable("no SMIME_SIGNING connection is named in emailControl.smime.connection");
        }
        if (settings.smtpOrigin() == null) {
            throw new Unavailable("the integration has no smtpHost, so there is no relay to sign mail for");
        }
        if (connectionResolver == null || !connectionResolver.isResolvable()) {
            throw new Unavailable("connections are not available in this deployment");
        }
        ConnectionResolver.SmimeMaterial material;
        try {
            material = connectionResolver.get().resolveSmimeSigning("${connection:" + connection + "}", URI.create(settings.smtpOrigin()));
        } catch (ConnectionException e) {
            throw new Unavailable(e.getMessage());
        }
        try {
            return SmimeIdentity.load(material.certificate(), material.password());
        } catch (IllegalArgumentException e) {
            throw new Unavailable("connection '" + connection + "': " + e.getMessage());
        }
    }

    /** Why there is no usable signing identity, in words safe to log. */
    public static final class Unavailable extends Exception {
        public Unavailable(String message) {
            super(message);
        }
    }
}
