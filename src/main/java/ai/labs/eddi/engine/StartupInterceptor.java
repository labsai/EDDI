package ai.labs.eddi.engine;

import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleModule;
import ai.labs.eddi.modules.behavior.bootstrap.BehaviorModule;
import ai.labs.eddi.modules.gitcalls.bootstrap.GitCallsModule;
import ai.labs.eddi.modules.httpcalls.bootstrap.HttpCallsModule;
import ai.labs.eddi.modules.nlp.bootstrap.SemanticParserModule;
import ai.labs.eddi.modules.output.bootstrap.OutputGenerationModule;
import ai.labs.eddi.modules.properties.bootstrap.PropertySetterModule;
import ai.labs.eddi.modules.templating.bootstrap.TemplateEngineModule;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

@ApplicationScoped
public class StartupInterceptor {

    private static final Logger LOGGER = Logger.getLogger("Startup");

    @Inject
    LifecycleModule lifecycleModule;

    @Inject
    BehaviorModule behaviorModule;

    @Inject
    GitCallsModule gitCallsModule;

    @Inject
    HttpCallsModule httpCallsModule;

    @Inject
    SemanticParserModule semanticParserModule;

    @Inject
    OutputGenerationModule outputGenerationModule;

    @Inject
    PropertySetterModule propertySetterModule;

    @Inject
    TemplateEngineModule templateEngineModule;

    void onStart(@Observes StartupEvent ev) {
        LOGGER.info("EDDI starting up");
        lifecycleModule.start();
        LOGGER.info("Lifecycle Module started");
        behaviorModule.start();
        LOGGER.info("Behavior Module started");
        gitCallsModule.start();
        LOGGER.info("GitCalls Module started");
        httpCallsModule.start();
        LOGGER.info("HttpCalls Module started");
        semanticParserModule.start();
        LOGGER.info("SemanticParser Module started");
        outputGenerationModule.start();
        LOGGER.info("OutputGeneration Module started");
        propertySetterModule.start();
        LOGGER.info("PropertySetter Module started");
        templateEngineModule.start();
        LOGGER.info("TemplateEngine Module started");
        LOGGER.info("lifecycleModule providers size: " + lifecycleModule.getLifecycleTaskProviders().size());
    }
}
