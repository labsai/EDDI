package ai.labs.core;

import ai.labs.core.behavior.extensions.*;
import ai.labs.core.behavior.extensions.descriptor.BehaviorRuleExtensionRegistry;
import ai.labs.core.behavior.extensions.descriptor.ExtensionDescriptorBuilder;
import ai.labs.core.behavior.extensions.descriptor.IExtensionDescriptor;
import ai.labs.permission.ssl.SelfSignedTrustProvider;
import ai.labs.runtime.SystemRuntime;
import ai.labs.staticresources.IResourceDirectory;
import ai.labs.staticresources.IResourceFilesManager;
import ai.labs.staticresources.impl.ResourceDirectory;
import ai.labs.staticresources.impl.ResourceFilesManager;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.File;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public final class CoreRuntime {
    public static final String SNIPPET_IDENTIFIER = "snippet";

    private final SystemRuntime.IRuntime runtime;
    private final String host;
    private final Integer httpsPort;
    private final Boolean mergeResourceFiles;
    private final Boolean addFingerprintToResources;
    private final Boolean alwaysReloadResourcesFile;
    private final Boolean acceptSelfSignedCertificates;
    private final String environment;

    @Inject
    public CoreRuntime(SystemRuntime.IRuntime runtime,
                       @Named("server.host") String host,
                       @Named("server.httpsPort") Integer httpsPort,
                       @Named("mergeResourceFiles") Boolean mergeResourceFiles,
                       @Named("addFingerprintToResources") Boolean addFingerprintToResources,
                       @Named("alwaysReloadResourcesFile") Boolean alwaysReloadResourcesFile,
                       @Named("security.acceptSelfSignedCertificates") Boolean acceptSelfSignedCertificates,
                       @Named("system.environment") String environment) {
        this.runtime = runtime;
        this.host = host;
        this.httpsPort = httpsPort;
        this.mergeResourceFiles = mergeResourceFiles;
        this.addFingerprintToResources = addFingerprintToResources;
        this.alwaysReloadResourcesFile = alwaysReloadResourcesFile;
        this.acceptSelfSignedCertificates = acceptSelfSignedCertificates;
        this.environment = environment;

        init();
    }

    public void init() {
        if (acceptSelfSignedCertificates) {
            SelfSignedTrustProvider.setAlwaysTrust(true);
        }

        initResourceFiles();
        initializeBehaviourRuleRegistry();
    }

    private void initResourceFiles() {
        ResourceFilesManager.Options options = new ResourceFilesManager.Options(
                "https",
                host,
                httpsPort,
                runtime.getResourceDir(), runtime.getWebDir(),
                mergeResourceFiles,
                addFingerprintToResources,
                alwaysReloadResourcesFile);

        File resourceDir = new File(runtime.getResourceDir());
        List<IResourceDirectory> resourceDirectories = new LinkedList<>();
        if (resourceDir.exists() && resourceDir.isDirectory() && resourceDir.listFiles() != null) {
            IResourceDirectory resourceDirectory;
            for (File dir : resourceDir.listFiles()) {
                for (File targetDeviceDir : dir.listFiles()) {
                    String dirName = dir.getName();
                    resourceDirectory = new ResourceDirectory(dirName, targetDeviceDir.getName(), runtime.getResourceDir(), runtime.getWebDir(), environment);
                    if (SNIPPET_IDENTIFIER.equals(dirName)) {
                    }
                    resourceDirectories.add(resourceDirectory);
                }
            }
        }

        IResourceFilesManager resourceFilesManager = new ResourceFilesManager(options, resourceDirectories);
        resourceFilesManager.reloadResourceFiles();
    }

    public static void initializeBehaviourRuleRegistry() {
        //TODO change to DI MapBinder
        BehaviorRuleExtensionRegistry behaviorRuleExtensionRegistry = BehaviorRuleExtensionRegistry.getInstance();

        IExtensionDescriptor inputMatcherDescriptor = ExtensionDescriptorBuilder.create(InputMatcher.ID, "", "ai.labs.core.behavior.extensions.InputMatcher").
                attribute("expressions", "List<Expression>", "").build();
        behaviorRuleExtensionRegistry.register(inputMatcherDescriptor.getId(), inputMatcherDescriptor);

        IExtensionDescriptor propertyMatcherDescriptor = ExtensionDescriptorBuilder.create(PropertyMatcher.ID, "", "ai.labs.core.behavior.extensions.PropertyMatcher").
                attribute("expressions", "List<Expression>", "").build();
        behaviorRuleExtensionRegistry.register(propertyMatcherDescriptor.getId(), propertyMatcherDescriptor);

        IExtensionDescriptor connectorDescriptor = ExtensionDescriptorBuilder.create(Connector.ID, "", "ai.labs.core.behavior.extensions.Connector").
                attribute("operator", "String", "").build();
        behaviorRuleExtensionRegistry.register(connectorDescriptor.getId(), connectorDescriptor);

        IExtensionDescriptor dependencyDescriptor = ExtensionDescriptorBuilder.create(Dependency.ID, "", "ai.labs.core.behavior.extensions.Dependency").
                attribute("reference", "String", "").build();
        behaviorRuleExtensionRegistry.register(dependencyDescriptor.getId(), dependencyDescriptor);

        IExtensionDescriptor negationDescriptor = ExtensionDescriptorBuilder.create(Negation.ID, "", "ai.labs.core.behavior.extensions.Negation").
                build();
        behaviorRuleExtensionRegistry.register(negationDescriptor.getId(), negationDescriptor);

        IExtensionDescriptor occurrenceDescriptor = ExtensionDescriptorBuilder.create(Occurrence.ID, "", "ai.labs.core.behavior.extensions.Occurrence").
                attribute("maxOccurrence", "String", "").
                attribute("behaviorRuleName", "String", "").build();
        behaviorRuleExtensionRegistry.register(occurrenceDescriptor.getId(), occurrenceDescriptor);

        IExtensionDescriptor outputReferenceDescriptor = ExtensionDescriptorBuilder.create(OutputReference.ID, "", "ai.labs.core.behavior.extensions.OutputReference").
                attribute("inputValue", "String", "").
                attribute("sessionValue", "String", "").
                attribute("filter", "String", "").build();
        behaviorRuleExtensionRegistry.register(outputReferenceDescriptor.getId(), outputReferenceDescriptor);

        IExtensionDescriptor resultSizeDescriptor = ExtensionDescriptorBuilder.create(ResultSize.ID, "", "ai.labs.core.behavior.extensions.ResultSize").
                attribute("max", "Integer", "").
                attribute("min", "Integer", "").
                attribute("equal", "Integer", "").build();
        behaviorRuleExtensionRegistry.register(resultSizeDescriptor.getId(), resultSizeDescriptor);
    }
}
