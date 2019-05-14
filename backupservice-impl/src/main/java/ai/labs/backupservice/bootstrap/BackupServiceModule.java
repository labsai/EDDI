package ai.labs.backupservice.bootstrap;

import ai.labs.backupservice.IGitBackupService;
import ai.labs.backupservice.IRestExportService;
import ai.labs.backupservice.IRestImportService;
import ai.labs.backupservice.IZipArchive;
import ai.labs.backupservice.impl.GitBackupService;
import ai.labs.backupservice.impl.RestExportService;
import ai.labs.backupservice.impl.RestImportService;
import ai.labs.backupservice.impl.ZipArchive;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;

/**
 * @author ginccc
 */
public class BackupServiceModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IRestExportService.class).to(RestExportService.class);
        bind(IRestImportService.class).to(RestImportService.class);
        bind(IGitBackupService.class).to(GitBackupService.class);
        bind(IZipArchive.class).to(ZipArchive.class).in(Scopes.SINGLETON);
    }
}
