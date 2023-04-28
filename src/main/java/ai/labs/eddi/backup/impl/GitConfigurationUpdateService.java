/*
package ai.labs.eddi.backup.impl;


import ai.labs.eddi.backup.IRestGitBackupService;
import ai.labs.eddi.configs.bots.IBotStore;
import ai.labs.eddi.configs.bots.model.BotConfiguration;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.packages.IPackageStore;
import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.utils.RestUtilities;
import io.quarkus.arc.Priority;
import io.quarkus.runtime.Startup;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Startup
@Priority(2000)
@IResourceStore.ConfigurationUpdate
@Interceptor
public class GitConfigurationUpdateService  {

    final private Provider<IRestGitBackupService> backupService;
    final private Provider<IBotStore> botStore;
    final private Provider<IDeploymentStore> deploymentStore;
    final private Provider<IPackageStore> packageStore;
    final private Provider<IJsonSerialization> jsonSerialization;


    @Inject
    ManagedExecutor managedExecutor;

    private static final Logger log = Logger.getLogger(RestExportService.class);


    @Inject
    public GitConfigurationUpdateService(Provider<IRestGitBackupService> backupService,
                                         Provider<IBotStore> botStore,
                                         Provider<IDeploymentStore> deploymentStore,
                                         Provider<IPackageStore> packageStore,
                                         Provider<IJsonSerialization> jsonSerialization) {

        this.backupService = backupService;
        this.botStore = botStore;
        this.deploymentStore = deploymentStore;
        this.packageStore = packageStore;
        this.jsonSerialization = jsonSerialization;
    }


    @AroundInvoke
    public Object invoke(InvocationContext ctx) throws Throwable {
        managedExecutor.execute(() -> {
            try {
                if (backupService.get().isGitAutomatic() && (ctx.getMethod().getName().startsWith("update") || ctx.getMethod().getName().startsWith("delete"))) {
                    performAutomaticUpdate((String) ctx.getParameters()[0]);
                }
            } catch (Throwable th) {
                log.error("there was an error on automatic git -> everything is still stored, but check git settings");
            }
        });

        return ctx.proceed();
    }

    private void performAutomaticUpdate(String documentId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException, IOException {

        String botId = findBotId(documentId);
        if (botId != null) {
            if (!backupService.get().isGitInitialised(botId)) {
                backupService.get().gitInit(botId);
            }
            backupService.get().gitCommit(botId, "automatic commit on change, timestamp of commit " + System.currentTimeMillis() /1000);
            backupService.get().gitPush(botId);
        }

    }

    private String findBotId(String documentId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException, IOException {
        for (DeploymentInfo di : deploymentStore.get().readDeploymentInfos()) {
            BotConfiguration botConfiguration = botStore.get().read(di.getBotId(), di.getBotVersion());
            Map<IResourceStore.IResourceId, PackageConfiguration> packageConfigurations =
                    readConfigs(packageStore.get(), botConfiguration.getPackages());

            if (di.getBotId().equals(documentId)) return di.getBotId();

            for (IResourceStore.IResourceId resourceId : packageConfigurations.keySet()) {
                PackageConfiguration packageConfiguration = packageConfigurations.get(resourceId);
                String packageConfigurationString = jsonSerialization.get().serialize(packageConfiguration);
                if (packageConfigurationString.contains(documentId)) return di.getBotId();
            }
        }
        return null;
    }

    private static <T> Map<IResourceStore.IResourceId, T> readConfigs(IResourceStore<T> store, List<URI> configUris)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        Map<IResourceStore.IResourceId, T> ret = new LinkedHashMap<>();
        for (URI uri : configUris) {
            IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(uri);
            ret.put(resourceId, store.read(resourceId.getId(), resourceId.getVersion()));
        }

        return ret;
    }

}*/
