package ai.labs.api;

import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;

public class CSVTestModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(ICSVExport.class).to(CSVExport.class).in(Scopes.SINGLETON);
    }
}
