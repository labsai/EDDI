/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import java.util.List;

/**
 * Agent-designer configuration for one entry of {@code toolDisplay}: what a
 * watching client (a live chat showing "Creating an action...") may learn about
 * a call of this tool.
 * <p>
 * It is opt-in and deliberately narrow. By default a streamed tool call carries
 * only the tool name and the call id; arguments never leave the server, because
 * they may hold user data. This entry adds a static {@link #label} and the
 * values of at most {@link #MAX_FIELDS} top-level arguments the designer names.
 * Each value is shown only if it is a string, number or boolean, is run through
 * the secret redaction filter and is cut to {@link #MAX_VALUE_LENGTH}
 * characters.
 *
 * @since 6.5.0
 */
public class ToolDisplayConfig {

    /** Longest {@link #label}. */
    public static final int MAX_LABEL_LENGTH = 120;

    /** Most argument names a {@link #fields} allow-list may name. */
    public static final int MAX_FIELDS = 3;

    /** Longest displayed argument value, including the ellipsis of a cut one. */
    public static final int MAX_VALUE_LENGTH = 80;

    /** Longest tool name or argument name. */
    public static final int MAX_NAME_LENGTH = 128;

    /** Static text shown for the call, e.g. {@code Creating an action}. */
    private String label;

    /**
     * Allow-list of TOP-LEVEL argument names whose values may be shown. Absent or
     * empty shows none.
     */
    private List<String> fields;

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public List<String> getFields() {
        return fields;
    }

    public void setFields(List<String> fields) {
        this.fields = fields;
    }
}
