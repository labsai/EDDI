/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents;

import ai.labs.eddi.engine.attachments.AttachmentOwnerChanged;
import ai.labs.eddi.engine.attachments.AttachmentRootDeleted;
import ai.labs.eddi.engine.attachments.AttachmentSelector;
import ai.labs.eddi.engine.attachments.Derivation;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.OwnerRef;
import ai.labs.eddi.engine.documents.SelectorException.Reason;
import ai.labs.eddi.engine.memory.AttachmentContextExtractor;
import ai.labs.eddi.modules.ingestion.extract.ExtractionLimits;
import ai.labs.eddi.modules.ingestion.extract.PdfStructureExtractor;
import ai.labs.eddi.modules.ingestion.extract.UnreadableDocumentException;
import ai.labs.eddi.modules.ingestion.extract.document.DocumentManifest;
import ai.labs.eddi.modules.ingestion.extract.document.SplitConfig;
import ai.labs.eddi.modules.ingestion.extract.document.SplitStrategy;
import ai.labs.eddi.utils.LogSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * The structure of a stored PDF, and <em>the only place a unit label becomes
 * pages</em>.
 * <p>
 * {@link #manifest} returns a document's {@link DocumentManifest} under a split
 * configuration. It is persisted as a {@link Derivation.Kind#MANIFEST} derived
 * blob of the root, keyed {@code unitId = "manifest:" + split.hash()}, written
 * through {@link IAttachmentStore#storeDerived} with the <em>requesting</em>
 * conversation — the store gives it the root's owner, so a manifest a grantee
 * caused belongs to the file's owner. Two owners with the same bytes have two
 * roots and so two manifests: nothing is shared by content.
 * <p>
 * A node-local cache keyed {@code (ownerKey, rootRef, split hash)} sits in
 * front of the store. It is never authoritative and never consulted before the
 * store has confirmed that the requesting conversation may read the file: every
 * call asks the store first. Its values hold no storage reference. It is
 * flushed per root on {@link AttachmentRootDeleted} and
 * {@link AttachmentOwnerChanged}, and per owner by {@link #evictOwner}.
 * <p>
 * {@link #resolve} applies the selector rule ({@link SelectorResolver}): pages
 * are authoritative; a unit without pages becomes the pages of that unit under
 * the given split; a unit carrying another split's hash and no pages is an
 * error.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class DocumentStructureService {

    private static final Logger LOGGER = Logger.getLogger(DocumentStructureService.class);

    /** The {@code unitId} prefix of a stored manifest. */
    public static final String MANIFEST_UNIT_PREFIX = "manifest:";

    /** The MIME type of a stored manifest. */
    static final String MANIFEST_MIME = "application/json";

    /** The only MIME type that has a structure today. */
    static final String PDF = "application/pdf";

    /** Version of the persisted manifest envelope. */
    static final int STORED_VERSION = 1;

    static final Duration CACHE_EXPIRY = Duration.ofMinutes(30);
    static final long CACHE_MAX_ENTRIES = 2_000;

    private static final String NOT_FOUND = "not found";

    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * What is persisted: the split hash is part of the payload so that two splits
     * that cut a file identically still store different bytes — the store
     * de-duplicates by content, and a de-duplicated blob is not findable under the
     * second split's key.
     */
    record StoredManifest(int version, String split, DocumentManifest manifest) {
    }

    record Key(String ownerKey, String rootRef, String split) {
    }

    private final Instance<IAttachmentStore> storeInstance;
    private final IAttachmentStore directStore;
    private final PdfStructureExtractor extractor;
    private final DocumentSettings settings;
    private final Cache<Key, DocumentManifest> cache = Caffeine.newBuilder().expireAfterAccess(CACHE_EXPIRY)
            .maximumSize(CACHE_MAX_ENTRIES).build();

    @Inject
    public DocumentStructureService(Instance<IAttachmentStore> attachmentStore, PdfStructureExtractor extractor,
            DocumentSettings settings) {
        this.storeInstance = attachmentStore;
        this.directStore = null;
        this.extractor = extractor;
        this.settings = settings;
    }

    /**
     * Direct construction, for tests and code without a CDI context.
     *
     * @param attachmentStore
     *            the store, or {@code null} — every call then fails as "not found"
     */
    public DocumentStructureService(IAttachmentStore attachmentStore, PdfStructureExtractor extractor, DocumentSettings settings) {
        this.storeInstance = null;
        this.directStore = attachmentStore;
        this.extractor = extractor;
        this.settings = settings != null ? settings : DocumentSettings.none();
    }

    private IAttachmentStore store() {
        if (storeInstance != null) {
            return storeInstance.isResolvable() ? storeInstance.get() : null;
        }
        return directStore;
    }

    /**
     * The manifest of a stored PDF under {@code split}: from the cache, else the
     * stored {@code MANIFEST} blob, else extracted and stored.
     *
     * @param storageRef
     *            the PDF (a root, or a view of one: its root is described)
     * @param conversationId
     *            the conversation asking — the authorization boundary
     * @param split
     *            how to cut it; {@code null} for {@link SplitConfig#defaults()}
     * @return the manifest; its {@code rootRef} is the root's reference
     * @throws SelectorException
     *             {@link Reason#NOT_FOUND} when the conversation cannot read the
     *             file, {@link Reason#NOT_PDF}, or {@link Reason#UNREADABLE}
     */
    public DocumentManifest manifest(String storageRef, String conversationId, SplitConfig split) {
        SplitConfig config = split != null ? split : SplitConfig.defaults();
        IAttachmentStore store = requireStore();
        String root = rootOf(store, storageRef, conversationId);
        return manifestOfRoot(store, root, conversationId, config);
    }

    /**
     * {@link #resolve(String, String, String, String, String, SplitConfig)} without
     * a carried split hash.
     */
    public AttachmentSelector resolve(String storageRef, String conversationId, String unitOrNull, String pagesOrNull,
                                      SplitConfig split) {
        return resolve(storageRef, conversationId, unitOrNull, pagesOrNull, null, split);
    }

    /**
     * The ONLY place a unit label becomes pages. Returns a selector with pages, and
     * — when a unit was resolved — unit and split set; pages that were given are
     * kept and never re-derived from the unit.
     *
     * @param storageRef
     *            the stored PDF; a part is always of a root
     * @param conversationId
     *            the conversation asking — the authorization boundary
     * @param unitOrNull
     *            a unit label such as {@code u3}
     * @param pagesOrNull
     *            a page range {@code "<from>-<to>"}
     * @param carriedSplitOrNull
     *            the split hash that came with the unit, if any
     * @param split
     *            the receiving split; {@code null} for
     *            {@link SplitConfig#defaults()}
     * @throws SelectorException
     *             with a model-safe message
     */
    public AttachmentSelector resolve(String storageRef, String conversationId, String unitOrNull, String pagesOrNull,
                                      String carriedSplitOrNull, SplitConfig split) {
        boolean noPart = isBlank(unitOrNull) && isBlank(pagesOrNull);
        if (noPart) {
            return AttachmentSelector.wholeFile(storageRef);
        }
        SplitConfig config = split != null ? split : SplitConfig.defaults();
        IAttachmentStore store = requireStore();
        String root = rootOf(store, storageRef, conversationId);
        if (isView(store, storageRef, conversationId)) {
            throw new SelectorException(Reason.NOT_PDF, "only a stored PDF can be sent in parts, not a view of one");
        }
        requirePdf(store, root, conversationId);
        SelectorResolver.DocumentFacts facts = new SelectorResolver.DocumentFacts() {
            @Override
            public int pageCount() {
                Integer known = null;
                try {
                    known = store.pageCount(root, conversationId);
                } catch (AttachmentStoreException e) {
                    throw notFound(e);
                }
                return known != null && known > 0 ? known : manifestOfRoot(store, root, conversationId, config).pageCount();
            }

            @Override
            public DocumentManifest manifest() {
                return manifestOfRoot(store, root, conversationId, config);
            }
        };
        return SelectorResolver.resolve(root, unitOrNull, pagesOrNull, carriedSplitOrNull, config.hash(), facts);
    }

    /**
     * The resolver the conversation applies to a {@code storageRef} attachment that
     * names a part ({@code pages}, {@code unit}, {@code split} fields of
     * {@code attachment_N}) when a turn arrives: with {@code split} as the
     * receiving split.
     */
    public AttachmentContextExtractor.PartResolver partResolver(SplitConfig split) {
        return (storageRef, conversationId, unit, pages, carriedSplit) -> resolve(storageRef, conversationId, unit, pages,
                carriedSplit, split);
    }

    /**
     * The split an agent gets when it configures none — today every agent's,
     * because no {@code split.*} key is available to designers yet. The receiving
     * split at turn intake, where no task is known.
     */
    public SplitConfig deploymentSplit() {
        return splitConfig(EffectiveDocumentsConfig.resolve(null, settings).split());
    }

    /** The extractor's split configuration for an effective {@code split} block. */
    public static SplitConfig splitConfig(EffectiveDocumentsConfig.Split split) {
        if (split == null) {
            return SplitConfig.defaults();
        }
        SplitStrategy strategy = split.strategy() == null
                ? SplitStrategy.AUTO
                : SplitStrategy.valueOf(split.strategy().name().toUpperCase(Locale.ROOT));
        return new SplitConfig(strategy, split.pagesPerUnit(), split.maxUnitTokens(), split.minUnitTokens(), split.maxUnits());
    }

    /** Drops every cached manifest of one owner (erasure, owner change). */
    public void evictOwner(String ownerKey) {
        if (ownerKey != null) {
            cache.asMap().keySet().removeIf(k -> ownerKey.equals(k.ownerKey()));
        }
    }

    /** Drops every cached manifest of one root. */
    public void evictRoot(String rootRef) {
        if (rootRef != null) {
            cache.asMap().keySet().removeIf(k -> rootRef.equals(k.rootRef()));
        }
    }

    void onRootDeleted(@Observes AttachmentRootDeleted event) {
        try {
            evictRoot(event.rootRef());
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not evict the cached structure of a deleted attachment: %s", LogSanitizer.sanitize(e.getMessage()));
        }
    }

    void onOwnerChanged(@Observes AttachmentOwnerChanged event) {
        try {
            evictRoot(event.rootRef());
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not evict the cached structure of an attachment whose owner changed: %s",
                    LogSanitizer.sanitize(e.getMessage()));
        }
    }

    /** Number of cached manifests, for tests. */
    long cachedCount() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    private DocumentManifest manifestOfRoot(IAttachmentStore store, String root, String conversationId, SplitConfig config) {
        // The store answers first, on every call: a cache hit must never answer a
        // conversation that cannot read the file.
        OwnerRef owner;
        try {
            owner = store.ownerOf(root, conversationId);
        } catch (AttachmentStoreException e) {
            throw notFound(e);
        }
        if (owner == null) {
            throw new SelectorException(Reason.NOT_FOUND, NOT_FOUND);
        }
        String hash = config.hash();
        Key key = new Key(owner.ownerKey() != null ? owner.ownerKey() : "", root, hash);
        DocumentManifest cached = cache.getIfPresent(key);
        if (cached != null) {
            return withRoot(cached, root);
        }
        String unitId = MANIFEST_UNIT_PREFIX + hash;
        DocumentManifest manifest = loadStored(store, root, conversationId, unitId, hash);
        if (manifest == null) {
            requirePdf(store, root, conversationId);
            manifest = extract(store, root, conversationId, config);
            persist(store, root, conversationId, unitId, hash, manifest);
        }
        DocumentManifest refless = withRoot(manifest, null);
        cache.put(key, refless);
        return withRoot(refless, root);
    }

    private DocumentManifest loadStored(IAttachmentStore store, String root, String conversationId, String unitId, String hash) {
        try {
            Optional<IAttachmentStore.Attachment> stored = store.findDerived(root, Derivation.Kind.MANIFEST, unitId, conversationId);
            if (stored.isEmpty()) {
                return null;
            }
            StoredManifest envelope = JSON.readValue(store.load(stored.get().storageRef(), conversationId), StoredManifest.class);
            if (envelope == null || envelope.manifest() == null || !hash.equals(envelope.split())) {
                return null;
            }
            return envelope.manifest();
        } catch (AttachmentStoreException | IOException | RuntimeException e) {
            // Never authoritative: an unreadable stored manifest is rebuilt.
            LOGGER.debugf("A stored manifest could not be read and is rebuilt: %s", LogSanitizer.sanitize(e.getMessage()));
            return null;
        }
    }

    private DocumentManifest extract(IAttachmentStore store, String root, String conversationId, SplitConfig config) {
        byte[] bytes;
        try {
            bytes = store.load(root, conversationId);
        } catch (AttachmentStoreException e) {
            throw notFound(e);
        }
        DocumentManifest manifest;
        try {
            manifest = extractor.manifest(bytes, root, config, ExtractionLimits.defaults());
        } catch (UnreadableDocumentException e) {
            // The extractor's messages describe the file, never quote it.
            throw new SelectorException(Reason.UNREADABLE, e.getMessage(), e);
        }
        try {
            store.setPageCount(root, manifest.pageCount());
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not record the page count of an attachment: %s", LogSanitizer.sanitize(e.getMessage()));
        }
        return manifest;
    }

    private void persist(IAttachmentStore store, String root, String conversationId, String unitId, String hash,
                         DocumentManifest manifest) {
        try {
            byte[] json = JSON.writeValueAsBytes(new StoredManifest(STORED_VERSION, hash, withRoot(manifest, null)));
            store.storeDerived(json, MANIFEST_MIME, "manifest-" + hash + ".json", Derivation.of(root, Derivation.Kind.MANIFEST, unitId),
                    conversationId);
        } catch (AttachmentStoreException | JsonProcessingException | RuntimeException e) {
            // A cap refusal or a race with a delete: the manifest still answers this
            // call, it just is not kept.
            LOGGER.debugf("A manifest was not stored: %s", LogSanitizer.sanitize(e.getMessage()));
        }
    }

    private static DocumentManifest withRoot(DocumentManifest m, String root) {
        return new DocumentManifest(root, m.sha256(), m.pageCount(), m.splitUsed(), m.units(), m.embeddedFiles(), m.warnings());
    }

    private IAttachmentStore requireStore() {
        IAttachmentStore store = store();
        if (store == null) {
            throw new SelectorException(Reason.NOT_FOUND, NOT_FOUND);
        }
        return store;
    }

    private static String rootOf(IAttachmentStore store, String storageRef, String conversationId) {
        if (isBlank(storageRef) || isBlank(conversationId)) {
            throw new SelectorException(Reason.NOT_FOUND, NOT_FOUND);
        }
        try {
            String root = store.rootOf(storageRef, conversationId);
            if (isBlank(root)) {
                throw new SelectorException(Reason.NOT_FOUND, NOT_FOUND);
            }
            return root;
        } catch (AttachmentStoreException e) {
            throw notFound(e);
        }
    }

    /**
     * Whether a reference names a derived view rather than a root. Asked of the
     * store, not by comparing references: a store may accept more than one spelling
     * of a root's reference.
     */
    private static boolean isView(IAttachmentStore store, String storageRef, String conversationId) {
        try {
            return store.getDerivation(storageRef, conversationId).isPresent();
        } catch (AttachmentStoreException e) {
            throw notFound(e);
        }
    }

    private static void requirePdf(IAttachmentStore store, String root, String conversationId) {
        IAttachmentStore.Attachment meta;
        try {
            meta = store.getMetadata(root, conversationId);
        } catch (AttachmentStoreException e) {
            throw notFound(e);
        }
        if (meta == null || meta.mimeType() == null || !PDF.equals(meta.mimeType().strip().toLowerCase(Locale.ROOT))) {
            throw new SelectorException(Reason.NOT_PDF, "only a PDF can be sent in parts");
        }
    }

    private static SelectorException notFound(Exception cause) {
        // The same answer whether the file does not exist or cannot be read here.
        return new SelectorException(Reason.NOT_FOUND, NOT_FOUND, cause);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
