/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

/**
 * Protects EDDI-<em>generated</em> text that is concatenated into a Qute
 * template's <b>source</b> from being evaluated as part of it.
 * <p>
 * The hazard is one-sided: an agent author's own prompt is meant to be a
 * template, but text EDDI assembles for them — the setup wizard's OpenAPI
 * endpoint summary — is not. Such text routinely contains braces that are
 * meaningless to its author but are valid syntax to Qute: an OpenAPI path like
 * {@code /administration/docs/{name}} is a {@code {name}} expression, and with
 * the property-not-found strategy in force it fails the whole render rather
 * than degrading to empty.
 * <p>
 * <b>Only for source concatenation.</b> Do NOT wrap a value that is passed in
 * through the template DATA map (the {@code snippets}, {@code properties},
 * {@code vars} namespaces and friends). Qute does not re-parse what an
 * expression resolved to, so a data value's markers are literal already — and
 * the wrapper, being part of that same resolved value, is not re-parsed either,
 * so its {@code {|…|}} delimiters would travel verbatim into the output.
 * {@code PromptSnippetService} used to do exactly that; see its class javadoc.
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
     * is preventing — naively wrapping <code>a|&#125; {properties.name} b</code>
     * renders <code>a LEAKED b|&#125;</code>. So the pair is split across a block
     * boundary: the <code>|</code> ends one unparsed block and the
     * <code>&#125;</code> opens the next, leaving neither block containing a
     * terminator while the concatenated output is byte-identical.
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
