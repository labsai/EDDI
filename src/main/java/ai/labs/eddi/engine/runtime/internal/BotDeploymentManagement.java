package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.bots.IBotStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.runtime.IBotDeploymentManagement;
import ai.labs.eddi.engine.runtime.IBotFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.readiness.IBotsReadiness;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.engine.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus.deployed;
import static ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus.undeployed;
import static ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import static ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import static java.lang.String.format;
import static java.time.temporal.ChronoUnit.DAYS;

/**
 * @author ginccc
 */

@Startup
@Priority(4000)
@ApplicationScoped
public class BotDeploymentManagement implements IBotDeploymentManagement {
    private final IDeploymentStore deploymentStore;
    private final IBotFactory botFactory;
    private final IBotStore botStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IMigrationManager migrationManager;
    private final IBotsReadiness botsReadiness;
    private final IRuntime runtime;
    private final int maximumLifeTimeOfIdleConversationsInDays;
    private Instant lastDeploymentCheck = null;
    private static final Logger LOGGER = Logger.getLogger(BotDeploymentManagement.class);
    private final List<DeploymentInfo> deploymentInfos = new LinkedList<>();

    @Inject
    public BotDeploymentManagement(IDeploymentStore deploymentStore,
                                   IBotFactory botFactory,
                                   IBotStore botStore,
                                   IBotsReadiness botsReadiness,
                                   IConversationMemoryStore conversationMemoryStore,
                                   IDocumentDescriptorStore documentDescriptorStore,
                                   IMigrationManager migrationManager,
                                   IRuntime runtime,
                                   @ConfigProperty(name = "eddi.conversations.maximumLifeTimeOfIdleConversationsInDays")
                                   int maximumLifeTimeOfIdleConversationsInDays) {
        this.deploymentStore = deploymentStore;
        this.botFactory = botFactory;
        this.botStore = botStore;
        this.botsReadiness = botsReadiness;
        this.conversationMemoryStore = conversationMemoryStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.migrationManager = migrationManager;
        this.runtime = runtime;
        this.maximumLifeTimeOfIdleConversationsInDays = maximumLifeTimeOfIdleConversationsInDays;
    }

    void onStart(@Observes StartupEvent ev) {
        runtime.submitScheduledCallable(() -> {
            autoDeployBots();

            return null;
        }, 1000, TimeUnit.MILLISECONDS, Collections.emptyMap());
    }

    @Override
    public void autoDeployBots() {
        LOGGER.info("Starting deployment of bots...");

        migrationManager.startMigrationIfFirstTimeRun(() -> {
            checkDeployments();
            botsReadiness.setBotsReadiness(true);
        });

        LOGGER.info("Finished deployment of bots.");
        LOGGER.info("E.D.D.I is ready!");
    }

