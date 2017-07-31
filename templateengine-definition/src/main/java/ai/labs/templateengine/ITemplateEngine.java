package ai.labs.templateengine;

import java.util.Map;

/**
 * @author ginccc
 */
public interface ITemplateEngine {
    String processTemplate(String template, Map<String, Object> models);
}
