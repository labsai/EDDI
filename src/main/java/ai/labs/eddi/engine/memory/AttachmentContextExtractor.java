/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

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
