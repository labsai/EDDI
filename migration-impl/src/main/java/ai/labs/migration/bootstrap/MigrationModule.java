package ai.labs.migration.bootstrap;

import ai.labs.migration.IMigrationManager;
import ai.labs.migration.impl.MigrationManager;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;

public class MigrationModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IMigrationManager.class).to(MigrationManager.class).in(Scopes.SINGLETON);
    }
}
