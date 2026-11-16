/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle;

import java.util.Map;

/**
 * What an agent designer chose to show of one tool call to a watching client
 * (the {@code toolDisplay} setting of an LLM task): a static label, and the
 * values of a few allow-listed top-level arguments, already redacted and cut.
 * <p>
 * Nothing but this travels from a call's arguments onto the live event stream.
 *
 * @param displayName
 *            the designer's label, e.g. "Creating an action"; may be null
 * @param fields
 *            argument name to display value; never null, may be empty
 */
public record ToolDisplay(String displayName, Map<String, String> fields) {
    public ToolDisplay {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }
}
