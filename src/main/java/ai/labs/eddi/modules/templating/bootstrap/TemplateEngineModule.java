package ai.labs.eddi.modules.templating.bootstrap;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.modules.templating.OutputTemplateTask;
import ai.labs.eddi.modules.templating.impl.HtmlTemplateEngine;
import ai.labs.eddi.modules.templating.impl.JavaScriptTemplateEngine;
import ai.labs.eddi.modules.templating.impl.JsonSerializationThymeleafDialect;
import ai.labs.eddi.modules.templating.impl.TextTemplateEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Startup;
import org.jboss.logging.Logger;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import java.util.Map;

/**
 * @author ginccc
 */
@Startup(1000)
@ApplicationScoped
public class TemplateEngineModule {

    private static final Logger LOGGER = Logger.getLogger("Startup");
    private final Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders;
    private final Instance<ILifecycleTask> instance;

    public TemplateEngineModule(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders,
                                Instance<ILifecycleTask> instance) {
        this.lifecycleTaskProviders = lifecycleTaskProviders;
        this.instance = instance;
    }

    @PostConstruct
    @Inject
    protected void configure() {
        lifecycleTaskProviders.put(OutputTemplateTask.ID, () -> instance.select(OutputTemplateTask.class).get());
        LOGGER.debug("Added TemplateEngine Module, current size of lifecycle modules " + lifecycleTaskProviders.size());
    }

    @ApplicationScoped
    @Produces
    public TextTemplateEngine provideTextTemplateEngine(ObjectMapper objectMapper) {
        return new TextTemplateEngine(createTemplateEngine(TemplateMode.TEXT, objectMapper));
    }

    @ApplicationScoped
    @Produces
    public HtmlTemplateEngine provideHtmlTemplateEngine(ObjectMapper objectMapper) {
        return new HtmlTemplateEngine(createTemplateEngine(TemplateMode.HTML, objectMapper));
    }

    @ApplicationScoped
    @Produces
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
