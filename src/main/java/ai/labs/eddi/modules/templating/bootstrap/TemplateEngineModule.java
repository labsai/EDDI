package ai.labs.eddi.modules.templating.bootstrap;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.modules.templating.OutputTemplateTask;
import ai.labs.eddi.modules.templating.impl.HtmlTemplateEngine;
import ai.labs.eddi.modules.templating.impl.JavaScriptTemplateEngine;
import ai.labs.eddi.modules.templating.impl.JsonSerializationThymeleafDialect;
import ai.labs.eddi.modules.templating.impl.TextTemplateEngine;
import com.fasterxml.jackson.core.JsonFactory;
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
    public TextTemplateEngine provideTextTemplateEngine(JsonFactory jsonFactory) {
        return new TextTemplateEngine(createTemplateEngine(TemplateMode.TEXT, jsonFactory));
    }

    @Produces
    @ApplicationScoped
    public HtmlTemplateEngine provideHtmlTemplateEngine(JsonFactory jsonFactory) {
        return new HtmlTemplateEngine(createTemplateEngine(TemplateMode.HTML, jsonFactory));
    }

    @Produces
    @ApplicationScoped
    public JavaScriptTemplateEngine provideJavaScriptTemplateEngine(JsonFactory jsonFactory) {
        return new JavaScriptTemplateEngine(createTemplateEngine(TemplateMode.JAVASCRIPT, jsonFactory));
    }

    private TemplateEngine createTemplateEngine(TemplateMode templateMode, JsonFactory jsonFactory) {
        TemplateEngine templateEngine = new TemplateEngine();
        templateEngine.addDialect(new JsonSerializationThymeleafDialect(jsonFactory));
        StringTemplateResolver templateResolver = new StringTemplateResolver();
        templateResolver.setTemplateMode(templateMode);
        templateEngine.addTemplateResolver(templateResolver);

        return templateEngine;
    }
}
