package ai.labs.templateengine;

import java.util.Map;

/**
 * @author ginccc
 */
public interface ITemplatingEngine {
    String processTemplate(String template, Map<String, Object> dynamicAttributesMap) throws TemplateEngineException;

    String processTemplate(String template, Map<String, Object> dynamicAttributesMap, TemplateMode templateMode) throws TemplateEngineException;

    enum TemplateMode {
        TEXT,
        HTML
    }

    class TemplateEngineException extends Exception {
        public TemplateEngineException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