    @Scheduled(every = "10s", delay = 10)
    public void checkDeployments() {
        try {
            deploymentStore.readDeploymentInfos(deployed).stream().
                    filter(deploymentInfo -> !this.deploymentInfos.contains(deploymentInfo)).
                    forEach(deploymentInfo -> {
                        try {
                            botFactory.deployBot(deploymentInfo.getEnvironment(),
                                    deploymentInfo.getBotId(),
                                    deploymentInfo.getBotVersion(),
                                    null);

                            this.deploymentInfos.add(deploymentInfo);
                        } catch (ServiceException | IllegalAccessException e) {
                            LOGGER.error(e.getLocalizedMessage(), e);
                        }
                    });
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    @Scheduled(every = "24h", delay = 300)
    public void manageBotDeployments() {
        try {
            var oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
            if (lastDeploymentCheck == null || lastDeploymentCheck.isBefore(oneHourAgo)) {
                lastDeploymentCheck = Instant.now();
                var postUndeloymentAttempts =
                        deploymentStore.readDeploymentInfos(deployed).stream().
                                map(deploymentInfo -> {
                                    var environmentName = deploymentInfo.getEnvironment().toString();
                                    var environment = Environment.valueOf(environmentName);
                                    var botId = deploymentInfo.getBotId();
                                    var botVersion = deploymentInfo.getBotVersion();
                                    try {
                                        IResourceId latestBot;
                                        try {
                                            latestBot = botStore.getCurrentResourceId(botId);
                                        } catch (ResourceNotFoundException e) {
                                            // there is no latest bot version found, so this bot was very likely deleted,
                                            // therefore we will treat it like an older bot version and try to undeploy
                                            // if no longer in use
                                            latestBot = null;
                                        }

                                        if (latestBot != null && latestBot.getVersion() <= botVersion) {
                                            // we attempt to deploy a bot if it is the latest
                                            botFactory.deployBot(environment, botId, botVersion, null);
                                        } else {
                                            manageDeploymentOfOldBot(environment, botId, botVersion);

                                            return (UndeploymentExecutor) () -> {
                                                try {
                                                    // attempt to undeploy bot if this bot version is no longer in use
                                                    endOldConversationsWithOldBots(botId, botVersion);

                                                    manageDeploymentOfOldBot(environment, botId, botVersion);
                                                } catch (ResourceStoreException |
                                                         ResourceNotFoundException |
                                                         ServiceException |
                                                         IllegalAccessException e) {
                                                    LOGGER.error(e.getLocalizedMessage(), e);
                                                }
                                            };
                                        }
                                    } catch (ServiceException | IllegalAccessException |
                                             IllegalArgumentException e) {
                                        var message =
                                                "Error while deployment management of bot " +
                                                        "(environment=%s, botId=%s, version=%s)!\n";

                                        LOGGER.error(format(message, environment, botId, botVersion));
                                        LOGGER.error(e.getLocalizedMessage(), e);
                                    }

                                    return (UndeploymentExecutor) () -> {
                                    };
                                }).toList();

                // run all undeploy attempts of old bots after all current bots have been deployed and see
                // if we can undeploy bot version if we end old conversations
                postUndeloymentAttempts.forEach(UndeploymentExecutor::attemptUndeploy);
            }
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    private void manageDeploymentOfOldBot(Environment environment, String botId, Integer botVersion)
            throws ServiceException, IllegalAccessException {

        var conversationCount = conversationMemoryStore.getActiveConversationCount(botId, botVersion);
        if (conversationCount == 0) {
            // this old bot version has no more active conversations connected to it,
            // so we undeploy it
            botFactory.undeployBot(environment, botId, botVersion);
            deploymentStore.setDeploymentInfo(environment.toString(), botId, botVersion, undeployed);
            LOGGER.info(format("Successfully undeployed bot (id: %s, version: %d)", botId, botVersion));
        } else {
            // not the latest bot, but still has active conversations connected to it,
            // therefore we deploy it as well to make sure we don't interrupt UX
            botFactory.deployBot(environment, botId, botVersion, null);
        }
    }

    private void endOldConversationsWithOldBots(String botId, Integer botVersion)
            throws ResourceStoreException, ResourceNotFoundException {

        var conversationMemorySnapshots =
                conversationMemoryStore.loadActiveConversationMemorySnapshot(botId, botVersion);

        for (var conversationMemory : conversationMemorySnapshots) {
            var documentDescriptor =
                    documentDescriptorStore.readDescriptor(
                            conversationMemory.getBotId(), conversationMemory.getBotVersion());

            var timeOfLastInteractionInConversation = documentDescriptor.getLastModifiedOn();

            var isOlderThanMaximumAmountOfDays =
                    isOlderThanDays(Instant.ofEpochMilli(timeOfLastInteractionInConversation.getTime()).
                            atZone(ZoneId.systemDefault()).toLocalDate(), maximumLifeTimeOfIdleConversationsInDays);

            if (isOlderThanMaximumAmountOfDays) {
                String conversationId = conversationMemory.getId();
                conversationMemoryStore.setConversationState(
                        conversationId, ConversationState.ENDED);
                var message = format(
                        "Ended conversation (id: %s) with bot (name: %s, id: %s, version: %d) " +
                                "because it is %d days older than the maximum idle time of %d days",
                        conversationId, documentDescriptor.getName(), botId, botVersion,
                        DAYS.between(timeOfLastInteractionInConversation.toInstant(), Instant.now()),
                        maximumLifeTimeOfIdleConversationsInDays);

                LOGGER.info(message);
            }
        }
    }

    private boolean isOlderThanDays(final LocalDate date, final int days) {
        boolean result = false;

        LocalDate now = LocalDate.now();
        // period from now to date
        Period period = Period.between(now, date);

        if (period.getYears() < 0) {
            // if year is negative, 100% older than 6 months
            result = true;
        } else if (period.getYears() == 0) {
            if (period.getDays() <= -days) {
                result = true;
            }
        }

        return result;
    }

    private interface UndeploymentExecutor {
        void attemptUndeploy();
    }
}
