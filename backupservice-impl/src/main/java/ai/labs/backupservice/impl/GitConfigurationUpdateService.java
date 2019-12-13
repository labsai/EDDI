package ai.labs.backupservice.impl;

import ai.labs.backupservice.IRestExportService;
import ai.labs.backupservice.IRestGitBackupService;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.config.bots.IBotStore;
import ai.labs.resources.rest.config.bots.model.BotConfiguration;
import ai.labs.resources.rest.config.packages.IPackageStore;
import ai.labs.resources.rest.config.packages.model.PackageConfiguration;
import ai.labs.resources.rest.deployment.IDeploymentStore;
import ai.labs.resources.rest.deployment.model.DeploymentInfo;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;


@Slf4j
public class GitConfigurationUpdateService implements MethodInterceptor {

    private IRestGitBackupService backupService;
    private IBotStore botStore;
    private IDeploymentStore deploymentStore;
    private IPackageStore packageStore;
    private IJsonSerialization jsonSerialization;

    private ExecutorService gitSingleThreadedExecutor = Executors.newFixedThreadPool(1);

    @Inject
    public GitConfigurationUpdateService(IRestGitBackupService backupService,
                                         IBotStore botStore,
                                         IDeploymentStore deploymentStore,
                                         IPackageStore packageStore,
                                         IJsonSerialization jsonSerialization) {

        this.backupService = backupService;
        this.botStore = botStore;
        this.deploymentStore = deploymentStore;
        this.packageStore = packageStore;
        this.jsonSerialization = jsonSerialization;
    }


    @Override
    public Object invoke(MethodInvocation methodInvocation) throws Throwable {
        Object result = methodInvocation.proceed();
        gitSingleThreadedExecutor.execute(() -> {
            try {
                if (backupService.isGitAutomatic() && (methodInvocation.getMethod().getName().startsWith("update") || methodInvocation.getMethod().getName().startsWith("delete"))) {
                    performAutomaticUpdate((String) methodInvocation.getArguments()[0]);
                }
            } catch (Throwable th) {
                log.error("there was an error on automatic git -> everything is still stored, but check git settings");
            }
        });

        return result;
    }

    private void performAutomaticUpdate(String documentId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException, IOException {

        String botId = findBotId(documentId);
        if (botId != null) {
            if (!backupService.isGitInitialised(botId)) {
                backupService.gitInit(botId);
            }
            backupService.gitCommit(botId, "automatic commit on change, timestamp of commit " + System.currentTimeMillis() /1000);
            backupService.gitPush(botId);
        }

    }

    private String findBotId(String documentId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException, IOException {
        for (DeploymentInfo di : deploymentStore.readDeploymentInfos()) {
            BotConfiguration botConfiguration = botStore.read(di.getBotId(), di.getBotVersion());
            Map<IResourceStore.IResourceId, PackageConfiguration> packageConfigurations =
                    readConfigs(packageStore, botConfiguration.getPackages());

            if (di.getBotId().equals(documentId)) return di.getBotId();

            for (IResourceStore.IResourceId resourceId : packageConfigurations.keySet()) {
                PackageConfiguration packageConfiguration = packageConfigurations.get(resourceId);
                String packageConfigurationString = jsonSerialization.serialize(packageConfiguration);
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

}
