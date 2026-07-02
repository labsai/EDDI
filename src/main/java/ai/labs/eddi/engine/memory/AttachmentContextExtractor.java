/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.model.Context;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts {@link Attachment} objects from the conversation context map.
 * <p>
 * Supports two input paths from the client:
 * <ul>
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
 * (mimeType, url/data, fileName).
 * <p>
 * Context keys matching {@code attachment_*} are extracted. Other context keys
 * are ignored.
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
