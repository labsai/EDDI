/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.context;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The approval-card context of one pause, <b>rendered once when the pause was
 * committed</b> and persisted with it (conversation snapshot field
 * {@code hitlApprovalContext}; group conversation likewise). Every surface —
 * the Slack card, the Manager, REST, MCP — shows this same text, so what the
 * approver in the channel saw is what an auditor reads later, whatever the
 * config, snippets or artifacts have become since.
 * <p>
 * Cleared with the pause bookmark; overwritten on every re-pause. Bounded: the
 * total text is capped by the renderer (default 8,000 characters).
 * <p>
 * {@link #getDigest()} is a SHA-256 over the items, so an audit entry can prove
 * which text was shown without copying it.
 *
 * @since 6.7.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RenderedApprovalContext {

    /**
     * {@link Item#getSource()} of a block from {@code hitlConfig.approvalContext}.
     */
    public static final String SOURCE_AGENT = "agent";
    /**
     * {@link Item#getSource()} of a block from the group's {@code approvalContext}.
     */
    public static final String SOURCE_GROUP = "group";
    /**
     * Prefix of {@link Item#getSource()} for a rule block: {@code rule:<match>}.
     */
    public static final String SOURCE_RULE_PREFIX = "rule:";

    /** {@link Item#getError()}: the template threw or timed out. */
    public static final String ERROR_RENDER_FAILED = "render_failed";
    /** {@link Item#getError()}: the template exceeded the output or loop limit. */
    public static final String ERROR_TOO_LARGE = "too_large";

    private List<Item> items = new ArrayList<>();
    private boolean truncated;
    private String digest;

    /** One rendered block. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Item {
        private String label;
        private String text;
        private boolean truncated;
        private String source;
        private String callId;
        private String error;

        public Item() {
        }

        public Item(String label, String text, boolean truncated, String source, String callId, String error) {
            this.label = label;
            this.text = text;
            this.truncated = truncated;
            this.source = source;
            this.callId = callId;
            this.error = error;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public void setTruncated(boolean truncated) {
            this.truncated = truncated;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getCallId() {
            return callId;
        }

        public void setCallId(String callId) {
            this.callId = callId;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }
    }

    public RenderedApprovalContext() {
    }

    /** Builds the context for {@code items} and computes its digest. */
    public static RenderedApprovalContext of(List<Item> items, boolean truncated) {
        RenderedApprovalContext context = new RenderedApprovalContext();
        context.items = items == null ? new ArrayList<>() : new ArrayList<>(items);
        context.truncated = truncated;
        context.digest = digestOf(context.items);
        return context;
    }

    /**
     * SHA-256 (hex) over label, text, source, call id and error of every item, each
     * field length-prefixed so two different item lists cannot hash alike.
     */
    public static String digestOf(List<Item> items) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (Item item : items) {
                for (String field : new String[]{item.label, item.text, item.source, item.callId, item.error}) {
                    byte[] bytes = (field == null ? "" : field).getBytes(StandardCharsets.UTF_8);
                    sha.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                    sha.update(bytes);
                }
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /** True when there is nothing to show. */
    @JsonIgnore
    public boolean isEmpty() {
        return items == null || items.isEmpty();
    }

    public List<Item> getItems() {
        return items;
    }

    public void setItems(List<Item> items) {
        this.items = items == null ? new ArrayList<>() : items;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public String getDigest() {
        return digest;
    }

    public void setDigest(String digest) {
        this.digest = digest;
    }
}
