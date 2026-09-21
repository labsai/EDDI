/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.snippets.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Reusable prompt building block for system prompts.
 * <p>
 * Snippets are named prompt text fragments stored in MongoDB as versioned
 * config documents. Agent designers compose them into system prompts via
 * template variables: {@code {{snippets.cautious_mode}}}.
 * <p>
 * All snippets are auto-loaded into the template data map at LLM task execution
 * time. Designers have full control over ordering and placement within their
 * system prompt templates.
 *
 * @author ginccc
 * @since 6.0.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PromptSnippet {

    /**
     * Unique identifier, underscore-delimited. Must match {@code [a-z0-9_]+}. Used
     * as the template variable name: {@code {{snippets.<name>}}}.
     */
    private String name;

    /**
     * UI grouping category: "governance", "persona", "compliance", "custom".
     */
    private String category;

    /**
     * Human-readable explanation for the Manager gallery.
     */
    private String description;

    /**
     * The prompt text (multiline). Injected verbatim into the template data map and
     * pulled into a prompt with {@code {snippets.<name>}}. Qute does not re-parse
     * what an expression resolved to, so any {@code {...}} in here reaches the
     * model as literal text.
     */
    private String content;

    /**
     * Search/filter tags: ["safety", "production"].
     */
    private List<String> tags;

    /**
     * Intended to control whether the snippet content undergoes template
     * resolution. Default: true.
     * <p>
     * <b>Currently inert.</b> Snippet content is delivered as a template data value
     * and Qute does not re-parse a resolved value, so markers are literal either
     * way — {@code false} already describes the actual behaviour, and {@code true}
     * does not obtain it. The field is kept because stored configs carry it and
     * because honouring it is a live option; see {@code PromptSnippetService} for
     * what enabling it would cost.
     */
    private boolean templateEnabled = true;

    public PromptSnippet() {
    }

    public PromptSnippet(String name, String category, String description, String content, List<String> tags, boolean templateEnabled) {
        this.name = name;
        this.category = category;
        this.description = description;
        this.content = content;
        this.tags = tags;
        this.templateEnabled = templateEnabled;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public boolean isTemplateEnabled() {
        return templateEnabled;
    }

    public void setTemplateEnabled(boolean templateEnabled) {
        this.templateEnabled = templateEnabled;
    }
}
