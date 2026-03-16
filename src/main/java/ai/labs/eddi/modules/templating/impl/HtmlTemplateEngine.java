package ai.labs.eddi.modules.templating.impl;

import org.thymeleaf.TemplateEngine;

public class HtmlTemplateEngine {
    private TemplateEngine templateEngine;

    public HtmlTemplateEngine(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    public TemplateEngine getTemplateEngine() {
        return templateEngine;
    }

    public void setTemplateEngine(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }
}
