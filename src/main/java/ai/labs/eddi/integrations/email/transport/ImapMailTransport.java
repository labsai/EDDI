/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.transport;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.engine.net.SafeSocketConnector;
import ai.labs.eddi.engine.net.SocketPolicyException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.UIDFolder;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPMessage;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * IMAP over Angus Mail, with every socket from {@link SafeSocketConnector}
 * under the <b>author</b> policy ({@link SafeSocketConnector#resolve}): DNS
 * names only, 993 (TLS) or 143 (STARTTLS, required), no private address, the
 * address pinned, certificates verified, {@code eddi.email.allowed-hosts}
 * honoured. Never {@code resolveOperator}: the host is the channel author's
 * choice.
 * <p>
 * <b>Credentials</b> come only from the EDDI connection the integration names:
 * a {@code BASIC} connection logs in with its username and vault-backed
 * password (an app password); an OAuth connection authenticates with
 * {@code XOAUTH2}, the mailbox address as the user. Either way the connection's
 * {@code baseUrlAllowlist} must name {@code imaps://<host>} (or
 * {@code imap://<host>:143}).
 * <p>
 * <b>Reading never marks.</b> Bodies are fetched with {@code BODY.PEEK[]}
 * ({@code mail.imap.peek}), so {@code \Seen} is never set by EDDI; the size is
 * checked from {@code RFC822.SIZE} before the body is fetched, and again while
 * it is read. {@link MailboxSession#markProcessed} sets a keyword and
 * optionally moves; nothing is ever deleted or expunged.
 */
@ApplicationScoped
public class ImapMailTransport implements MailTransport {

    private static final Logger LOGGER = Logger.getLogger(ImapMailTransport.class);

    /**
     * Resolves the endpoint a poll may connect to. Production: the author policy.
     */
    @FunctionalInterface
    public interface EndpointResolver {
        SafeSocketConnector.Endpoint resolve(String host, int port) throws SocketPolicyException;
    }

    private final SafeSocketConnector connector;
    private final EndpointResolver endpoints;
    private final Instance<ConnectionResolver> connectionResolver;

    @Inject
    public ImapMailTransport(SafeSocketConnector connector, Instance<ConnectionResolver> connectionResolver) {
        this(connector, (host, port) -> connector.resolve(host, port, SafeSocketConnector.Protocol.IMAP), connectionResolver);
    }

    /**
     * For tests: an endpoint resolver that can stand a loopback test server in for
     * the mail host. An {@code Endpoint} can only be minted inside
     * {@code engine.net}, so production code has no way to build one that skipped
     * the policy.
     */
    public ImapMailTransport(SafeSocketConnector connector, EndpointResolver endpoints, Instance<ConnectionResolver> connectionResolver) {
        this.connector = connector;
        this.endpoints = endpoints;
        this.connectionResolver = connectionResolver;
    }

    @Override
    public String id() {
        return EmailChannelSettings.TRANSPORT_IMAP;
    }

    @Override
    public MailboxSession open(String integrationId, EmailChannelSettings settings) throws MailboxException {
        SafeSocketConnector.Endpoint endpoint;
        try {
            endpoint = endpoints.resolve(settings.host(), settings.port());
        } catch (SocketPolicyException e) {
            throw new MailboxException(MailboxException.Category.POLICY, e.getMessage(), e);
        }
        String[] login = login(settings, endpoint);
        Properties properties = connector.angusProperties(endpoint);
        properties.setProperty("mail.imap.peek", "true");
        properties.setProperty("mail.imap.auth.mechanisms", login[2]);
        properties.setProperty("mail.imap.fetchsize", "65536");
        Session session = Session.getInstance(properties);
        IMAPStore store = null;
        try {
            store = (IMAPStore) session.getStore("imap");
            store.connect(endpoint.host(), endpoint.port(), login[0], login[1]);
        } catch (AuthenticationFailedException e) {
            closeQuietly(store);
            throw new MailboxException(MailboxException.Category.AUTHENTICATION,
                    "The IMAP server " + endpoint.host() + " refused the login of connection '" + settings.connection() + "'.", e);
        } catch (MessagingException e) {
            closeQuietly(store);
            throw new MailboxException(MailboxException.Category.CONNECTION,
                    "Could not connect to the IMAP server " + endpoint.host() + ":" + endpoint.port() + ": " + rootMessage(e), e);
        }
        IMAPFolder folder;
        try {
            folder = (IMAPFolder) store.getFolder(settings.folder());
            folder.open(Folder.READ_WRITE);
        } catch (MessagingException e) {
            closeQuietly(store);
            throw new MailboxException(MailboxException.Category.FOLDER, "The folder '" + settings.folder() + "' cannot be opened for writing: "
                    + rootMessage(e), e);
        }
        return new ImapSession(integrationId, store, folder);
    }

    /** {@code [user, secret, mechanisms]}. */
    private String[] login(EmailChannelSettings settings, SafeSocketConnector.Endpoint endpoint) throws MailboxException {
        if (connectionResolver == null || !connectionResolver.isResolvable()) {
            throw new MailboxException(MailboxException.Category.CREDENTIAL, "Connections are not available in this deployment.", null);
        }
        String reference = "${connection:" + settings.connection() + "}";
        URI target = URI.create(settings.imapOrigin());
        ConnectionResolver resolver = connectionResolver.get();
        try {
            if (resolver.authTypeOf(reference).orElse(null) == AuthType.BASIC) {
                ConnectionResolver.BasicLogin basic = resolver.resolveBasicLogin(reference, target);
                return new String[]{basic.username(), basic.password(), "PLAIN LOGIN"};
            }
            // XOAUTH2 carries the mailbox as the user and the token as the secret; for a
            // client-credentials grant on Exchange Online that is the shared mailbox.
            String token = resolver.resolveAccessToken(reference, target, null);
            return new String[]{settings.address(), token, "XOAUTH2"};
        } catch (ConnectionException e) {
            throw new MailboxException(MailboxException.Category.CREDENTIAL,
                    "The IMAP credential of connection '" + settings.connection() + "' could not be resolved: " + e.getMessage(), e);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return message.length() > 200 ? message.substring(0, 200) + "…" : message;
    }

    private static void closeQuietly(IMAPStore store) {
        if (store == null) {
            return;
        }
        try {
            store.close();
        } catch (MessagingException e) {
            LOGGER.debugf("Closing the IMAP store failed: %s", e.getMessage());
        }
    }

    /** One open folder. */
    /**
     * Integrations already warned, per node, that their server takes no keywords /
     * has no MOVE — one WARN per integration, not one per poll.
     */
    static final Set<String> WARNED_NO_KEYWORDS = ConcurrentHashMap.newKeySet();
    static final Set<String> WARNED_NO_MOVE = ConcurrentHashMap.newKeySet();

    /**
     * Whether a folder's {@code PERMANENTFLAGS} let EDDI store its keyword:
     * {@code \*} (any keyword), or the keyword named explicitly. Exchange Online
     * and some hosters allow neither.
     */
    static boolean keywordAllowed(Flags permanent) {
        return permanent != null && (permanent.contains(Flags.Flag.USER) || permanent.contains(PROCESSED_KEYWORD));
    }

    static final class ImapSession implements MailboxSession {
        private final String integrationId;
        private final IMAPStore store;
        private final IMAPFolder folder;

        ImapSession(String integrationId, IMAPStore store, IMAPFolder folder) {
            this.integrationId = integrationId;
            this.store = store;
            this.folder = folder;
        }

        @Override
        public long uidValidity() throws MailboxException {
            try {
                return folder.getUIDValidity();
            } catch (MessagingException e) {
                throw new MailboxException(MailboxException.Category.FOLDER, "UIDVALIDITY unavailable: " + rootMessage(e), e);
            }
        }

        @Override
        public long uidNext() throws MailboxException {
            try {
                return folder.getUIDNext();
            } catch (MessagingException e) {
                throw new MailboxException(MailboxException.Category.FOLDER, "UIDNEXT unavailable: " + rootMessage(e), e);
            }
        }

        @Override
        public List<MailboxMessage> messagesAfter(long lastUid, int max) throws MailboxException {
            try {
                Message[] range = folder.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID);
                List<Message> candidates = new ArrayList<>();
                for (Message message : range) {
                    // "UID n:*" always includes the last message, even when its UID is below n.
                    if (message != null && folder.getUID(message) > lastUid) {
                        candidates.add(message);
                    }
                }
                candidates.sort(Comparator.comparingLong(this::uidOf));
                if (candidates.size() > max) {
                    candidates = new ArrayList<>(candidates.subList(0, max));
                }
                FetchProfile profile = new FetchProfile();
                profile.add(FetchProfile.Item.ENVELOPE);
                profile.add(FetchProfile.Item.FLAGS);
                profile.add(FetchProfile.Item.SIZE);
                profile.add(UIDFolder.FetchProfileItem.UID);
                folder.fetch(candidates.toArray(Message[]::new), profile);
                long uidValidity = folder.getUIDValidity();
                List<MailboxMessage> result = new ArrayList<>(candidates.size());
                for (Message message : candidates) {
                    result.add(new ImapMessage((IMAPMessage) message, folder.getUID(message), uidValidity, folder.getFullName()));
                }
                return result;
            } catch (MessagingException e) {
                throw new MailboxException(MailboxException.Category.CONNECTION, "Listing the folder failed: " + rootMessage(e), e);
            }
        }

        private long uidOf(Message message) {
            try {
                return folder.getUID(message);
            } catch (MessagingException e) {
                return Long.MAX_VALUE;
            }
        }

        @Override
        public MarkResult markProcessed(MailboxMessage message, EmailChannelSettings settings) throws MailboxException {
            IMAPMessage imap = ((ImapMessage) message).message;
            try {
                boolean flagged = false;
                if (keywordAllowed(folder.getPermanentFlags())) {
                    try {
                        folder.setFlags(new Message[]{imap}, new Flags(PROCESSED_KEYWORD), true);
                        flagged = true;
                    } catch (MessagingException refused) {
                        // A server that advertises keywords and still refuses the STORE: the
                        // same degradation as one that does not advertise them.
                        warnNoKeywords(settings);
                    }
                } else {
                    // Degrade, never fail the poll: the UID cursor and the message ledger keep
                    // a mail from running twice; the keyword only spares a re-download.
                    warnNoKeywords(settings);
                }
                if (!settings.movesProcessed()) {
                    return flagged ? MarkResult.FLAGGED : MarkResult.NOTHING;
                }
                if (!store.hasCapability("MOVE")) {
                    if (WARNED_NO_MOVE.add(integrationId)) {
                        LOGGER.warnf("Email integration %s: the IMAP server does not support MOVE, so processed mail stays in '%s' "
                                + "(EDDI never copies and deletes).", sanitize(integrationId), sanitize(settings.folder()));
                    }
                    return flagged ? MarkResult.FLAGGED_NOT_MOVED : MarkResult.NOTHING;
                }
                Folder destination = store.getFolder(settings.moveToFolder());
                if (!destination.exists()) {
                    destination.create(Folder.HOLDS_MESSAGES);
                }
                folder.moveMessages(new Message[]{imap}, destination);
                return MarkResult.MOVED;
            } catch (MessagingException e) {
                throw new MailboxException(MailboxException.Category.CONNECTION, "Marking a message processed failed: " + rootMessage(e), e);
            }
        }

        private void warnNoKeywords(EmailChannelSettings settings) {
            if (WARNED_NO_KEYWORDS.add(integrationId)) {
                LOGGER.warnf("Email integration %s: the IMAP server does not accept the keyword %s on folder '%s' (PERMANENTFLAGS), so processed "
                        + "mail cannot be flagged. Polling goes on: the cursor and the message ledger keep every mail from running twice. Use "
                        + "processedAction 'move:<folder>' (recommended for Exchange Online) to see processed mail leave the folder.",
                        sanitize(integrationId), PROCESSED_KEYWORD, sanitize(settings.folder()));
            }
        }

        @Override
        public void close() {
            try {
                if (folder.isOpen()) {
                    // false: never expunge — EDDI deletes nothing, and an expunge would also
                    // remove whatever a person had marked deleted.
                    folder.close(false);
                }
            } catch (MessagingException e) {
                LOGGER.debugf("Closing the IMAP folder failed: %s", e.getMessage());
            }
            closeQuietly(store);
        }
    }

    static final class ImapMessage implements MailboxMessage {
        final IMAPMessage message;
        private final long uid;
        private final long uidValidity;
        private final String folderName;

        ImapMessage(IMAPMessage message, long uid, long uidValidity, String folderName) {
            this.message = message;
            this.uid = uid;
            this.uidValidity = uidValidity;
            this.folderName = folderName;
        }

        @Override
        public long uid() {
            return uid;
        }

        @Override
        public String envelopeMessageId() {
            try {
                return message.getMessageID();
            } catch (MessagingException e) {
                return null;
            }
        }

        @Override
        public long size() {
            try {
                return message.getSizeLong();
            } catch (MessagingException e) {
                return -1;
            }
        }

        @Override
        public Instant internalDate() {
            try {
                return message.getReceivedDate() == null ? null : message.getReceivedDate().toInstant();
            } catch (MessagingException e) {
                return null;
            }
        }

        @Override
        public boolean alreadyProcessed() {
            try {
                Flags flags = message.getFlags();
                return flags != null && Arrays.asList(flags.getUserFlags()).contains(PROCESSED_KEYWORD);
            } catch (MessagingException e) {
                return false;
            }
        }

        @Override
        public String sourceRef() {
            return "imap:" + folderName + ":" + uidValidity + ":" + uid;
        }

        @Override
        public byte[] fetchRaw(long maxBytes) throws MailboxException {
            BoundedBuffer buffer = new BoundedBuffer(maxBytes);
            try {
                message.writeTo(buffer);
                return buffer.toByteArray();
            } catch (TooLarge e) {
                throw new MailboxException(MailboxException.Category.TOO_LARGE, "The message is larger than " + maxBytes + " bytes.", e);
            } catch (IOException | MessagingException e) {
                throw new MailboxException(MailboxException.Category.FETCH, "Fetching the message failed: " + rootMessage(e), e);
            }
        }
    }

    private static final class TooLarge extends IOException {
        TooLarge() {
            super("over the size limit");
        }
    }

    /** Collects at most {@code max} bytes and refuses the rest. */
    private static final class BoundedBuffer extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final long max;

        BoundedBuffer(long max) {
            this.max = max;
        }

        @Override
        public void write(int b) throws IOException {
            if (bytes.size() + 1L > max) {
                throw new TooLarge();
            }
            bytes.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (bytes.size() + (long) len > max) {
                throw new TooLarge();
            }
            bytes.write(b, off, len);
        }

        byte[] toByteArray() {
            return bytes.toByteArray();
        }
    }
}
