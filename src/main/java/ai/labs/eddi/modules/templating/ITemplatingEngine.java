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

    String processTemplate(String template, Map<String, Object> dynamicAttributesMap, TemplateMode templateMode) throws TemplateEngineException;

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
