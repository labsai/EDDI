/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.attachments.AttachmentSelector;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.documents.SelectorException;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.model.Context;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts {@link Attachment} objects from the conversation context map.
 * <p>
 * Supports three input paths from the client, checked in this order:
 * <ul>
 * <li><strong>Stored reference</strong>: {@code {"attachment_0":
 * Context(type=object, value={"storageRef":"...","fileName":"a.pdf"})}} — an
 * uploaded blob; the MIME type and size are resolved from the attachment store
 * (see {@link #resolveAndGuard}), and any client-sent MIME type is
 * ignored.</li>
 * <li><strong>URL reference</strong>: {@code {"attachment_0":
 * Context(type=object,
 * value={"mimeType":"image/png","url":"https://..."})}}</li>
 * <li><strong>Base64 inline</strong>: {@code {"attachment_0":
 * Context(type=object,
 * value={"mimeType":"image/png","data":"iVBOR...","fileName":"icon.png"})}}</li>
 * </ul>
 * <p>
 * The {@link Context#getValue()} is expected to be a
 * {@code Map<String, Object>} containing the attachment fields directly
 * (storageRef, or mimeType with url/data, plus an optional fileName). An entry
 * that is not a map, or has no usable field, is skipped with a log line.
 * <p>
 * Context keys matching {@code attachment_*} are extracted. Other context keys
 * are ignored.
 * <p>
 * A stored reference may name a <strong>part</strong> of a PDF with the string
 * fields {@code pages} ({@code "<from>-<to>"}, authoritative), {@code unit} (a
 * chapter label such as {@code u3}) and {@code split} (the hash of the split
 * configuration the label belongs to). They are kept in
 * {@link Attachment#getMetadata()} under the {@link AttachmentSelector}
 * constants and resolved by
 * {@link #resolveAndGuard(List, IAttachmentStore, String, int, PartResolver)}.
 * Nothing else is read into the metadata: a client cannot mark its attachment
 * as a derived view.
 *
 * @since 6.0.0
 */
public final class AttachmentContextExtractor {

    private static final Logger LOGGER = Logger.getLogger(AttachmentContextExtractor.class);

    /**
     * Context keys with this prefix carry attachment references (attachment_0,
     * attachment_1, …).
     */
    public static final String ATTACHMENT_PREFIX = "attachment_";

    /** Inline base64 payload field inside an attachment context value map. */
    public static final String FIELD_DATA = "data";

    /** Uploaded-blob reference field inside an attachment context value map. */
    public static final String FIELD_STORAGE_REF = "storageRef";

    /** Page-range field of a stored reference: {@code "<from>-<to>"}. */
    public static final String FIELD_PAGES = "pages";

    /** Unit-label field of a stored reference, e.g. {@code u3}. */
    public static final String FIELD_UNIT = "unit";

    /** Split-hash field of a stored reference. */
    public static final String FIELD_SPLIT = "split";

    /** Longest part field read from a client; anything longer is cut. */
    static final int MAX_PART_FIELD_CHARS = 64;

    /**
     * Field of the persisted map form that holds {@link Attachment#getMetadata()}.
     */
    private static final String FIELD_METADATA = "metadata";

    /** Default per-turn cap on the number of attachments forwarded. */
    public static final int DEFAULT_MAX_ATTACHMENTS_PER_TURN = 5;

    private AttachmentContextExtractor() {
        // non-instantiable utility
    }

    /**
     * Extract attachments from the context map. Returns an empty list if no
     * attachment context variables are present.
     *
     * @param contexts
     *            the context map from the conversation input
     * @return list of parsed Attachment objects (never null)
     */
    public static List<Attachment> extractAttachments(Map<String, Context> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return Collections.emptyList();
        }

        List<Attachment> attachments = new ArrayList<>();

        for (Map.Entry<String, Context> entry : contexts.entrySet()) {
            if (!entry.getKey().startsWith(ATTACHMENT_PREFIX)) {
                continue;
            }

            Context ctx = entry.getValue();
            if (ctx == null || ctx.getValue() == null) {
                continue;
            }

            try {
                Attachment attachment = parseAttachment(entry.getKey(), ctx);
                if (attachment != null) {
                    attachments.add(attachment);
                }
            } catch (Exception e) {
                LOGGER.warnv("Failed to parse attachment from context key '{0}': {1}",
                        entry.getKey(), e.getMessage());
            }
        }

        return attachments;
    }

    @SuppressWarnings("unchecked")
    private static Attachment parseAttachment(String contextKey, Context ctx) {
        Object value = ctx.getValue();

        if (!(value instanceof Map<?, ?> map)) {
            LOGGER.debugv("Attachment context '{0}' value is not a Map, skipping", contextKey);
            return null;
        }

        Map<String, Object> attachMap = (Map<String, Object>) map;

        // Stored-reference path (highest precedence). The client sends only
        // {storageRef} (+ an optional fileName display hint); the authoritative
        // MIME type and size are resolved from validated store metadata later
        // (see resolveAndGuard), so no client-supplied MIME is trusted for
        // stored blobs.
        String storageRef = getStringField(attachMap, FIELD_STORAGE_REF);
        if (storageRef != null && !storageRef.isBlank()) {
            Attachment attachment = new Attachment();
            attachment.setStorageRef(storageRef);
            attachment.setFileName(getStringField(attachMap, "fileName"));
            // A part of the file: only these three fields, so a client can neither mark
            // its attachment derived nor name a root for it.
            copyPartField(attachMap, FIELD_PAGES, attachment, AttachmentSelector.META_PAGES);
            copyPartField(attachMap, FIELD_UNIT, attachment, AttachmentSelector.META_UNIT);
            copyPartField(attachMap, FIELD_SPLIT, attachment, AttachmentSelector.META_SPLIT);
            return attachment;
        }

        String mimeType = getStringField(attachMap, "mimeType");
        if (mimeType == null || mimeType.isBlank()) {
            LOGGER.warnv("Attachment context '{0}' missing required 'mimeType' field", contextKey);
            return null;
        }

        Attachment attachment = new Attachment();
        attachment.setMimeType(mimeType);
        attachment.setFileName(getStringField(attachMap, "fileName"));

        // URL reference path
        String url = getStringField(attachMap, "url");
        if (url != null && !url.isBlank()) {
            attachment.setUrl(url);
            return attachment;
        }

        // Base64 inline path
        String data = getStringField(attachMap, FIELD_DATA);
        if (data != null && !data.isBlank()) {
            attachment.setBase64Data(data);
            // Estimate size from base64 length (3/4 of encoded length)
            attachment.setSizeBytes((long) (data.length() * 3.0 / 4.0));
            return attachment;
        }

        LOGGER.warnv("Attachment context '{0}' has no 'url' or 'data' field", contextKey);
        return null;
    }

    private static String getStringField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String s ? s : null;
    }

    private static void copyPartField(Map<String, Object> map, String field, Attachment attachment, String metadataKey) {
        String value = getStringField(map, field);
        if (value == null || value.isBlank()) {
            return;
        }
        String stripped = value.strip();
        if (stripped.length() > MAX_PART_FIELD_CHARS) {
            // Too long to be a page range, unit or split. Kept, cut and marked so it can
            // never parse, so the part is refused rather than silently widened to the
            // whole file or narrowed to a shorter range.
            stripped = stripped.substring(0, MAX_PART_FIELD_CHARS - 1) + "…";
        }
        if (attachment.getMetadata() == null) {
            attachment.setMetadata(new LinkedHashMap<>());
        }
        attachment.getMetadata().put(metadataKey, stripped);
    }

    /** Whether an attachment names a part of its file. */
    static boolean hasPart(Attachment att) {
        Map<String, String> m = att.getMetadata();
        return m != null && (m.get(AttachmentSelector.META_PAGES) != null || m.get(AttachmentSelector.META_UNIT) != null
                || m.get(AttachmentSelector.META_SPLIT) != null);
    }

    /**
     * Resolves the part of a stored file an incoming attachment names into a
     * pages-authoritative selector. Implemented by
     * {@code DocumentStructureService#partResolver}.
     */
    @FunctionalInterface
    public interface PartResolver {
        /**
         * @return the selector, with {@code pages} set
         * @throws SelectorException
         *             with a model-safe message when the part cannot be resolved
         */
        AttachmentSelector resolve(String storageRef, String conversationId, String unit, String pages, String split);
    }

    /**
     * Result of resolving parsed attachments against the store and per-turn cap.
     *
     * @param attachments
     *            the forwardable attachments (stored refs resolved)
     * @param errors
     *            human-readable notes for dropped/failed attachments (never silent)
     */
    public record ExtractionResult(List<Attachment> attachments, List<String> errors) {
    }

    /**
     * Resolve server-side metadata for {@link Attachment.ContentSource#STORED}
     * attachments and enforce the per-turn count cap.
     * <p>
     * For each stored reference, {@link IAttachmentStore#getMetadata} supplies the
     * authoritative MIME type / size (owner-or-grant authorized), so behavior rules
     * and the forwarder see the truth rather than client-declared values. URL and
     * inline attachments pass through unchanged. Anything dropped — an unresolvable
     * reference, a missing store, or an attachment beyond the per-turn cap — is
     * reported in {@link ExtractionResult#errors()} and never silently discarded.
     *
     * @param parsed
     *            attachments from {@link #extractAttachments(Map)}
     * @param store
     *            the attachment store (may be null if none configured)
     * @param conversationId
     *            the requesting conversation (authorization boundary)
     * @param maxPerTurn
     *            per-turn cap; non-positive means unlimited
     * @return resolved attachments plus error notes
     */
    public static ExtractionResult resolveAndGuard(List<Attachment> parsed, IAttachmentStore store,
                                                   String conversationId, int maxPerTurn) {
        return resolveAndGuard(parsed, store, conversationId, maxPerTurn, null);
    }

    /**
     * {@link #resolveAndGuard(List, IAttachmentStore, String, int)}, resolving the
     * part a stored reference names ({@code pages}, {@code unit}, {@code split}).
     * <p>
     * Pages win: a part with {@code pages} keeps them (checked against the
     * document); a {@code unit} without pages becomes that unit's pages under the
     * receiving split, once, here — from then on it carries pages and split. A unit
     * with another split's hash and no pages, an unknown unit, or pages outside the
     * document is reported in {@link ExtractionResult#errors()} and the attachment
     * is dropped; it never falls back to the whole file. Without a resolver, any
     * part is dropped the same way. A part counts as one attachment against
     * {@code maxPerTurn}, like any other.
     *
     * @param partResolver
     *            resolves parts; {@code null} refuses every part
     */
    public static ExtractionResult resolveAndGuard(List<Attachment> parsed, IAttachmentStore store,
                                                   String conversationId, int maxPerTurn, PartResolver partResolver) {
        List<Attachment> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int cap = maxPerTurn > 0 ? maxPerTurn : Integer.MAX_VALUE;

        for (Attachment att : parsed) {
            if (out.size() >= cap) {
                errors.add("Attachment '" + displayName(att) + "' skipped: per-turn limit of "
                        + maxPerTurn + " attachment(s) reached.");
                continue;
            }
            if (att.getContentSource() == Attachment.ContentSource.STORED) {
                if (store == null) {
                    errors.add("Stored attachment '" + att.getStorageRef()
                            + "' could not be resolved: no attachment store is configured.");
                    continue;
                }
                try {
                    IAttachmentStore.Attachment meta = store.getMetadata(att.getStorageRef(), conversationId);
                    att.setMimeType(meta.mimeType());
                    if (att.getFileName() == null) {
                        att.setFileName(meta.filename());
                    }
                    att.setSizeBytes(meta.sizeBytes());
                    if (hasPart(att)) {
                        String refusal = resolvePart(att, conversationId, partResolver);
                        if (refusal != null) {
                            errors.add("Stored attachment '" + att.getStorageRef() + "': the requested part was not sent: "
                                    + refusal + ".");
                            continue;
                        }
                    }
                    out.add(att);
                } catch (IAttachmentStore.AttachmentStoreException e) {
                    errors.add("Stored attachment '" + att.getStorageRef()
                            + "' could not be resolved: " + e.getMessage());
                }
            } else {
                out.add(att);
            }
        }
        return new ExtractionResult(out, errors);
    }

    /**
     * Replaces the part fields of {@code att} with the resolved selector's.
     *
     * @return {@code null} on success, else a model-safe reason
     */
    private static String resolvePart(Attachment att, String conversationId, PartResolver resolver) {
        Map<String, String> metadata = att.getMetadata();
        if (resolver == null) {
            return "parts of a file cannot be sent here";
        }
        AttachmentSelector selector;
        try {
            selector = resolver.resolve(att.getStorageRef(), conversationId, metadata.get(AttachmentSelector.META_UNIT),
                    metadata.get(AttachmentSelector.META_PAGES), metadata.get(AttachmentSelector.META_SPLIT));
        } catch (SelectorException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            LOGGER.warnv("Could not resolve the part of a stored attachment: {0}", e.getClass().getSimpleName());
            return "it could not be resolved";
        }
        if (selector == null || selector.pages() == null) {
            return "it could not be resolved";
        }
        metadata.remove(AttachmentSelector.META_PAGES);
        metadata.remove(AttachmentSelector.META_UNIT);
        metadata.remove(AttachmentSelector.META_SPLIT);
        metadata.putAll(selector.toMetadata());
        att.setStorageRef(selector.storageRef());
        return null;
    }

    /**
     * Attachments from the <em>earlier</em> turns of this conversation that can
     * still be fetched, most recent first and de-duplicated by storage reference.
     * <p>
     * An attachment is inlined into the LLM message only on the turn it arrives —
     * re-sending a document on every subsequent turn would burn the context window.
     * Without this lookup, though, a follow-up question about a file uploaded two
     * turns ago reaches a model that has no trace of it, and the
     * {@code readAttachment} tool — which reads per <em>conversation</em>, not per
     * turn — is never even offered. Callers use this to keep an uploaded file
     * reachable for the rest of the conversation.
     * <p>
     * Only blob-backed attachments (a {@code storageRef}) are returned: that is
     * exactly what {@link ai.labs.eddi.engine.attachments.IAttachmentStore} can
     * serve later. An inline base64 payload is deliberately never persisted, and a
     * URL reference is not in the store either, so neither can be re-read on a
     * later turn — listing them would only advertise a file the tool then fails to
     * fetch.
     * <p>
     * Read straight from conversation memory (no attachment-store round trip), so
     * this is safe to call on every turn.
     *
     * @param memory
     *            the conversation memory for the current turn (may be null)
     * @return earlier turns' still-retrievable attachments, newest turn first
     *         (never null)
     */
    public static List<Attachment> attachmentsFromPreviousTurns(IConversationMemory memory) {
        if (memory == null) {
            return Collections.emptyList();
        }
        IConversationMemory.IConversationStepStack previousSteps = memory.getPreviousSteps();
        if (previousSteps == null || previousSteps.size() == 0) {
            return Collections.emptyList();
        }

        // Keyed by identity so the same file re-sent across turns is listed once,
        // insertion-ordered so the most recent turn's attachments come first.
        Map<String, Attachment> unique = new LinkedHashMap<>();
        for (int i = 0; i < previousSteps.size(); i++) {
            // A turn an input guardrail refused keeps its files out of every later
            // prompt: no re-inlined image, no "shared earlier" note, no readAttachment.
            if (ConversationLogGenerator.isInputWithheld(previousSteps.get(i))) {
                continue;
            }
            // Exact-match read: "attachments" is a prefix of the
            // attachments:extracts / attachments:errors keys the forwarder persists,
            // so a prefix scan would return the wrong entry.
            IData<List<?>> data = previousSteps.get(i).getData(MemoryKeys.ATTACHMENTS);
            if (data == null || data.getResult() == null) {
                continue;
            }
            for (Object value : data.getResult()) {
                Attachment attachment = asAttachment(value);
                // Retrievable only: see the note on inline/URL attachments above.
                if (attachment != null && attachment.getStorageRef() != null) {
                    unique.putIfAbsent(identity(attachment), attachment);
                }
            }
        }
        return List.copyOf(unique.values());
    }

    /**
     * The storage references of the files of earlier turns an input guardrail
     * refused ({@link ConversationLogGenerator#isInputWithheld}) — what the
     * {@code readAttachment} tool must not serve, although the attachment store
     * still holds them for the conversation. A file that was also attached on a
     * turn that was not refused stays readable.
     * <p>
     * Decided on roots: a recorded view of a file
     * ({@link AttachmentSelector#META_ROOT}) counts for its root, so a child of a
     * withheld file is withheld too. The set holds the roots, and a view's own
     * reference where it differs.
     */
    public static Set<String> withheldStorageRefs(IConversationMemory memory) {
        return withheldStorageRefs(memory, false);
    }

    /**
     * {@link #withheldStorageRefs(IConversationMemory)}, plus the files of the
     * current step when an input guardrail refused it — for a tool that can run
     * later in the same turn than the refusal, such as sharing a file with a
     * delegate. A file accepted on an earlier turn stays shareable.
     */
    public static Set<String> withheldStorageRefsIncludingCurrentStep(IConversationMemory memory) {
        return withheldStorageRefs(memory, true);
    }

    private static Set<String> withheldStorageRefs(IConversationMemory memory, boolean includeCurrentStep) {
        if (memory == null) {
            return Set.of();
        }
        List<IConversationMemory.IConversationStep> steps = new ArrayList<>();
        IConversationMemory.IConversationStepStack previousSteps = memory.getPreviousSteps();
        if (previousSteps != null) {
            for (int i = 0; i < previousSteps.size(); i++) {
                steps.add(previousSteps.get(i));
            }
        }
        if (includeCurrentStep && memory.getCurrentStep() != null) {
            steps.add(memory.getCurrentStep());
        }
        Set<String> withheld = new HashSet<>();
        Set<String> accepted = new HashSet<>();
        for (IConversationMemory.IConversationStep step : steps) {
            IData<List<?>> data = step.getData(MemoryKeys.ATTACHMENTS);
            if (data == null || data.getResult() == null) {
                continue;
            }
            Set<String> target = ConversationLogGenerator.isInputWithheld(step) ? withheld : accepted;
            for (Object value : data.getResult()) {
                Attachment attachment = asAttachment(value);
                if (attachment != null && attachment.getStorageRef() != null) {
                    target.add(attachment.getStorageRef());
                    // A recorded view only ever withholds its root, never releases it: the
                    // root itself must be attached on an accepted turn for that.
                    String root = rootRefOf(attachment);
                    if (root != null && target == withheld) {
                        target.add(root);
                    }
                }
            }
        }
        withheld.removeAll(accepted);
        return withheld;
    }

    /**
     * Read an ATTACHMENTS memory entry into {@link Attachment}s, accepting both the
     * live objects and the map form a step comes back as once it has been through
     * the conversation store.
     * <p>
     * Use this for <em>every</em> read of {@link MemoryKeys#ATTACHMENTS}, including
     * the current step: a HITL resume re-enters the same step of a conversation
     * reloaded from the store, so "current step" does not imply "live objects". A
     * bare {@code instanceof Attachment} filter silently drops everything there.
     * <p>
     * No retrievability filter — a current-turn attachment may legitimately be an
     * inline or URL payload, both of which are forwardable on the turn they arrive.
     *
     * @param rawStepData
     *            the value of an ATTACHMENTS data entry (may be null)
     * @return the attachments it holds, in order (never null)
     */
    public static List<Attachment> attachmentsFrom(List<?> rawStepData) {
        if (rawStepData == null || rawStepData.isEmpty()) {
            return Collections.emptyList();
        }
        List<Attachment> attachments = new ArrayList<>();
        for (Object value : rawStepData) {
            Attachment attachment = asAttachment(value);
            if (attachment != null) {
                attachments.add(attachment);
            }
        }
        return attachments;
    }

    /**
     * Coerce one stored ATTACHMENTS entry back into an {@link Attachment}.
     * <p>
     * The current turn's entries are the live objects, but every earlier turn has
     * been through the conversation store, and
     * {@code ConversationMemorySnapshot.ResultSnapshot#getResult()} is a bare
     * {@code Object} — so Jackson hands those back as plain maps, not
     * {@code Attachment}s. Both the Mongo and Postgres stores repair only
     * {@code context*} entries on load ({@code fixContextTypes}); everything else,
     * this key included, stays a map. An {@code instanceof Attachment} test alone
     * therefore matches nothing from turn two onwards, which is exactly when this
     * lookup matters.
     * <p>
     * {@code base64Data} is {@code @JsonIgnore}d and never persisted, so a
     * recovered attachment is a stored/URL reference — which is all the
     * readAttachment tool needs.
     *
     * @param value
     *            a live Attachment, or the map form read back from the store
     * @return the attachment, or {@code null} if the entry is neither
     */
    private static Attachment asAttachment(Object value) {
        if (value instanceof Attachment attachment) {
            return attachment;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }

        String storageRef = stringValue(map, FIELD_STORAGE_REF);
        String url = stringValue(map, "url");
        String mimeType = stringValue(map, "mimeType");
        if (storageRef == null && url == null && mimeType == null) {
            // Not an attachment shape — leave it alone rather than inventing one.
            return null;
        }

        Attachment attachment = new Attachment();
        attachment.setStorageRef(storageRef);
        attachment.setUrl(url);
        attachment.setMimeType(mimeType);
        attachment.setFileName(stringValue(map, "fileName"));
        if (map.get("sizeBytes") instanceof Number sizeBytes) {
            attachment.setSizeBytes(sizeBytes.longValue());
        }
        // The part a selector names, and a recorded view's marks: a resumed turn
        // must not turn "pages 12-19" into the whole file.
        if (map.get(FIELD_METADATA) instanceof Map<?, ?> metadata) {
            Map<String, String> copy = new LinkedHashMap<>();
            metadata.forEach((k, v) -> {
                if (k instanceof String key && v instanceof String text) {
                    copy.put(key, text);
                }
            });
            attachment.setMetadata(copy);
        }
        return attachment;
    }

    /**
     * The root a recorded derived view names
     * ({@link AttachmentSelector#META_ROOT}), or {@code null}.
     */
    private static String rootRefOf(Attachment attachment) {
        Map<String, String> metadata = attachment.getMetadata();
        String root = metadata != null ? metadata.get(AttachmentSelector.META_ROOT) : null;
        return root != null && !root.isBlank() ? root : null;
    }

    /**
     * The derived selectors recorded on this conversation's steps — the current
     * step and every earlier one, skipping turns an input guardrail refused:
     * {@link MemoryKeys#ATTACHMENTS} entries marked
     * {@link AttachmentSelector#META_DERIVED}. Memory only; what
     * {@code ConversationListing.of} lists beside the roots, after checking each
     * against the store.
     */
    public static List<Attachment> recordedDerived(IConversationMemory memory) {
        if (memory == null) {
            return Collections.emptyList();
        }
        List<IConversationMemory.IConversationStep> steps = new ArrayList<>();
        if (memory.getCurrentStep() != null) {
            steps.add(memory.getCurrentStep());
        }
        IConversationMemory.IConversationStepStack previous = memory.getPreviousSteps();
        for (int i = 0; previous != null && i < previous.size(); i++) {
            steps.add(previous.get(i));
        }
        Map<String, Attachment> unique = new LinkedHashMap<>();
        for (IConversationMemory.IConversationStep step : steps) {
            if (ConversationLogGenerator.isInputWithheld(step)) {
                continue;
            }
            IData<List<?>> data = step.getData(MemoryKeys.ATTACHMENTS);
            if (data == null || data.getResult() == null) {
                continue;
            }
            for (Object value : data.getResult()) {
                Attachment attachment = asAttachment(value);
                if (attachment != null && attachment.getStorageRef() != null && attachment.getMetadata() != null
                        && "true".equals(attachment.getMetadata().get(AttachmentSelector.META_DERIVED))) {
                    unique.putIfAbsent(identity(attachment), attachment);
                }
            }
        }
        return List.copyOf(unique.values());
    }

    /** Non-blank string field from a map of unknown key/value types. */
    private static String stringValue(Map<?, ?> map, String key) {
        return map.get(key) instanceof String s && !s.isBlank() ? s : null;
    }

    /**
     * Stable de-duplication key: the storage ref and the part it names if there is
     * one (two parts of one file are two attachments), else the URL or the name.
     */
    static String identity(Attachment att) {
        if (att.getStorageRef() != null) {
            Map<String, String> m = att.getMetadata();
            String pages = m != null ? m.get(AttachmentSelector.META_PAGES) : null;
            String unit = m != null ? m.get(AttachmentSelector.META_UNIT) : null;
            if (pages != null) {
                return "ref:" + att.getStorageRef() + "#p" + pages;
            }
            if (unit != null) {
                return "ref:" + att.getStorageRef() + "#u" + unit;
            }
            return "ref:" + att.getStorageRef();
        }
        if (att.getUrl() != null) {
            return "url:" + att.getUrl();
        }
        return "name:" + displayName(att);
    }

    private static String displayName(Attachment att) {
        if (att.getFileName() != null) {
            return att.getFileName();
        }
        if (att.getStorageRef() != null) {
            return att.getStorageRef();
        }
        return att.getMimeType() != null ? att.getMimeType() : "attachment";
    }

    /**
     * Return a metadata-only copy of an {@code attachment_*} context whose value
     * map carries an inline base64 {@link #FIELD_DATA} payload; every other context
     * — and payload-free attachment contexts such as URL references — is returned
     * unchanged.
     * <p>
     * Callers use this to build the <em>persisted</em> copy of the context (step
     * data and {@code context.*} conversation output) so the raw base64 never lands
     * in the Mongo conversation document (~1.33&times; file size per turn against
     * the 16&nbsp;MB limit) and is never exposed via
     * {@code {context.attachment_*.data}} templates. The live payload has already
     * been captured into ATTACHMENTS memory for the turn by
     * {@link #extractAttachments(Map)} reading the original context map, so LLM
     * forwarding is unaffected. Mirrors the secret-input scrubbing pattern.
     *
     * @param contextKey
     *            the context key (only {@code attachment_*} keys are scrubbed)
     * @param ctx
     *            the original context (may be null)
     * @return a scrubbed copy when a payload is present, otherwise {@code ctx}
     *         unchanged
     */
    public static Context scrubInlinePayload(String contextKey, Context ctx) {
        if (contextKey == null || !contextKey.startsWith(ATTACHMENT_PREFIX) || ctx == null) {
            return ctx;
        }
        if (!(ctx.getValue() instanceof Map<?, ?> value) || !value.containsKey(FIELD_DATA)) {
            return ctx;
        }
        Map<Object, Object> scrubbed = new LinkedHashMap<>(value);
        scrubbed.remove(FIELD_DATA);
        return new Context(ctx.getType(), scrubbed);
    }
}
