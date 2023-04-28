package ai.labs.eddi.modules.gitcalls.impl;

import ai.labs.eddi.configs.git.model.GitCall;
import ai.labs.eddi.configs.git.model.GitCallsConfiguration;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.exceptions.PackageConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_BRANCH_SECTION;

@ApplicationScoped
public class GitCallsTask implements ILifecycleTask {
    public static final String ID = "ai.labs.gitcalls";
    private static final String ACTION_KEY = "actions";
    private static final String KEY_GIT_CALLS = "gitCalls";
    private final IResourceClientLibrary resourceClientLibrary;
    private final IDataFactory dataFactory;
    private final ITemplatingEngine templatingEngine;
    private final IMemoryItemConverter memoryItemConverter;
    private final String tmpPath = System.getProperty("user.dir") + "/gitcalls/";

    private static final Logger log = Logger.getLogger(GitCallsTask.class);

    @Inject
    public GitCallsTask(IResourceClientLibrary resourceClientLibrary,
                        IDataFactory dataFactory,
                        ITemplatingEngine templatingEngine,
                        IMemoryItemConverter memoryItemConverter) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.dataFactory = dataFactory;
        this.templatingEngine = templatingEngine;
        this.memoryItemConverter = memoryItemConverter;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getType() {
        return KEY_GIT_CALLS;
    }

    @Override
    public void execute(IConversationMemory memory, Object component) {
        final var gitCallsConfig = (GitCallsConfiguration) component;

        IConversationMemory.IWritableConversationStep currentStep = memory.getCurrentStep();
        IData<List<String>> latestData = currentStep.getLatestData(ACTION_KEY);
        if (latestData == null) {
            return;
        }

        Map<String, Object> templateDataObjects = memoryItemConverter.convert(memory);
        List<String> actions = latestData.getResult();

        for (String action : actions) {
            var filteredGitCalls = gitCallsConfig.getGitCalls().stream().
                    filter(gitCall -> {
                        List<String> gitCallActions = gitCall.getActions();
                        return gitCallActions.contains(action) || gitCallActions.contains("*");
                    }).distinct().collect(Collectors.toList());

            for (var gitCall : filteredGitCalls) {
                switch (gitCall.getCommand()) {
                    case PUSH_TO_REPOSITORY:
                        gitInit(gitCall, templateDataObjects, gitCallsConfig);
                        gitPull(gitCall, templateDataObjects, currentStep, gitCallsConfig);
                        gitCommit(gitCall, templateDataObjects, gitCallsConfig);
                        gitPush(gitCall, templateDataObjects, gitCallsConfig);
                        break;
                    case PULL_FROM_REPOSITORY:
                        gitInit(gitCall, templateDataObjects, gitCallsConfig);
                        gitPull(gitCall, templateDataObjects, currentStep, gitCallsConfig);
                        break;
                }
            }
        }
    }

    @Override
    public Object configure(Map<String, Object> configuration, Map<String, Object> extensions)
            throws PackageConfigurationException {

        try {
            Object uriObj = configuration.get("uri");
            if (!isNullOrEmpty(uriObj)) {
                URI uri = URI.create(uriObj.toString());
                return resourceClientLibrary.getResource(uri, GitCallsConfiguration.class);
            }
        } catch (ServiceException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new PackageConfigurationException(e.getMessage(), e);
        }

        throw new PackageConfigurationException("No resource URI has been defined! [GitCallsConfiguration]");
    }

    public void gitInit(GitCall gitCall, Map<String, Object> templateDataObjects, GitCallsConfiguration gitCallsConfig) {
        try {
            String repositoryLocalDirectory =
                    getRepositoryNameFromUrl(templateDataObjects, gitCall.getBranch(), gitCallsConfig);

            if (!Paths.get(tmpPath + "/" + repositoryLocalDirectory).toFile().exists()) {
                Path gitPath = Files.createDirectories(Paths.get(tmpPath + "/" + repositoryLocalDirectory));
                Git git = Git.cloneRepository()
                        .setBranch(gitCall.getBranch())
                        .setURI(template(gitCallsConfig.getUrl(), templateDataObjects))
                        .setCredentialsProvider(
                                new UsernamePasswordCredentialsProvider(
                                        gitCallsConfig.getUsername(),
                                        gitCallsConfig.getPassword()))
                        .setDirectory(gitPath.toFile())
                        .call();
                StoredConfig config = git.getRepository().getConfig();
                config.setString(CONFIG_BRANCH_SECTION, "local-branch", "remote", gitCall.getBranch());
                config.setString(CONFIG_BRANCH_SECTION, "local-branch", "merge", "refs/heads/" + gitCall.getBranch());
                config.save();
            }

        } catch (IOException | GitAPIException | ITemplatingEngine.TemplateEngineException | URISyntaxException e) {
            log.error(e.getLocalizedMessage(), e);
        }
    }

