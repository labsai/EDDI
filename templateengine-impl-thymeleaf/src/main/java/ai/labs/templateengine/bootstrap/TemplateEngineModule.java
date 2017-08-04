package ai.labs.templateengine.bootstrap;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.templateengine.ITemplatingEngine;
import ai.labs.templateengine.OutputTemplateTask;
import ai.labs.templateengine.impl.TemplatingEngine;
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

        MapBinder<String, ILifecycleTask> lifecycleTaskPlugins
                = MapBinder.newMapBinder(binder(), String.class, ILifecycleTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.templating").to(OutputTemplateTask.class);
    }

    @Provides
    @Singleton
    public TemplateEngine provideTemplateEngine() {
        TemplateEngine templateEngine = new TemplateEngine();
        StringTemplateResolver templateResolver = new StringTemplateResolver();
        templateResolver.setTemplateMode(TemplateMode.HTML);
        templateEngine.setTemplateResolver(templateResolver);

        return templateEngine;
    }
}
