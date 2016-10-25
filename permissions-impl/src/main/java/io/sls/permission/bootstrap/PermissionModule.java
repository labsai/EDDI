package io.sls.permission.bootstrap;

import com.google.inject.Scopes;
import io.sls.permission.IAuthorizationManager;
import io.sls.permission.IPermissionStore;
import io.sls.permission.impl.AuthorizationManager;
import io.sls.permission.impl.PermissionStore;
import io.sls.runtime.bootstrap.AbstractBaseModule;

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
