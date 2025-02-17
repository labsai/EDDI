package ai.labs.eddi.modules.templating.bootstrap;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.modules.templating.OutputTemplateTask;
import ai.labs.eddi.modules.templating.impl.HtmlTemplateEngine;
import ai.labs.eddi.modules.templating.impl.JavaScriptTemplateEngine;
import ai.labs.eddi.modules.templating.impl.TextTemplateEngine;
import ai.labs.eddi.modules.templating.impl.dialects.encoding.EncoderDialect;
import ai.labs.eddi.modules.templating.impl.dialects.json.JsonDialect;
import ai.labs.eddi.modules.templating.impl.dialects.uuid.UUIDDialect;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

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
    public TextTemplateEngine provideTextTemplateEngine() {
        return new TextTemplateEngine(createTemplateEngine(TemplateMode.TEXT));
    }

    @ApplicationScoped
    @Produces
    public HtmlTemplateEngine provideHtmlTemplateEngine() {
        return new HtmlTemplateEngine(createTemplateEngine(TemplateMode.HTML));
    }

    @ApplicationScoped
    @Produces
    public JavaScriptTemplateEngine provideJavaScriptTemplateEngine() {
        return new JavaScriptTemplateEngine(createTemplateEngine(TemplateMode.JAVASCRIPT));
    }

    private TemplateEngine createTemplateEngine(TemplateMode templateMode) {
        var templateResolver = new StringTemplateResolver();
        templateResolver.setTemplateMode(templateMode);

        var templateEngine = new TemplateEngine();
        templateEngine.addTemplateResolver(templateResolver);
        templateEngine.addDialect(new UUIDDialect());
        templateEngine.addDialect(new JsonDialect());
        templateEngine.addDialect(new EncoderDialect());

        return templateEngine;
    }
}
