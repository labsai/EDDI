package ai.labs.templateengine.bootstrap;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.templateengine.IMemoryTemplateConverter;
import ai.labs.templateengine.ITemplatingEngine;
import ai.labs.templateengine.OutputTemplateTask;
import ai.labs.templateengine.impl.HtmlTemplateEngine;
import ai.labs.templateengine.impl.MemoryTemplateConverter;
import ai.labs.templateengine.impl.TemplatingEngine;
import ai.labs.templateengine.impl.TextTemplateEngine;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.multibindings.MapBinder;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import javax.inject.Singleton;

/**
 * @author ginccc
 */
public class TemplateEngineModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(ITemplatingEngine.class).to(TemplatingEngine.class).in(Scopes.SINGLETON);
        bind(IMemoryTemplateConverter.class).to(MemoryTemplateConverter.class).in(Scopes.SINGLETON);

        MapBinder<String, ILifecycleTask> lifecycleTaskPlugins
                = MapBinder.newMapBinder(binder(), String.class, ILifecycleTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.templating").to(OutputTemplateTask.class);
    }

    @Provides
    @Singleton
    public TextTemplateEngine provideTextTemplateEngine() {
        return new TextTemplateEngine(createTemplateEngine(TemplateMode.TEXT));
    }

    @Provides
    @Singleton
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
