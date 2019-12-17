package ai.labs.backupservice.bootstrap;

import ai.labs.backupservice.IRestExportService;
import ai.labs.backupservice.IRestGitBackupService;
import ai.labs.backupservice.IRestImportService;
import ai.labs.backupservice.IZipArchive;
import ai.labs.backupservice.impl.GitConfigurationUpdateService;
import ai.labs.backupservice.impl.RestExportService;
import ai.labs.backupservice.impl.RestGitBackupService;
import ai.labs.backupservice.impl.RestImportService;
import ai.labs.backupservice.impl.ZipArchive;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.config.bots.IBotStore;
import ai.labs.resources.rest.config.packages.IPackageStore;
import ai.labs.resources.rest.deployment.IDeploymentStore;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.serialization.IJsonSerialization;
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
        bindInterceptor(Matchers.any(), new AbstractMatcher<>() {
            @Override
            public boolean matches(Method method) {
                return method.isAnnotationPresent(IResourceStore.ConfigurationUpdate.class) && !method.isSynthetic();
            }

        }, new GitConfigurationUpdateService(getProvider(IRestGitBackupService.class),
                                             getProvider(IBotStore.class),
                                             getProvider(IDeploymentStore.class),
                                             getProvider(IPackageStore.class),
                                             getProvider(IJsonSerialization.class)));
        bind(IZipArchive.class).to(ZipArchive.class).in(Scopes.SINGLETON);
    }
}
