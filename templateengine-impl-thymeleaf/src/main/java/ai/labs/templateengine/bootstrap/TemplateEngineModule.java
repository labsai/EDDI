package ai.labs.templateengine.bootstrap;

import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.templateengine.ITemplateEngine;
import ai.labs.templateengine.impl.TemplateEngine;
import com.google.inject.Scopes;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * @author ginccc
 */
public class TemplateEngineModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(ITemplateEngine.class).to(TemplateEngine.class).in(Scopes.SINGLETON);
    }

    public org.thymeleaf.TemplateEngine provideTemplateResolver() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setTemplateMode("XHTML");
        resolver.setSuffix(".html");
        org.thymeleaf.TemplateEngine engine = new org.thymeleaf.TemplateEngine();
        engine.setTemplateResolver(resolver);

        return engine;
    }
}
