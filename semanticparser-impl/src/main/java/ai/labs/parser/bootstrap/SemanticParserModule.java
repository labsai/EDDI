package ai.labs.parser.bootstrap;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.parser.InputParserTask;
import ai.labs.parser.rest.IRestSemanticParser;
import ai.labs.parser.rest.impl.RestSemanticParser;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.multibindings.MapBinder;

/**
 * @author ginccc
 */
public class SemanticParserModule extends AbstractBaseModule {

    @Override
    protected void configure() {
        bind(IRestSemanticParser.class).to(RestSemanticParser.class);
        MapBinder<String, ILifecycleTask> lifecycleTaskPlugins
                = MapBinder.newMapBinder(binder(), String.class, ILifecycleTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.parser").to(InputParserTask.class);
    }
}
