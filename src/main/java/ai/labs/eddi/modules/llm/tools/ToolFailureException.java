/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

/**
 * A tool call that failed in a way the tool itself describes for the model —
 * the typed counterpart of the {@code "Error: …"} strings built-in tools used
 * to <em>return</em>.
 *
 * <p>
 * Returning the failure as an ordinary string made it indistinguishable from a
 * result: {@link ToolExecutionService} cached it like any answer, so a web page
 * that timed out once stayed "unreachable" for the scraper's full one-hour TTL,
 * and a weather lookup that hit a provider blip kept failing for five minutes
 * after the provider recovered. Thrown instead, the failure is never cached and
 * never charged, and the model still receives exactly the text the tool wrote
 * ({@link #getMessage()}) — this is the one exception whose message is meant
 * for the model. Every other exception a tool throws is reported to the model
 * generically and logged in full, because its text is whatever a library put
 * there.
 * </p>
 *
 * <p>
 * Use it for failures that may not repeat — a network error, an upstream
 * status, a timeout. A deterministic answer to bad input ("division by zero",
 * "invalid timezone") is still a result: the same input produces it every time,
 * and caching it is correct.
 * </p>
 */
public class ToolFailureException extends RuntimeException {

    /**
     * @param modelMessage
     *            the text the model receives, conventionally starting with
     *            {@code "Error: "}; must not contain anything the model may not see
     */
    public ToolFailureException(String modelMessage) {
        super(modelMessage, null, false, false);
    }
}
