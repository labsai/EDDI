/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

import java.util.Map;

/**
 * @author ginccc
 */
public interface ITemplatingEngine {
    String processTemplate(String template, Map<String, Object> dynamicAttributesMap) throws TemplateEngineException;

    /**
     * Parses a template without rendering it.
     *
     * @return {@code null} when the template parses, or when it holds nothing to
     *         parse; otherwise the parser's message
     */
    default String validateTemplate(String template) {
        return null;
    }

    String processTemplate(String template, Map<String, Object> dynamicAttributesMap, TemplateMode templateMode) throws TemplateEngineException;

    /**
     * Renders the {@code expressions} field of a quick reply. Conditions, loops and
     * filters see the original data and the literal text of the template is kept;
     * only what a data expression substitutes loses the characters of the
     * expression notation ({@code (}, {@code )} and {@code ,}), and a substituted
     * value that is only {@code *} becomes empty. The field becomes a trusted
     * dictionary phrase on the next turn, so data must not be able to close the
     * author's expression and add one of its own.
     */
    String processExpressionsTemplate(String template, Map<String, Object> dynamicAttributesMap) throws TemplateEngineException;

    /**
     * Output context the rendered result is embedded into. It decides how values
     * substituted into the template are escaped — the static template text is never
     * touched.
     */
    enum TemplateMode {
        /** No escaping — the result is plain text. */
        TEXT,
        /** Substituted values are HTML-escaped ({@code & < > " '}). */
        HTML,
        /** Substituted values are escaped for a JavaScript string literal. */
        JAVASCRIPT
    }

    class TemplateEngineException extends Exception {
        public TemplateEngineException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
