package ai.labs.templateengine.impl;

import ai.labs.templateengine.ITemplatingEngine;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.exceptions.TemplateInputException;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @author ginccc
 */
public class TemplatingEngine implements ITemplatingEngine {
    private static final List<String> templatingControlChars = Arrays.asList("${", "*{", "#{", "@{", "~{", "th:");
    private final TextTemplateEngine textTemplateEngine;
    private final HtmlTemplateEngine htmlTemplateEngine;

    @Inject
    public TemplatingEngine(TextTemplateEngine textTemplateEngine,
                            HtmlTemplateEngine htmlTemplateEngine) {

        this.textTemplateEngine = textTemplateEngine;
        this.htmlTemplateEngine = htmlTemplateEngine;
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
        if (templateMode.equals(TemplateMode.HTML)) {
            return htmlTemplateEngine.getTemplateEngine();
        } else {
            return textTemplateEngine.getTemplateEngine();
        }
    }
}
