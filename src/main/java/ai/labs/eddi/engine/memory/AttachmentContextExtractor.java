package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.model.Context;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
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
    private static final String ATTACHMENT_PREFIX = "attachment_";

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
        String data = getStringField(attachMap, "data");
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
}
