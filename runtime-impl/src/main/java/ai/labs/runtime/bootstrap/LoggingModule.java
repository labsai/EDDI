package ai.labs.runtime.bootstrap;

import ai.labs.runtime.DatabaseLogs;
import ai.labs.runtime.IDatabaseLogs;
import ai.labs.utilities.RuntimeUtilities;
import com.bugsnag.Bugsnag;
import com.google.inject.Provides;
import com.google.inject.Scopes;

import javax.inject.Named;
import javax.inject.Singleton;

public class LoggingModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IDatabaseLogs.class).to(DatabaseLogs.class).in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    private Bugsnag provideBugsnag(@Named("bugsnagApiKey") String bugsnagApiKey) {
        if (!RuntimeUtilities.isNullOrEmpty(bugsnagApiKey)) {
            return new Bugsnag(bugsnagApiKey);
        }

        return null;
    }
}
