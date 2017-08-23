package ai.labs.templateengine;

import java.util.Map;

/**
 * @author ginccc
 */
public interface ITemplatingEngine {
    String processTemplate(String template, Map<String, Object> dynamicAttributesMap) throws TemplateEngineException;

    class TemplateEngineException extends Exception {
        public TemplateEngineException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
