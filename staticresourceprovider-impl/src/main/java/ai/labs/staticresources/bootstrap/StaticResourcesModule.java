package ai.labs.staticresources.bootstrap;

import ai.labs.runtime.SystemRuntime;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.staticresources.IResourceDirectory;
import ai.labs.staticresources.IResourceFilesManager;
import ai.labs.staticresources.impl.ResourceDirectory;
import ai.labs.staticresources.impl.ResourceFilesManager;
import ai.labs.staticresources.rest.IRestBinaryResource;
import ai.labs.staticresources.rest.IRestTextResource;
import ai.labs.staticresources.rest.impl.RestBinaryResource;
import ai.labs.staticresources.rest.impl.RestTextResource;
import com.google.inject.Provides;

import javax.inject.Named;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * @author ginccc
 */
public class StaticResourcesModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IRestTextResource.class).to(RestTextResource.class);
        bind(IRestBinaryResource.class).to(RestBinaryResource.class);
    }

    @Provides
    @Singleton
    public IResourceFilesManager provideResourceFilesManager(SystemRuntime.IRuntime runtime,
                                                             @Named("server.host") String host,
                                                             @Named("server.httpsPort") Integer httpsPort,
                                                             @Named("mergeResourceFiles") Boolean mergeResourceFiles,
                                                             @Named("addFingerprintToResources") Boolean addFingerprintToResources,
                                                             @Named("alwaysReloadResourcesFile") Boolean alwaysReloadResourcesFile,
                                                             @Named("availableUIs") String availableUIs,
                                                             @Named("system.environment") String environment) {
        String resourceDir = runtime.getResourceDir();
        String webDir = runtime.getWebDir();
        IResourceFilesManager.Options options = new ResourceFilesManager.Options(
                "https", host, httpsPort, resourceDir, webDir,
                mergeResourceFiles, addFingerprintToResources, alwaysReloadResourcesFile);

        List<IResourceDirectory> resourceDirectories = new ArrayList<>();
        for (String keyIdentifier : availableUIs.split(",")) {
            resourceDirectories.add(createResourceDirectory(resourceDir, webDir, keyIdentifier, "desktop", environment));
            resourceDirectories.add(createResourceDirectory(resourceDir, webDir, keyIdentifier, "mobile", environment));
                    }
        IResourceFilesManager resourceFilesManager = new ResourceFilesManager(options, resourceDirectories);
        resourceFilesManager.reloadResourceFiles();

        return resourceFilesManager;
    }

    private ResourceDirectory createResourceDirectory(String resourceDir, String webDir, String keyIdentifier, String targetDevice, String environment) {
        return new ResourceDirectory(keyIdentifier, targetDevice, resourceDir, webDir, environment);

    }
}
