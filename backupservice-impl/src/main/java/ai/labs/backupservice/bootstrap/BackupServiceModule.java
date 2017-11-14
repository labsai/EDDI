package ai.labs.backupservice.bootstrap;

import ai.labs.backupservice.IRestExportService;
import ai.labs.backupservice.impl.RestExportService;
import ai.labs.runtime.bootstrap.AbstractBaseModule;

/**
 * @author ginccc
 */
public class BackupServiceModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IRestExportService.class).to(RestExportService.class);
    }
}
