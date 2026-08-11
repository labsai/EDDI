/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

/**
 * Protects EDDI-<em>generated</em> text that is concatenated into a Qute
 * template from being evaluated as one.
 * <p>
 * The hazard is one-sided: an agent author's own prompt is meant to be a
 * template, but text EDDI assembles for them — an OpenAPI endpoint summary, a
 * snippet marked {@code templateEnabled=false} — is not. Such text routinely
 * contains braces that are meaningless to Qute's author but are valid syntax to
 * Qute: an OpenAPI path like {@code /administration/docs/{name}} is a
 * {@code {name}} expression, and with the property-not-found strategy in force
 * it aborts the whole render rather than degrading to empty. The agent then
 * fails on every turn with a template error naming a key nobody ever wrote.
 *
 * @author ginccc
 */
public final class TemplateEscaping {

    /**
     * Qute's unparsed-block delimiters: everything between them renders literally.
     * Not {@code {% raw %}} — that is Jinja2, which Qute emits verbatim while still
     * resolving the markers it was meant to protect.
     */
    private static final String UNPARSED_START = "{|";
    private static final String UNPARSED_END = "|}";

    private TemplateEscaping() {
        // non-instantiable utility
    }

    /**
     * Wraps {@code content} in a Qute unparsed block so its template markers are
     * output literally rather than resolved.
     * <p>
     * Content carrying the terminator itself would close the block early and hand
     * the remainder back to the parser, reintroducing exactly the evaluation this
     * is preventing — wrapping {@code "a|} {properties.name} b"} naively renders
     * {@code "a LEAKED b|}"}. So the pair is split across a block boundary: the
     * {@code "|"} ends one unparsed block and the {@code "}"} opens the next,
     * leaving neither block containing a terminator while the concatenated output
     * is byte-identical.
     *
     * @param content
     *            text to render literally; {@code null} and empty are returned
     *            unchanged, since wrapping nothing only adds markers
     * @return {@code content} enclosed in an unparsed block
     */
    public static String unparsedBlock(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String safe = content.replace(UNPARSED_END, "|" + UNPARSED_END + UNPARSED_START + "}");
        return UNPARSED_START + safe + UNPARSED_END;
    }
}
