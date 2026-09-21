/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

import java.util.List;

/**
 * Agent-designer configuration for what happens to a tool result on its way
 * back to the model.
 * <p>
 * Config, not Java: whether a directive inside an API response should be
 * redacted, warned about or blocked is a policy call that differs per agent —
 * an internal agent calling a first-party API wants the noise-free path, an
 * agent wired to a third-party MCP marketplace does not. Java supplies the
 * mechanism; the JSON picks the behaviour.
 * <p>
 * Defaults are chosen so an existing config gains protection without gaining a
 * failure mode: provenance marking on, directive content redacted rather than
 * blocked. Blocking a tool result is a real behaviour change — the model loses
 * the answer — so it is opt-in.
 */
public class ToolResultGuardrailConfig {

    /** What to do with a tool result; the persisted vocabulary is EDDI-owned. */
    public static final String ACTION_WARN = "warn";
    public static final String ACTION_REDACT = "redact";
    public static final String ACTION_BLOCK = "block";

    /**
     * Master switch. When false, results reach the model exactly as they did before
     * this feature existed.
     */
    private Boolean enabled = Boolean.TRUE;

    /**
     * Whether each result is wrapped in a delimiter naming the tool and its source,
     * with an explicit "this is data, not instructions" note.
     */
    private Boolean markProvenance = Boolean.TRUE;

    /**
     * What to do when a result carries directive-shaped content: {@code warn} (log
     * and count, pass through), {@code redact} (default — replace the directive
     * spans, keep the rest) or {@code block} (replace the whole result with a
     * neutral notice).
     */
    private String directiveAction = ACTION_REDACT;

    /**
     * Tool sources {@link #directiveAction} applies to, by provenance tag
     * ({@code builtin}, {@code http}, {@code mcp}, {@code a2a}, {@code dynamic},
     * {@code memory}, {@code recall}). Empty or null means every source — the safe
     * reading, and the one that does not silently stop covering a source added
     * later.
     * <p>
     * Named for what it narrows. As {@code appliesToSources} it read as "this whole
     * config applies to these sources", and the implementation obliged by skipping
     * PROVENANCE MARKING too — so narrowing to {@code ["mcp","a2a","http"]}, the
     * example printed in the docs, left every {@code websearch} and memory result
     * arriving unmarked in the same transcript position a system instruction
     * occupies. Marking is deliberately not narrowable; use {@link #exemptTools} to
     * exclude one tool's content from directive handling.
     */
    private List<String> directiveAppliesToSources;

    /**
     * Dispatch names exempt from directive handling. Provenance marking still
     * applies: an exemption is a statement about a tool's <em>content</em>, not a
     * reason to hide where its output came from.
     */
    private List<String> exemptTools;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getMarkProvenance() {
        return markProvenance;
    }

    public void setMarkProvenance(Boolean markProvenance) {
        this.markProvenance = markProvenance;
    }

    public String getDirectiveAction() {
        return directiveAction;
    }

    public void setDirectiveAction(String directiveAction) {
        this.directiveAction = directiveAction;
    }

    public List<String> getDirectiveAppliesToSources() {
        return directiveAppliesToSources;
    }

    public void setDirectiveAppliesToSources(List<String> directiveAppliesToSources) {
        this.directiveAppliesToSources = directiveAppliesToSources;
    }

    public List<String> getExemptTools() {
        return exemptTools;
    }

    public void setExemptTools(List<String> exemptTools) {
        this.exemptTools = exemptTools;
    }
}
