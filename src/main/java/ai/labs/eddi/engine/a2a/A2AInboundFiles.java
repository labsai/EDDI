/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.engine.a2a.A2AModels.FilePart;
import ai.labs.eddi.engine.a2a.A2AModels.InboundFileMode;
import ai.labs.eddi.engine.a2a.A2AModels.InvalidA2ARequestException;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentQuotaExceededException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import ai.labs.eddi.engine.attachments.MimeValidator;
import ai.labs.eddi.engine.httpclient.outbound.BodyMode;
import ai.labs.eddi.engine.httpclient.outbound.BodyTooLargeException;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.OutboundResponse;
import ai.labs.eddi.engine.httpclient.outbound.Profile;
import ai.labs.eddi.engine.httpclient.outbound.SsrfPolicy;
import ai.labs.eddi.engine.memory.AttachmentContextExtractor;
import ai.labs.eddi.engine.model.Context;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static ai.labs.eddi.engine.a2a.A2AModels.ERROR_CONTENT_TYPE_NOT_SUPPORTED;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Takes the file parts of an inbound A2A message into the task's conversation —
 * {@code eddi.a2a.files.inbound}.
 * <p>
 * Each file is stored <strong>through the attachment store</strong>, owned by
 * the task's conversation and counted against the calling peer's quota, so the
 * store's own rules apply unchanged: the size limit
 * ({@code eddi.attachments.max-size-bytes}), the MIME check by signature, the
 * per-conversation and per-user quotas. The turn then names the stored blobs as
 * {@code attachment_N} input context entries, exactly as a REST upload does,
 * and the forwarder and {@code readAttachment} see them as any other upload.
 * <p>
 * A file part that names a {@code uri} is fetched only in mode
 * {@link InboundFileMode#INLINE_AND_URI}. The URI is chosen by a remote peer,
 * so the fetch goes through {@link OutboundHttp} under
 * {@link SsrfPolicy#STRICT} (checked on the URL and on every redirect hop),
 * sends no credentials, and reads at most
 * {@code eddi.attachments.max-size-bytes} into memory.
 * <p>
 * Every refusal is an {@link InvalidA2ARequestException} whose message is
 * written here: no downstream exception text, resolved address or store detail
 * reaches the peer.
 * <p>
 * Files stored here are owned by their conversation and nothing else: they are
 * erased with it ({@code IAttachmentStore.deleteByConversation}), need no
 * erasure participant of their own, and never reach a library. A2A peers often
 * share a key, and everything sent with that key shares one per-user quota.
 */
@ApplicationScoped
public class A2AInboundFiles {

    private static final Logger LOGGER = Logger.getLogger(A2AInboundFiles.class);

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** The meter counting files taken in (and, later, sent out) over A2A. */
    static final String METER_FILES = "eddi.a2a.files";

    static final String REFUSED_URI_NOT_ACCEPTED = "File parts by URI are not accepted by this deployment; send the file's bytes inline";
    static final String REFUSED_UNAVAILABLE = "File parts are not accepted: this deployment has no attachment store";
    static final String REFUSED_NOT_FETCHED = "A file part's URI could not be fetched";
    static final String REFUSED_NOT_STORED = "A file part was refused: its content is empty, or does not match its declared media type";
    static final String REFUSED_QUOTA = "A file part was refused: the attachment quota is exhausted";

    private final InboundFileMode mode;
    private final int maxPerMessage;
    private final long maxSizeBytes;
    private final Instance<IAttachmentStore> attachmentStore;
    private final OutboundHttp http;
    private final MeterRegistry meterRegistry;

    @Inject
    public A2AInboundFiles(@ConfigProperty(name = "eddi.a2a.files.inbound", defaultValue = "off") String mode,
            @ConfigProperty(name = "eddi.a2a.files.max-per-message", defaultValue = "5") int maxPerMessage,
            @ConfigProperty(name = "eddi.attachments.max-per-turn", defaultValue = "5") int maxPerTurn,
            @ConfigProperty(name = "eddi.attachments.max-size-bytes", defaultValue = "20971520") long maxSizeBytes,
            Instance<IAttachmentStore> attachmentStore, OutboundHttp http, MeterRegistry meterRegistry) {
        this(InboundFileMode.fromProperty(mode), effectiveCap(maxPerMessage, maxPerTurn), maxSizeBytes, attachmentStore, http,
                meterRegistry);
    }

    A2AInboundFiles(InboundFileMode mode, int maxPerMessage, long maxSizeBytes, Instance<IAttachmentStore> attachmentStore,
            OutboundHttp http, MeterRegistry meterRegistry) {
        this.mode = mode;
        this.maxPerMessage = maxPerMessage;
        this.maxSizeBytes = maxSizeBytes;
        this.attachmentStore = attachmentStore;
        this.http = http;
        this.meterRegistry = meterRegistry;
    }

    /** File parts switched off — what a handler built without this bean uses. */
    static A2AInboundFiles disabled() {
        return new A2AInboundFiles(InboundFileMode.OFF, 0, 0, null, null, null);
    }

    /**
     * The per-message cap: {@code eddi.a2a.files.max-per-message}, but never more
     * than {@code eddi.attachments.max-per-turn} when that is set — a file the turn
     * would drop is refused here instead, where the peer is told.
     */
    static int effectiveCap(int maxPerMessage, int maxPerTurn) {
        int cap = Math.max(0, maxPerMessage);
        return maxPerTurn > 0 ? Math.min(cap, maxPerTurn) : cap;
    }

    public InboundFileMode mode() {
        return mode;
    }

    /** Whether file parts are read at all. */
    public boolean acceptsFiles() {
        return mode != InboundFileMode.OFF;
    }

    int maxPerMessage() {
        return maxPerMessage;
    }

    /**
     * The refusals that need no I/O — run before a conversation is created, so a
     * refused message leaves none behind.
     *
     * @throws InvalidA2ARequestException
     *             for too many files, a URI where only inline bytes are accepted, a
     *             URI that is not absolute {@code http}/{@code https}, inline bytes
     *             that decode to more than the size limit, or no store
     */
    void check(List<FilePart> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        if (!acceptsFiles()) {
            throw new InvalidA2ARequestException(ERROR_CONTENT_TYPE_NOT_SUPPORTED, "Only text and data parts are supported; file parts are not");
        }
        if (files.size() > maxPerMessage) {
            throw new InvalidA2ARequestException("A message may carry at most " + maxPerMessage + " file part(s); this one carries " + files.size());
        }
        if (store() == null) {
            throw new InvalidA2ARequestException(ERROR_CONTENT_TYPE_NOT_SUPPORTED, REFUSED_UNAVAILABLE);
        }
        for (FilePart file : files) {
            if (file.uri() != null) {
                if (mode != InboundFileMode.INLINE_AND_URI) {
                    throw new InvalidA2ARequestException(ERROR_CONTENT_TYPE_NOT_SUPPORTED, REFUSED_URI_NOT_ACCEPTED);
                }
                fetchableUri(file.uri());
            } else if (decodedLengthUpperBound(file.bytes()) > maxSizeBytes + 2) {
                // +2: the padding estimate may overshoot by up to two bytes.
                throw tooLarge();
            }
        }
    }

    /**
     * Decodes or fetches every file and checks its declared type against its
     * signature, the store's own MIME rule, without storing anything. Run before a
     * conversation is created, so that every refusal except an exhausted quota
     * leaves no conversation behind. Holds the message's files in memory until
     * {@link #storeLoaded}: at most {@code max-per-message} times
     * {@code eddi.attachments.max-size-bytes}.
     *
     * @throws InvalidA2ARequestException
     *             when a file is refused by {@link #check}, is not valid base64,
     *             cannot be fetched, or does not match its declared type
     */
    List<Loaded> load(List<FilePart> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        check(files);
        List<Loaded> loaded = new ArrayList<>(files.size());
        for (FilePart file : files) {
            Loaded one = file.uri() != null ? fetch(file) : decode(file);
            if (!MimeValidator.isCompatibleContent(one.mimeType(), one.bytes())) {
                throw new InvalidA2ARequestException(REFUSED_NOT_STORED);
            }
            loaded.add(one);
        }
        return loaded;
    }

    /**
     * Stores loaded files into {@code conversationId} and returns the
     * {@code attachment_N} context entries naming them. On any refusal the files
     * already stored for this message are deleted again.
     *
     * @param userId
     *            the calling peer — the conversation's owner, whose per-user quota
     *            the files count against
     * @throws InvalidA2ARequestException
     *             when the store refuses a file (a quota, most likely)
     */
    Map<String, Context> storeLoaded(List<Loaded> files, String conversationId, String userId) {
        if (files == null || files.isEmpty()) {
            return Map.of();
        }
        IAttachmentStore store = store();
        if (store == null) {
            throw new InvalidA2ARequestException(ERROR_CONTENT_TYPE_NOT_SUPPORTED, REFUSED_UNAVAILABLE);
        }
        List<String> stored = new ArrayList<>();
        Map<String, Context> contexts = new LinkedHashMap<>();
        try {
            for (Loaded file : files) {
                IAttachmentStore.Attachment saved = storeOne(store, file.bytes(), file.mimeType(), file.name(), conversationId, userId);
                stored.add(saved.storageRef());
                contexts.put(AttachmentContextExtractor.ATTACHMENT_PREFIX + contexts.size(), reference(saved.storageRef(), file.name()));
            }
        } catch (RuntimeException e) {
            deleteQuietly(store, stored, conversationId);
            throw e;
        }
        stored.forEach(ref -> countInbound());
        return contexts;
    }

    /** {@link #load} then {@link #storeLoaded}. */
    Map<String, Context> store(List<FilePart> files, String conversationId, String userId) {
        return storeLoaded(load(files), conversationId, userId);
    }

    /**
     * Deletes files {@link #storeLoaded} stored, for a message refused after its
     * files were stored. Best effort: what cannot be deleted goes with its
     * conversation.
     */
    void discard(Map<String, Context> attachments, String conversationId) {
        IAttachmentStore store = store();
        if (store == null || attachments == null || attachments.isEmpty()) {
            return;
        }
        deleteQuietly(store, refsOf(attachments), conversationId);
    }

    private static List<String> refsOf(Map<String, Context> attachments) {
        List<String> refs = new ArrayList<>();
        for (Context context : attachments.values()) {
            if (context.getValue() instanceof Map<?, ?> reference
                    && reference.get(AttachmentContextExtractor.FIELD_STORAGE_REF) instanceof String ref) {
                refs.add(ref);
            }
        }
        return refs;
    }

    /**
     * Moves files this message stored from one conversation to another — for a
     * context whose conversation had ended and carries on in a fresh one. Each blob
     * is read, deleted from {@code from} and stored into {@code to}, so the files
     * stay owned by the conversation that reads them, and a peer at its quota can
     * still move them. A file whose copy is refused is lost with the task, which
     * fails: the ended conversation could not have used it.
     *
     * @return the context entries naming the copies
     * @throws InvalidA2ARequestException
     *             when a copy cannot be stored; copies made so far are deleted
     */
    Map<String, Context> rehome(Map<String, Context> attachments, String from, String to, String userId) {
        if (attachments == null || attachments.isEmpty()) {
            return Map.of();
        }
        IAttachmentStore store = store();
        if (store == null) {
            throw new InvalidA2ARequestException(ERROR_CONTENT_TYPE_NOT_SUPPORTED, REFUSED_UNAVAILABLE);
        }
        List<String> copies = new ArrayList<>();
        Map<String, Context> moved = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Context> entry : attachments.entrySet()) {
                if (!(entry.getValue().getValue() instanceof Map<?, ?> reference)
                        || !(reference.get(AttachmentContextExtractor.FIELD_STORAGE_REF) instanceof String ref)) {
                    continue;
                }
                String fileName = reference.get("fileName") instanceof String name ? name : null;
                IAttachmentStore.Attachment original;
                byte[] bytes;
                try {
                    original = store.getMetadata(ref, from);
                    bytes = store.load(ref, from);
                } catch (AttachmentStoreException e) {
                    throw new InvalidA2ARequestException(REFUSED_NOT_STORED);
                }
                deleteQuietly(store, List.of(ref), from);
                IAttachmentStore.Attachment copy = storeOne(store, bytes, original.mimeType(), fileName, to, userId);
                copies.add(copy.storageRef());
                moved.put(entry.getKey(), reference(copy.storageRef(), fileName));
            }
        } catch (RuntimeException e) {
            // The originals already moved are gone; the turn that would read them fails.
            deleteQuietly(store, copies, to);
            throw e;
        }
        return moved;
    }

    // ==================== loading one file ====================

    /** One file of a message, decoded or fetched, not yet stored. */
    record Loaded(byte[] bytes, String mimeType, String name) {
    }

    private Loaded decode(FilePart file) {
        byte[] bytes;
        // Line-wrapped base64 (MIME-style, 76 columns) is common; the strict
        // decoders refuse embedded whitespace, so it goes first. The MIME decoder is
        // not used: it skips every invalid character silently.
        String encoded = WHITESPACE.matcher(file.bytes()).replaceAll("");
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException standard) {
            try {
                // Protobuf JSON accepts the URL-safe alphabet too.
                bytes = Base64.getUrlDecoder().decode(encoded);
            } catch (IllegalArgumentException urlSafe) {
                throw new InvalidA2ARequestException("A file part's bytes are not valid base64");
            }
        }
        if (bytes.length > maxSizeBytes) {
            throw tooLarge();
        }
        return new Loaded(bytes, mimeOf(file.mimeType(), null, bytes), file.name());
    }

    private Loaded fetch(FilePart file) {
        URI uri = fetchableUri(file.uri());
        OutboundRequest request = OutboundRequest.get(Profile.DEFAULT, uri).ssrf(SsrfPolicy.STRICT).build();
        OutboundResponse<byte[]> response;
        try {
            response = http.send(request, BodyMode.bytes(maxSizeBytes, BodyMode.OnOverflow.FAIL));
        } catch (BodyTooLargeException e) {
            throw tooLarge();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvalidA2ARequestException(REFUSED_NOT_FETCHED);
        } catch (IOException | RuntimeException e) {
            // OutboundBlockedException (SSRF) is an IllegalArgumentException: the peer
            // learns only that the URI could not be fetched, never why.
            LOGGER.debugf("A2A file part not fetched from %s: %s", sanitize(OutboundHttp.withoutQuery(uri)), sanitize(e.getMessage()));
            throw new InvalidA2ARequestException(REFUSED_NOT_FETCHED);
        }
        if (response.status() < 200 || response.status() >= 300 || response.body() == null) {
            LOGGER.debugf("A2A file part not fetched from %s: HTTP %d", sanitize(OutboundHttp.withoutQuery(uri)), response.status());
            throw new InvalidA2ARequestException(REFUSED_NOT_FETCHED);
        }
        byte[] bytes = response.body();
        if (bytes.length > maxSizeBytes) {
            throw tooLarge();
        }
        return new Loaded(bytes, mimeOf(file.mimeType(), response.firstHeader("Content-Type").orElse(null), bytes), file.name());
    }

    /**
     * An absolute {@code http} or {@code https} URI with a host — anything else is
     * refused before a connection is attempted.
     */
    static URI fetchableUri(String raw) {
        URI uri;
        try {
            uri = new URI(raw.strip());
        } catch (URISyntaxException e) {
            throw new InvalidA2ARequestException("A file part's URI is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidA2ARequestException("A file part's URI must be an absolute http or https URI");
        }
        if (uri.getRawUserInfo() != null) {
            throw new InvalidA2ARequestException("A file part's URI must not carry credentials");
        }
        return uri;
    }

    /**
     * The declared media type, else the response's {@code Content-Type}, else the
     * type the content's signature shows. The store checks whichever is chosen
     * against the signature again.
     */
    private static String mimeOf(String declared, String contentType, byte[] bytes) {
        if (declared != null && !declared.isBlank()) {
            return declared;
        }
        if (contentType != null && !contentType.isBlank()) {
            return MimeValidator.normalize(contentType);
        }
        return MimeValidator.detectMime(bytes);
    }

    // ==================== storing ====================

    private static IAttachmentStore.Attachment storeOne(IAttachmentStore store, byte[] bytes, String mimeType, String fileName,
                                                        String conversationId, String userId) {
        try {
            return store.store(bytes, mimeType, fileName, conversationId, null, userId);
        } catch (AttachmentQuotaExceededException e) {
            throw new InvalidA2ARequestException(REFUSED_QUOTA);
        } catch (AttachmentStoreException e) {
            LOGGER.debugf("A2A file part refused by the attachment store: %s", sanitize(e.getMessage()));
            throw new InvalidA2ARequestException(REFUSED_NOT_STORED);
        }
    }

    private static void deleteQuietly(IAttachmentStore store, List<String> refs, String conversationId) {
        for (String ref : refs) {
            try {
                store.delete(ref, conversationId);
            } catch (AttachmentStoreException | RuntimeException e) {
                LOGGER.warnf("A2A: a stored file part could not be removed again: %s", sanitize(e.getMessage()));
            }
        }
    }

    private static Context reference(String storageRef, String fileName) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put(AttachmentContextExtractor.FIELD_STORAGE_REF, storageRef);
        if (fileName != null) {
            reference.put("fileName", fileName);
        }
        return new Context(Context.ContextType.object, reference);
    }

    private IAttachmentStore store() {
        return attachmentStore != null && attachmentStore.isResolvable() ? attachmentStore.get() : null;
    }

    private InvalidA2ARequestException tooLarge() {
        return new InvalidA2ARequestException("A file part is larger than the limit of " + maxSizeBytes + " bytes");
    }

    /** At most how many bytes a base64 string decodes to. */
    static long decodedLengthUpperBound(String base64) {
        if (base64 == null) {
            return 0;
        }
        long significant = base64.chars().filter(c -> !Character.isWhitespace(c)).count();
        return (long) Math.ceil(significant / 4.0) * 3;
    }

    private void countInbound() {
        if (meterRegistry != null) {
            meterRegistry.counter(METER_FILES, "direction", "inbound").increment();
        }
    }
}
