package ai.labs.eddi.modules.templating.impl;

import ai.labs.eddi.modules.templating.ITemplatingEngine;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.exceptions.TemplateInputException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @author ginccc
 */
@ApplicationScoped
public class TemplatingEngine implements ITemplatingEngine {
    private static final List<String> templatingControlChars = Arrays.asList("${", "*{", "#{", "@{", "~{", "th:");
    private final TextTemplateEngine textTemplateEngine;
    private final HtmlTemplateEngine htmlTemplateEngine;
    private final JavaScriptTemplateEngine javaScriptTemplateEngine;

    @Inject
    public TemplatingEngine(TextTemplateEngine textTemplateEngine,
                            HtmlTemplateEngine htmlTemplateEngine,
                            JavaScriptTemplateEngine javaScriptTemplateEngine) {

        this.textTemplateEngine = textTemplateEngine;
        this.htmlTemplateEngine = htmlTemplateEngine;
        this.javaScriptTemplateEngine = javaScriptTemplateEngine;
    }

    @Override
    public String processTemplate(String template,
                                  Map<String, Object> dynamicAttributesMap) throws TemplateEngineException {
        return processTemplate(template, dynamicAttributesMap, TemplateMode.TEXT);
    }

    @Override
    public String processTemplate(String template,
                                  Map<String, Object> dynamicAttributesMap,
                                  TemplateMode templateMode) throws TemplateEngineException {
        final Context ctx = new Context(Locale.ENGLISH);
        dynamicAttributesMap.forEach(ctx::setVariable);
        try {
            if (containsTemplatingControlCharacters(template)) {
                return getTemplateEngine(templateMode).process(template, ctx);
            } else {
                return template;
            }
        } catch (TemplateInputException e) {
            String message = "Error trying to insert context information into template. " +
                    "Either context is missing or reference in template is wrong!";
            throw new TemplateEngineException(message, e);
        }
    }

    private boolean containsTemplatingControlCharacters(String template) {
        return templatingControlChars.stream().anyMatch(template::contains);
    }

    private TemplateEngine getTemplateEngine(TemplateMode templateMode) {
        switch (templateMode) {
            case HTML:
                return htmlTemplateEngine.getTemplateEngine();
            case JAVASCRIPT:
                return javaScriptTemplateEngine.getTemplateEngine();
            case TEXT:
            default:
                return textTemplateEngine.getTemplateEngine();
        }
    }
}
