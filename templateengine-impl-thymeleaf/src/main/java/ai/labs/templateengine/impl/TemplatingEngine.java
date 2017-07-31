package ai.labs.templateengine.impl;

import ai.labs.templateengine.ITemplatingEngine;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

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
    public String processTemplate(String template, Map<String, Object> dynamicAttributesMap) {
        final Context ctx = new Context(Locale.ENGLISH);
        dynamicAttributesMap.forEach(ctx::setVariable);
        return templateEngine.process(template, ctx);
    }
}
