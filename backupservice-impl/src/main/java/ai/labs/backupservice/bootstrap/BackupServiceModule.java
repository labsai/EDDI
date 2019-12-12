package ai.labs.backupservice.bootstrap;

import ai.labs.backupservice.IRestExportService;
import ai.labs.backupservice.IRestGitBackupService;
import ai.labs.backupservice.IRestImportService;
import ai.labs.backupservice.IZipArchive;
import ai.labs.backupservice.impl.*;
import ai.labs.persistence.IResourceStore;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;
import com.google.inject.matcher.AbstractMatcher;
import com.google.inject.matcher.Matchers;

import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * @author ginccc
 */
public class BackupServiceModule extends AbstractBaseModule {
    private final InputStream[] configFiles;


    public BackupServiceModule(FileInputStream... configFiles) {
        this.configFiles = configFiles;
    }

    @Override
    protected void configure() {
        registerConfigFiles(this.configFiles);
        bind(IRestExportService.class).to(RestExportService.class).in(Scopes.SINGLETON);
        bind(IRestImportService.class).to(RestImportService.class).in(Scopes.SINGLETON);
        bind(IRestGitBackupService.class).to(RestGitBackupService.class).in(Scopes.SINGLETON);

        // AutoGit Injection - all methods that are annotated with @ConfigurationUpdate and start with
        // update or delete trigger a new commit/push
        GitConfigurationUpdateService updateService = new GitConfigurationUpdateService();
        requestInjection(updateService);
        bindInterceptor(Matchers.any(), new AbstractMatcher<>() {
            @Override
            public boolean matches(Method method) {
                return method.isAnnotationPresent(IResourceStore.ConfigurationUpdate.class) && !method.isSynthetic();
            }

        }, updateService);
        bind(IZipArchive.class).to(ZipArchive.class).in(Scopes.SINGLETON);
    }
}
