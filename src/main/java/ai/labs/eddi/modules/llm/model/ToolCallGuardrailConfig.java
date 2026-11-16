/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import java.util.List;

/**
 * One guardrail on the arguments of a tool call
 * ({@code LlmConfiguration.Task.toolCallGuardrails}). Everything an input or
 * output guardrail has — {@code type}, {@code action}, {@code maxRetries},
 * {@code onExhausted}, {@code blockMessage}, {@code parameters} — plus which
 * calls it screens and which of their fields.
 * <ul>
 * <li>{@code tools} (required) — the calls it applies to, in the pattern
 * language of {@code toolApprovals} ({@code mcp:add_issue_comment},
 * {@code http.post:/issues/*}, {@code builtin:*});</li>
 * <li>{@code fields} (optional) — JSON pointers into the arguments
 * ({@code /body}); a segment {@code *} is every element or member
 * ({@code /items/*}/text); {@code ""} is the whole arguments document. Absent:
 * every string in the arguments.</li>
 * </ul>
 * Actions: {@code block} (default) and {@code reprompt} refuse the call (a
 * reprompt tells the model what to correct, at most {@code maxRetries} times
 * per turn, then blocks); {@code redact} rewrites the field in place;
 * {@code warn} records only. Checked before the tool-approval gate, and again,
 * block-only, on a call a human approved.
 *
 * @since 6.7.0
 */
public class ToolCallGuardrailConfig extends LlmConfiguration.GuardrailConfig {

    private List<String> tools;
    private List<String> fields;

    public List<String> getTools() {
        return tools;
    }

    public void setTools(List<String> tools) {
        this.tools = tools;
    }

    public List<String> getFields() {
        return fields;
    }

    public void setFields(List<String> fields) {
        this.fields = fields;
    }
}
