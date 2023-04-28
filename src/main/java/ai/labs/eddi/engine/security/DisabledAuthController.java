package ai.labs.eddi.engine.security;

import io.quarkus.arc.Priority;
import io.quarkus.security.spi.runtime.AuthorizationController;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.interceptor.Interceptor;

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
