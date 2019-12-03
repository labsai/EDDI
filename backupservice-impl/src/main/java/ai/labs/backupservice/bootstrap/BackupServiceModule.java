package ai.labs.backupservice.bootstrap;

import ai.labs.backupservice.IRestGitBackupService;
import ai.labs.backupservice.IRestExportService;
import ai.labs.backupservice.IRestImportService;
import ai.labs.backupservice.IZipArchive;
import ai.labs.backupservice.impl.RestGitBackupService;
import ai.labs.backupservice.impl.RestExportService;
import ai.labs.backupservice.impl.RestImportService;
import ai.labs.backupservice.impl.ZipArchive;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;

import java.io.InputStream;

/**
 * @author ginccc
 */
public class BackupServiceModule extends AbstractBaseModule {

    public BackupServiceModule(InputStream configFile) {
        super(configFile);
    }

    @Override
    protected void configure() {
        registerConfigFiles(configFiles);
        bind(IRestExportService.class).to(RestExportService.class).in(Scopes.SINGLETON);
        bind(IRestImportService.class).to(RestImportService.class).in(Scopes.SINGLETON);
        bind(IRestGitBackupService.class).to(RestGitBackupService.class).in(Scopes.SINGLETON);
        bind(IZipArchive.class).to(ZipArchive.class).in(Scopes.SINGLETON);
    }
}
