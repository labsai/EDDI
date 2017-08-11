package ai.labs.templateengine.impl;

import ai.labs.templateengine.ITemplatingEngine;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.exceptions.TemplateInputException;

import javax.inject.Inject;
import java.util.Locale;
import java.util.Map;

/**
 * @author ginccc
 */
public class TemplatingEngine implements ITemplatingEngine {
    private final TemplateEngine templateEngine;

    @Inject
    public TemplatingEngine(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    @Override
    public String processTemplate(String template, Map<String, Object> dynamicAttributesMap)
            throws TemplateEngineException {
        final Context ctx = new Context(Locale.ENGLISH);
        dynamicAttributesMap.forEach(ctx::setVariable);
        try {
            return templateEngine.process(template, ctx);
        } catch (TemplateInputException e) {
            String message = "Error trying to insert context information into template. " +
                    "Either context is missing or reference in template is wrong!";
            throw new TemplateEngineException(message, e);
        }
    }
}
