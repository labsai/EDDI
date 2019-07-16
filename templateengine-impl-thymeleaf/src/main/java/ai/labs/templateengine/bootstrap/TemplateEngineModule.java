package ai.labs.templateengine.bootstrap;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.templateengine.OutputTemplateTask;
import ai.labs.templateengine.impl.HtmlTemplateEngine;
import ai.labs.templateengine.impl.TextTemplateEngine;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.inject.Provider;
import java.util.Map;

/**
 * @author ginccc
 */
public class TemplateEngineModule {
    @PostConstruct
    @Inject
    protected void configure(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders,
                             Instance<ILifecycleTask> instance) {

        lifecycleTaskProviders.put("ai.labs.templating", () -> instance.select(OutputTemplateTask.class).get());
    }

    @Produces
    @ApplicationScoped
    public TextTemplateEngine provideTextTemplateEngine() {
        return new TextTemplateEngine(createTemplateEngine(TemplateMode.TEXT));
    }

    @Produces
    @ApplicationScoped
    public HtmlTemplateEngine provideHtmlTemplateEngine() {
        return new HtmlTemplateEngine(createTemplateEngine(TemplateMode.HTML));
    }

    private TemplateEngine createTemplateEngine(TemplateMode templateMode) {
        TemplateEngine templateEngine = new TemplateEngine();
        StringTemplateResolver templateResolver = new StringTemplateResolver();
        templateResolver.setTemplateMode(templateMode);
        templateEngine.addTemplateResolver(templateResolver);

        return templateEngine;
    }
}
