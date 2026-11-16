/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.model;

/**
 * Which conversation controls a chat window offers for this agent. Each switch
 * is optional: absent ({@code null}) keeps the client's behaviour from before
 * the setting existed, {@code false} hides the control. {@link #getRawHtml()
 * rawHtml} is the exception — it is <strong>off unless set to
 * {@code true}</strong>.
 * <p>
 * These are presentation switches, served by {@code GET
 * /agents/{agentId}/profile}. They do not change what the server accepts:
 * hiding undo does not disable {@code POST /agents/{id}/undo}, and hiding
 * ratings does not stop the rating endpoints, which follow
 * {@code ratingConfig}.
 *
 * @since 6.7.0
 */
public class ConversationControls {

    private Boolean undo;
    private Boolean redo;
    private Boolean attachments;
    private Boolean rating;
    private Boolean quickReplies;
    private Boolean streaming;
    private Boolean rawHtml;

    /** Show the undo control; absent shows it. */
    public Boolean getUndo() {
        return undo;
    }

    public void setUndo(Boolean undo) {
        this.undo = undo;
    }

    /** Show the redo control; absent shows it. */
    public Boolean getRedo() {
        return redo;
    }

    public void setRedo(Boolean redo) {
        this.redo = redo;
    }

    /** Offer file attachments in the composer; absent offers them. */
    public Boolean getAttachments() {
        return attachments;
    }

    public void setAttachments(Boolean attachments) {
        this.attachments = attachments;
    }

    /**
     * Show the rating controls {@code ratingConfig} enables; absent shows them.
     * {@code true} cannot switch ratings on — that is {@code ratingConfig}.
     */
    public Boolean getRating() {
        return rating;
    }

    public void setRating(Boolean rating) {
        this.rating = rating;
    }

    /** Render quick replies as buttons; absent renders them. */
    public Boolean getQuickReplies() {
        return quickReplies;
    }

    public void setQuickReplies(Boolean quickReplies) {
        this.quickReplies = quickReplies;
    }

    /** Stream replies token by token; absent streams. */
    public Boolean getStreaming() {
        return streaming;
    }

    public void setStreaming(Boolean streaming) {
        this.streaming = streaming;
    }

    /**
     * Render HTML in the agent's replies (through the client's sanitizer).
     * <strong>Absent or {@code false} shows HTML as text.</strong> Before 6.7.0 the
     * Chat UI rendered HTML for every agent; the Manager never did.
     */
    public Boolean getRawHtml() {
        return rawHtml;
    }

    public void setRawHtml(Boolean rawHtml) {
        this.rawHtml = rawHtml;
    }
}
