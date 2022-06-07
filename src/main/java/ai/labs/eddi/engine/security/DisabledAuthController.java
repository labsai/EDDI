package ai.labs.eddi.engine.security;

import io.quarkus.arc.Priority;
import io.quarkus.security.spi.runtime.AuthorizationController;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Alternative;
import javax.interceptor.Interceptor;

@Alternative
@Priority(Interceptor.Priority.LIBRARY_AFTER)
@ApplicationScoped
public class DisabledAuthController extends AuthorizationController {
    @ConfigProperty(name = "authorization.enabled", defaultValue = "false")
    boolean authorizationEnabled;

    @Override
    public boolean isAuthorizationEnabled() {
        return authorizationEnabled;
    }
}
