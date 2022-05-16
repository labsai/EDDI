package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.bots.IBotStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus;
import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.runtime.IAutoBotDeployment;
import ai.labs.eddi.engine.runtime.IBotFactory;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.models.ConversationState;
import ai.labs.eddi.models.Deployment.Environment;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import static ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import static java.lang.String.format;
import static java.time.temporal.ChronoUnit.DAYS;

/**
 * @author ginccc
 */

@Startup(4000)
@ApplicationScoped
public class BotDeploymentManagement implements IAutoBotDeployment {
    private final IDeploymentStore deploymentStore;
    private final IBotFactory botFactory;
    private final IBotStore botStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final int maximumLifeTimeOfIdleConversationsInDays;

    private Instant lastDeploymentCheck = null;

    private static final Logger LOGGER = Logger.getLogger(BotDeploymentManagement.class);

    @Inject
    public BotDeploymentManagement(IDeploymentStore deploymentStore,
                                   IBotFactory botFactory,
                                   IBotStore botStore,
                                   IConversationMemoryStore conversationMemoryStore,
                                   IDocumentDescriptorStore documentDescriptorStore,
                                   @ConfigProperty(name = "eddi.conversations.maximumLifeTimeOfIdleConversationsInDays")
                                   int maximumLifeTimeOfIdleConversationsInDays) {
        this.deploymentStore = deploymentStore;
        this.botFactory = botFactory;
        this.botStore = botStore;
        this.conversationMemoryStore = conversationMemoryStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.maximumLifeTimeOfIdleConversationsInDays = maximumLifeTimeOfIdleConversationsInDays;
    }

    void onStart(@Observes StartupEvent ev) {
        try {
            autoDeployBots();
        } catch (IAutoBotDeployment.AutoDeploymentException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void autoDeployBots() throws AutoDeploymentException {
        manageBotDeployments();
    }

    @Scheduled(every = "24h")
    public void manageBotDeployments() throws AutoDeploymentException {
        try {
            var oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
            if (lastDeploymentCheck == null || lastDeploymentCheck.isBefore(oneHourAgo)) {
                lastDeploymentCheck = Instant.now();
                LOGGER.info("Starting deployment management of bots...");
                deploymentStore.readDeploymentInfos().stream().filter(
                                deploymentInfo -> deploymentInfo.getDeploymentStatus() == DeploymentStatus.deployed).
                        forEach(deploymentInfo -> {
                            Environment environment = Environment.valueOf(deploymentInfo.getEnvironment().toString());
                            String botId = deploymentInfo.getBotId();
                            Integer botVersion = deploymentInfo.getBotVersion();
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
                                    // we deploy a bot if it is the latest
                                    botFactory.deployBot(environment, botId, botVersion, null);
                                } else {
                                    // otherwise we attempt to undeploy it if this bot version is no longer in use
                                    endOldConversationsWithOldBots(botId, botVersion);

                                    var conversationCount =
                                            conversationMemoryStore.getActiveConversationCount(botId, botVersion);
                                    if (conversationCount == 0) {
                                        // this old bot version has no more active conversations connected to it,
                                        // so we undeploy it
                                        botFactory.undeployBot(environment, botId, botVersion);
                                        deploymentStore.setDeploymentInfo(environment.toString(),
                                                botId, botVersion, DeploymentInfo.DeploymentStatus.undeployed);
                                        LOGGER.info(format("Successfully undeployed bot (id: %s, version: %d)",
                                                botId, botVersion));
                                    } else {
                                        // not the latest bot, but still has active conversations connected to it,
                                        // therefore we deploy it as well to make sure we don't interrupt UX
                                        botFactory.deployBot(environment, botId, botVersion, null);
                                    }
                                }
                            } catch (ServiceException | IllegalAccessException | IllegalArgumentException |
                                     ResourceStoreException | ResourceNotFoundException e) {
                                var message =
                                        "Error while deployment management of bot " +
                                                "(environment=%s, botId=%s, version=%s)!\n";

                                LOGGER.error(format(message, environment, botId, botVersion));
                                LOGGER.error(e.getLocalizedMessage(), e);
                            }
                        });

                LOGGER.info("Finished managing the deployment of bots.");
            }
        } catch (ResourceStoreException e) {
            throw new AutoDeploymentException(e.getLocalizedMessage(), e);
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
}