    public void gitPull(GitCall gitCall, Map<String, Object> templateDataObjects,
                        IConversationMemory.IWritableConversationStep currentStep,
                        GitCallsConfiguration gitCallsConfig) {
        try {
            if (gitCallsConfig.getUrl() != null) {
                String repositoryLocalDirectory =
                        getRepositoryNameFromUrl(templateDataObjects, gitCall.getBranch(), gitCallsConfig);

                Path gitPath = Paths.get(tmpPath + "/" + repositoryLocalDirectory);
                PullResult pullResult = Git.open(gitPath.toFile())
                        .pull()
                        .setCredentialsProvider(
                                new UsernamePasswordCredentialsProvider(
                                        gitCallsConfig.getUsername(),
                                        gitCallsConfig.getPassword()))
                        .call();
                if (pullResult.isSuccessful()) {
                    List<GitFileEntry> fileEntries = new ArrayList<>();
                    Files.walkFileTree(gitPath, Collections.emptySet(), 3, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            if (Files.isRegularFile(file) && !file.getParent().toString().contains(".git")) {
                                GitFileEntry fileEntry = new GitFileEntry();
                                fileEntry.setFilename(file.getFileName().toString());
                                fileEntry.setDirectory(gitPath.toString());
                                fileEntry.setContent(Files.readString(file, StandardCharsets.UTF_8));
                                fileEntries.add(fileEntry);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
                    String memoryDataName = "gitCalls:" + gitCall.getDirectory();
                    IData<Object> gitData = dataFactory.createData(memoryDataName, fileEntries);
                    currentStep.storeData(gitData);
                    currentStep.addConversationOutputMap(KEY_GIT_CALLS, Map.of(gitCall.getDirectory(), gitData));
                } else {
                    log.error("Pull from repository was not successful! Please check your git settings! Maybe the path " + tmpPath + " is not empty or not a git repository");
                }
            }

        } catch (InvalidRemoteException e) {
            log.error("pull from configured repository failed - repository was not found, please check your settings");
        } catch (GitAPIException | IOException | ITemplatingEngine.TemplateEngineException | URISyntaxException e) {
            log.error(e.getLocalizedMessage(), e);
        }
    }

    public void gitCommit(GitCall gitCall, Map<String, Object> templatingDataObjects, GitCallsConfiguration gitCallsConfig) {
        try {
            if (gitCallsConfig.getUrl() != null) {
                String repositoryLocalDirectory =
                        getRepositoryNameFromUrl(templatingDataObjects, gitCall.getBranch(), gitCallsConfig);

                var filename = template(gitCall.getFilename(), templatingDataObjects);
                var directory = template(gitCall.getDirectory(), templatingDataObjects);
                var content = template(gitCall.getContent(), templatingDataObjects);
                var message = template(gitCall.getMessage(), templatingDataObjects);
                var path = tmpPath + "/" + repositoryLocalDirectory + "/" + directory;
                Files.createDirectories(Paths.get(path));
                var filepath = Paths.get(path + "/" + filename);
                Files.writeString(filepath, content);
                Path gitPath = Paths.get(tmpPath + "/" + repositoryLocalDirectory);
                Git.open(gitPath.toFile())
                        .add()
                        .addFilepattern(".")
                        .call();
                RevCommit commit = Git.open(gitPath.toFile())
                        .commit()
                        .setMessage(message)
                        .setCommitter("eddi", "eddi@labs.ai")
                        .call();
                log.info(commit.getFullMessage());
            } else {
                log.error("Git repo not initialized, please call gitInit first");
            }
        } catch (IOException | GitAPIException | ITemplatingEngine.TemplateEngineException | URISyntaxException e) {
            log.error(e.getLocalizedMessage(), e);
        }

    }

    public void gitPush(GitCall gitCall, Map<String, Object> templatingDataObjects, GitCallsConfiguration gitCallsConfig) {
        try {
            String repositoryLocalDirectory =
                    getRepositoryNameFromUrl(templatingDataObjects, gitCall.getBranch(), gitCallsConfig);

            Path gitPath = Paths.get(tmpPath + "/" + repositoryLocalDirectory);

            if (gitCallsConfig.getUrl() != null) {
                Iterable<PushResult> pushResults = Git.open(gitPath.toFile())
                        .push()
                        .setCredentialsProvider(
                                new UsernamePasswordCredentialsProvider(
                                        gitCallsConfig.getUsername(),
                                        gitCallsConfig.getPassword()))
                        .call();
                StringBuilder pushResultMessage = new StringBuilder();
                for (PushResult pushResult : pushResults) {
                    pushResultMessage.append(pushResult.getMessages());
                }
                log.info(pushResultMessage.toString());
            } else {
                log.error("No git settings in git call configuration, please add git settings!");
            }
        } catch (IOException | GitAPIException | ITemplatingEngine.TemplateEngineException | URISyntaxException e) {
            log.error(e.getLocalizedMessage(), e);
        }
    }

    private String template(String toBeTemplated, Map<String, Object> templatingDataObjects)
            throws ITemplatingEngine.TemplateEngineException {
        return templatingEngine.processTemplate(toBeTemplated, templatingDataObjects);
    }

    private String getRepositoryNameFromUrl(Map<String, Object> templatingDataObjects,
                                            String branch,
                                            GitCallsConfiguration gitCallsConfig)
            throws URISyntaxException, ITemplatingEngine.TemplateEngineException {

        URI uri = new URI(template(gitCallsConfig.getUrl(), templatingDataObjects));
        String path = uri.getPath();
        return path.substring(path.lastIndexOf('/') + 1) + "/" + branch;
    }

}
