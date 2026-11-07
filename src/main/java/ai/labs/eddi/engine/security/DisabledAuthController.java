/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.security.spi.runtime.AuthorizationController;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Replaces Quarkus' own {@link AuthorizationController} so that
 * {@code @RolesAllowed} and the other security annotations are enforced only
 * when {@code authorization.enabled=true} (default {@code false}). With it off,
 * every role check is a no-op.
 */
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
