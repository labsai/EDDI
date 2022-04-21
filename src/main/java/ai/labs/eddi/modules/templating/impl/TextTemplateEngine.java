package ai.labs.eddi.modules.templating.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.thymeleaf.TemplateEngine;

@Getter
@Setter
@AllArgsConstructor
public class TextTemplateEngine {
    private TemplateEngine templateEngine;
}
