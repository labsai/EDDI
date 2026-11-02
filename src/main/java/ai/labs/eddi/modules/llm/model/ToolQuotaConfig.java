/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent-designer configuration for {@code toolQuotas}: how often one subject
 * (normally the end user) may make a tool run within a fixed time window,
 * counted across conversations.
 * <p>
 * It complements, and does not replace, the other tool controls. A rate limit
 * ({@code toolRateLimits}) is a per-minute allowance per conversation, a
 * conversation budget ({@code maxBudgetPerConversation}) is reset by starting a
 * new conversation, and a tenant quota is shared by everyone. A tool quota is
 * the one that follows the person: "at most 100 content improvements per user
 * per day".
 * <p>
 * Absent means no quota at all &mdash; no store is read. See
 * {@code docs/langchain.md#per-subject-tool-quotas-toolquotas}.
 *
 * @since 6.5.0
 */
public class ToolQuotaConfig {

    /**
     * Longest subject kept verbatim; a longer rendered subject is shortened to a
     * prefix plus a digest of the whole, so two long subjects never share a
     * counter.
     */
    public static final int MAX_SUBJECT_LENGTH = 256;

    /** Shortest window: one minute. */
    public static final String MIN_WINDOW = "PT1M";

    /** Longest window: 31 days. */
    public static final String MAX_WINDOW = "P31D";

    /**
     * Template (the same syntax and data model as a system prompt) that renders to
     * the identifier counted against, for example
     * {@code {context.userInfo.userIds[0]}}. Optional: the default is the
     * conversation's user id.
     * <p>
     * A managed conversation's user id is composite
     * ({@code <user>-<company>-<program>}), so the default counts per program; use
     * this to count per person. A template that renders blank falls back to the
     * conversation's user id.
     */
    private String subject;

    /**
     * Rules by tool name, keyed like {@code toolRateLimits}: the dispatch name or
     * the built-in slug, the dispatch name winning. Works for every tool source,
     * HTTP-call tools included.
     */
    private Map<String, Rule> tools = new LinkedHashMap<>();

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public Map<String, Rule> getTools() {
        return tools;
    }

    public void setTools(Map<String, Rule> tools) {
        this.tools = tools;
    }

    /**
     * One tool's allowance.
     */
    public static class Rule {

        /** Calls permitted per window; a positive integer. */
        private Integer limit;

        /**
         * ISO-8601 duration of the window, between {@link #MIN_WINDOW} and
         * {@link #MAX_WINDOW}, e.g. {@code P1D}. Windows are fixed and aligned to the
         * UTC epoch, so {@code P1D} resets at 00:00 UTC.
         */
        private String window;

        /**
         * Optional. Rules naming the same pool share one counter, so "any of these
         * tools" can be capped together. Every rule of a pool must state the same
         * {@code limit} and {@code window}.
         */
        private String pool;

        public Integer getLimit() {
            return limit;
        }

        public void setLimit(Integer limit) {
            this.limit = limit;
        }

        public String getWindow() {
            return window;
        }

        public void setWindow(String window) {
            this.window = window;
        }

        public String getPool() {
            return pool;
        }

        public void setPool(String pool) {
            this.pool = pool;
        }
    }
}
