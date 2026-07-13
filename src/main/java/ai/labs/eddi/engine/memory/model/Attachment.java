/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Lightweight reference to a binary attachment in conversation memory.
 * <p>
 * Attachments are NOT stored inline (no base64). The binary payload lives in
 * GridFS or external object storage; this record carries only the metadata
 * reference. Behavior rules match on {@code mimeType}, {@code sizeBytes}, and
 * {@code metadata} — never on raw bytes.
 * <p>
 * Attachments inherit the conversation's TTL and are cleaned up automatically
 * when the conversation expires.
 *
 * @since 6.0.0
 */
public class Attachment {
    private String id;
    private String mimeType;
    private String fileName;
    private long sizeBytes;
    private String storageRef;

    /** URL reference for externally hosted attachments (not stored in EDDI). */
    private String url;

    /**
     * Inline base64-encoded data. Used for context-based input only; the payload
     * lives in memory for the duration of the turn but is NEVER persisted to
     * MongoDB — {@link #getBase64Data()} is {@link JsonIgnore}d so Jackson's
     * getter-based serialization skips it. The {@code transient} keyword alone does
     * not stop Jackson (no {@code PROPAGATE_TRANSIENT_MARKER} configured).
     * Consumers should decode this and pass it to the LLM.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private transient String base64Data;

    private Map<String, String> metadata;
    private Instant createdAt;

    public Attachment() {
        this.id = UUID.randomUUID().toString();
        this.metadata = new HashMap<>();
        this.createdAt = Instant.now();
    }

    public Attachment(String mimeType, String fileName, long sizeBytes, String storageRef) {
        this();
        this.mimeType = mimeType;
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
        this.storageRef = storageRef;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getStorageRef() {
        return storageRef;
    }

    public void setStorageRef(String storageRef) {
        this.storageRef = storageRef;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    @JsonIgnore
    public String getBase64Data() {
        return base64Data;
    }

    public void setBase64Data(String base64Data) {
        this.base64Data = base64Data;
    }

    /**
     * Determine the content source. Returns the most specific source available:
     * storageRef (uploaded file) > url (external link) > base64Data (inline).
     */
    public ContentSource getContentSource() {
        if (storageRef != null)
            return ContentSource.STORED;
        if (url != null)
            return ContentSource.URL;
        if (base64Data != null)
            return ContentSource.BASE64;
        return ContentSource.NONE;
    }

    public enum ContentSource {
        STORED, URL, BASE64, NONE
    }

    /**
     * Check if this attachment's MIME type matches a pattern. Supports wildcards:
     * "image/*" matches "image/png", "image/jpeg", etc.
     */
    public boolean matchesMimeType(String pattern) {
        if (pattern == null || mimeType == null) {
            return false;
        }
        if (pattern.equals("*/*") || pattern.equals(mimeType)) {
            return true;
        }
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return mimeType.startsWith(prefix);
        }
        return false;
    }

    @Override
    public String toString() {
        return "Attachment{id='" + id + "', mimeType='" + mimeType +
                "', fileName='" + fileName + "', sizeBytes=" + sizeBytes + '}';
    }
}
