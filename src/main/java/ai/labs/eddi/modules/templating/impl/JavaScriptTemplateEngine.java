package ai.labs.eddi.modules.templating.impl;

import org.thymeleaf.TemplateEngine;

public class JavaScriptTemplateEngine {
    private TemplateEngine templateEngine;

    public JavaScriptTemplateEngine(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    public TemplateEngine getTemplateEngine() {
        return templateEngine;
    }

    public void setTemplateEngine(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }
}
