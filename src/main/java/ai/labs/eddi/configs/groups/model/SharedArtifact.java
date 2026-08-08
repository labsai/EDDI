/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A typed document a group discussion's members create and co-edit through the
 * artifact tools (I17, blackboard-lite) — state that lives <em>outside</em> the
 * dialogue, so a structured thing an agent produces stops being prose the next
 * agent re-parses.
 * <p>
 * Persisted in its <b>own collection</b> ({@code sharedartifacts}), never
 * embedded in the {@link GroupConversation} document: the discussion loop
 * persists that document whole from stale snapshots after each phase, which
 * would silently clobber concurrent artifact writes. Because artifacts have
 * their own collection, tools write them through the store directly — the F1
 * live-instance rule that governs task-list writes does not apply here.
 * <p>
 * Concurrency is deterministic compare-and-set on {@link #version} (see
 * {@code ISharedArtifactStore.updateIfVersion}): a stale writer gets a "re-read
 * and merge" rejection and retries — explicitly <em>not</em> an LLM fusion
 * arbiter, whose failure mode is a silent bad merge rather than a retry.
 *
 * @author ginccc
 */
public class SharedArtifact {

    /**
     * Hard ceiling on {@link #content}, in UTF-8 bytes. An LLM handed a write tool
     * can write in a loop; the cap bounds a single document while staying far above
     * any deliberated draft.
     */
    public static final int MAX_CONTENT_BYTES = 256 * 1024;

    /**
     * How many prior revisions ride on the document. A bounded tail, oldest dropped
     * — full history is the transcript's job, this exists so a bad edit one or two
     * turns back is recoverable without archaeology.
     */
    public static final int HISTORY_CAP = 10;

    /** The artifact's content type — how consumers should read {@link #content}. */
    public enum ArtifactType {
        TEXT, MARKDOWN, JSON
    }

    /** Editing lifecycle: DRAFT while being worked, FINAL once frozen. */
    public enum ArtifactStatus {
        DRAFT, FINAL
    }

    /**
     * One superseded revision. {@code version} is the version this content carried
     * while current.
     */
    public record ArtifactRevision(String content, String editorAgentId, long version, Instant at) {
    }

    private String id;
    private String groupConversationId;
    /**
     * The owning discussion's {@code userId}, stamped at creation so GDPR erasure
     * can sweep artifacts by user exactly like group conversations — independent of
     * whether the parent document still exists at erasure time.
     */
    private String ownerUserId;
    private String name;
    private ArtifactType type;
    private String content;
    /**
     * Monotonic edit counter, starting at 1 on creation — the CAS token every
     * update must present. Serialized as a JSON number; the store's version CAS
     * uses the numeric {@code storeIfFieldEquals} overload for exactly that reason.
     */
    private long version;
    private String lastEditorAgentId;
    private ArtifactStatus status = ArtifactStatus.DRAFT;
    private List<ArtifactRevision> history = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Applies an accepted edit: archives the current content into {@link #history}
     * (capped, oldest dropped), then installs the new content and bumps
     * {@link #version}. Pure in-memory mutation — persistence and the CAS happen at
     * the store.
     */
    public void applyEdit(String newContent, String editorAgentId, Instant at) {
        history.add(new ArtifactRevision(content, lastEditorAgentId, version, updatedAt != null ? updatedAt : createdAt));
        while (history.size() > HISTORY_CAP) {
            history.remove(0);
        }
        content = newContent;
        lastEditorAgentId = editorAgentId;
        version = version + 1;
        updatedAt = at;
    }

    /**
     * UTF-8 byte length of {@code content}, for the {@link #MAX_CONTENT_BYTES} cap.
     */
    public static int contentBytes(String content) {
        return content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getGroupConversationId() {
        return groupConversationId;
    }

    public void setGroupConversationId(String groupConversationId) {
        this.groupConversationId = groupConversationId;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(String ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ArtifactType getType() {
        return type;
    }

    public void setType(ArtifactType type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public String getLastEditorAgentId() {
        return lastEditorAgentId;
    }

    public void setLastEditorAgentId(String lastEditorAgentId) {
        this.lastEditorAgentId = lastEditorAgentId;
    }

    public ArtifactStatus getStatus() {
        return status;
    }

    public void setStatus(ArtifactStatus status) {
        this.status = status;
    }

    public List<ArtifactRevision> getHistory() {
        return history;
    }

    public void setHistory(List<ArtifactRevision> history) {
        this.history = history != null ? history : new ArrayList<>();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
