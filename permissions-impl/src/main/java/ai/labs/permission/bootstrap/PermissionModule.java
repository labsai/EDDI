package ai.labs.permission.bootstrap;

import ai.labs.permission.IAuthorizationManager;
import ai.labs.permission.IPermissionStore;
import ai.labs.permission.impl.AuthorizationManager;
import ai.labs.permission.impl.PermissionStore;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;

/**
 * @author ginccc
 */
public class PermissionModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IPermissionStore.class).to(PermissionStore.class).in(Scopes.SINGLETON);
        bind(IAuthorizationManager.class).to(AuthorizationManager.class).in(Scopes.SINGLETON);
    }
}
