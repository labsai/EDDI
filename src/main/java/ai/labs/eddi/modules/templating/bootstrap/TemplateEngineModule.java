package ai.labs.eddi.modules.templating.bootstrap;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.modules.templating.OutputTemplateTask;
import ai.labs.eddi.modules.templating.impl.HtmlTemplateEngine;
import ai.labs.eddi.modules.templating.impl.JavaScriptTemplateEngine;
import ai.labs.eddi.modules.templating.impl.JsonSerializationThymeleafDialect;
import ai.labs.eddi.modules.templating.impl.TextTemplateEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.inject.Provider;
import java.util.Map;

/**
 * @author ginccc
 */
@ApplicationScoped
public class TemplateEngineModule {
    @PostConstruct
    @Inject
    protected void configure(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders,
                             Instance<ILifecycleTask> instance) {

        lifecycleTaskProviders.put(OutputTemplateTask.ID, () -> instance.select(OutputTemplateTask.class).get());
    }

    @Produces
    @ApplicationScoped
    public TextTemplateEngine provideTextTemplateEngine(ObjectMapper objectMapper) {
        return new TextTemplateEngine(createTemplateEngine(TemplateMode.TEXT, objectMapper));
    }

    @Produces
    @ApplicationScoped
    public HtmlTemplateEngine provideHtmlTemplateEngine(ObjectMapper objectMapper) {
        return new HtmlTemplateEngine(createTemplateEngine(TemplateMode.HTML, objectMapper));
    }

    @Produces
    @ApplicationScoped
    public JavaScriptTemplateEngine provideJavaScriptTemplateEngine(ObjectMapper objectMapper) {
        return new JavaScriptTemplateEngine(createTemplateEngine(TemplateMode.JAVASCRIPT, objectMapper));
    }

    private TemplateEngine createTemplateEngine(TemplateMode templateMode, ObjectMapper objectMapper) {
        TemplateEngine templateEngine = new TemplateEngine();
        templateEngine.addDialect(new JsonSerializationThymeleafDialect(objectMapper));
        StringTemplateResolver templateResolver = new StringTemplateResolver();
        templateResolver.setTemplateMode(templateMode);
        templateEngine.addTemplateResolver(templateResolver);

        return templateEngine;
    }
}
