package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.inject.Provider;
import java.util.Map;

@ApplicationScoped
public class Startup {
    private static final Logger LOGGER = Logger.getLogger(Startup.class);
    private final IAutoBotDeployment autoBotDeployment;
    private final Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders;

    @Inject
    public Startup(IAutoBotDeployment autoBotDeployment,
                   @LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders) {
        this.autoBotDeployment = autoBotDeployment;
        this.lifecycleTaskProviders = lifecycleTaskProviders;
    }

    void onStart(@Observes StartupEvent ev) {
        LOGGER.info("The application is starting...");

        //TODO: run auto deploy after all lifecycle tasks have been initialized
        /*try {
            autoBotDeployment.autoDeployBots();
        } catch (IAutoBotDeployment.AutoDeploymentException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }*/
    }

    void onStop(@Observes ShutdownEvent ev) {
        LOGGER.info("The application is stopping...");
    }
}
