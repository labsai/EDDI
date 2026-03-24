package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.V6RenameMigration;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.runtime.IAgentDeploymentManagement;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.readiness.IAgentsReadiness;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.engine.memory.model.ConversationState;
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
public class AgentDeploymentManagement implements IAgentDeploymentManagement {
    private final IDeploymentStore deploymentStore;
    private final IAgentFactory agentFactory;
    private final IAgentStore agentStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IMigrationManager migrationManager;
    private final V6RenameMigration v6RenameMigration;
    private final IAgentsReadiness agentsReadiness;
    private final IRuntime runtime;
    private final int maximumLifeTimeOfIdleConversationsInDays;
    private Instant lastDeploymentCheck = null;
    private static final Logger LOGGER = Logger.getLogger(AgentDeploymentManagement.class);
    private final List<DeploymentInfo> deploymentInfos = new LinkedList<>();

    @Inject
    public AgentDeploymentManagement(IDeploymentStore deploymentStore,
            IAgentFactory agentFactory,
            IAgentStore agentStore,
            IAgentsReadiness agentsReadiness,
            IConversationMemoryStore conversationMemoryStore,
            IDocumentDescriptorStore documentDescriptorStore,
            IMigrationManager migrationManager,
            V6RenameMigration v6RenameMigration,
            IRuntime runtime,
            @ConfigProperty(name = "eddi.conversations.maximumLifeTimeOfIdleConversationsInDays") int maximumLifeTimeOfIdleConversationsInDays) {
        this.deploymentStore = deploymentStore;
        this.agentFactory = agentFactory;
        this.agentStore = agentStore;
        this.agentsReadiness = agentsReadiness;
        this.conversationMemoryStore = conversationMemoryStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.migrationManager = migrationManager;
        this.v6RenameMigration = v6RenameMigration;
        this.runtime = runtime;
        this.maximumLifeTimeOfIdleConversationsInDays = maximumLifeTimeOfIdleConversationsInDays;
    }

    void onStart(@Observes StartupEvent ev) {
        runtime.getScheduledExecutorService().schedule(() -> {
            autoDeployAgents();

            return null;
        }, 1000, TimeUnit.MILLISECONDS);
    }

    @Override
    public void autoDeployAgents() {
        LOGGER.info("Starting deployment of agents...");

        // V6 rename migration must run before document-level migrations
        v6RenameMigration.runIfNeeded();

        migrationManager.startMigrationIfFirstTimeRun(() -> {
            checkDeployments();
            agentsReadiness.setAgentsReadiness(true);
        });

        LOGGER.info("Finished deployment of agents.");
        LOGGER.info("E.D.D.I is ready!");
    }

    @Scheduled(every = "10s", delay = 10)
    public void checkDeployments() {
        try {
            deploymentStore.readDeploymentInfos(deployed).stream()
                    .filter(deploymentInfo -> deploymentInfo.getAgentId() != null
                            && deploymentInfo.getAgentVersion() != null)
                    .filter(deploymentInfo -> !this.deploymentInfos.contains(deploymentInfo))
                    .forEach(deploymentInfo -> {
                        try {
                            agentFactory.deployAgent(deploymentInfo.getEnvironment(),
                                    deploymentInfo.getAgentId(),
                                    deploymentInfo.getAgentVersion(),
                                    null);

                            this.deploymentInfos.add(deploymentInfo);
                        } catch (ServiceException | IllegalAccessException e) {
                            LOGGER.error(e.getLocalizedMessage(), e);
                        } catch (Exception e) {
                            // Catch any other exception (e.g. IllegalStateException wrapping
                            // ResourceNotFoundException) so one broken Agent doesn't block all others
                            LOGGER.error(format(
                                    "Failed to deploy Agent (id=%s, version=%d, environment=%s), skipping. Cause: %s",
                                    deploymentInfo.getAgentId(),
                                    deploymentInfo.getAgentVersion(),
                                    deploymentInfo.getEnvironment(),
                                    e.getMessage()));

                            // If the root cause is a missing resource, auto-clean the stale record
                            if (isCausedByResourceNotFound(e)) {
                                LOGGER.warn(format(
                                        "Agent config not found for id=%s version=%d — marking deployment as undeployed",
                                        deploymentInfo.getAgentId(), deploymentInfo.getAgentVersion()));
                                deploymentStore.setDeploymentInfo(
                                        deploymentInfo.getEnvironment().toString(),
                                        deploymentInfo.getAgentId(),
                                        deploymentInfo.getAgentVersion(),
                                        undeployed);
                            }
                        }
                    });
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    private boolean isCausedByResourceNotFound(Throwable t) {
        while (t != null) {
            if (t instanceof ResourceNotFoundException)
                return true;
            t = t.getCause();
        }
        return false;
    }

    @Scheduled(every = "24h", delay = 300)
    public void manageAgentDeployments() {
        try {
            var oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
            if (lastDeploymentCheck == null || lastDeploymentCheck.isBefore(oneHourAgo)) {
                lastDeploymentCheck = Instant.now();
                var postUndeloymentAttempts = deploymentStore.readDeploymentInfos(deployed).stream()
                        .filter(deploymentInfo -> deploymentInfo.getAgentId() != null
                                && deploymentInfo.getAgentVersion() != null)
                        .map(deploymentInfo -> {
                            var environmentName = deploymentInfo.getEnvironment().toString();
                            var environment = Environment.valueOf(environmentName);
                            var agentId = deploymentInfo.getAgentId();
                            var agentVersion = deploymentInfo.getAgentVersion();
                            try {
                                IResourceId latestAgent;
                                try {
                                    latestAgent = agentStore.getCurrentResourceId(agentId);
                                } catch (ResourceNotFoundException e) {
                                    // there is no latest Agent version found, so this Agent was very likely
                                    // deleted,
                                    // therefore we will treat it like an older Agent version and try to undeploy
                                    // if no longer in use
                                    latestAgent = null;
                                }

                                if (latestAgent != null && latestAgent.getVersion() <= agentVersion) {
                                    // we attempt to deploy a Agent if it is the latest
                                    agentFactory.deployAgent(environment, agentId, agentVersion, null);
                                } else {
                                    manageDeploymentOfOldAgent(environment, agentId, agentVersion);

                                    return (UndeploymentExecutor) () -> {
                                        try {
                                            // attempt to undeploy Agent if this Agent version is no longer in use
                                            endOldConversationsWithOldAgents(agentId, agentVersion);

                                            manageDeploymentOfOldAgent(environment, agentId, agentVersion);
                                        } catch (ResourceStoreException | ResourceNotFoundException | ServiceException
                                                | IllegalAccessException e) {
                                            LOGGER.error(e.getLocalizedMessage(), e);
                                        }
                                    };
                                }
                            } catch (ServiceException | IllegalAccessException | IllegalArgumentException e) {
                                var message = "Error while deployment management of Agent " +
                                        "(environment=%s, agentId=%s, version=%s)!\n";

                                LOGGER.error(format(message, environment, agentId, agentVersion));
                                LOGGER.error(e.getLocalizedMessage(), e);
                            }

                            return (UndeploymentExecutor) () -> {
                            };
                        }).toList();

                // run all undeploy attempts of old agents after all current agents have been
                // deployed and see
                // if we can undeploy Agent version if we end old conversations
                postUndeloymentAttempts.forEach(UndeploymentExecutor::attemptUndeploy);
            }
        } catch (ResourceStoreException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    private void manageDeploymentOfOldAgent(Environment environment, String agentId, Integer agentVersion)
            throws ServiceException, IllegalAccessException {

        var conversationCount = conversationMemoryStore.getActiveConversationCount(agentId, agentVersion);
        if (conversationCount == 0) {
            // this old Agent version has no more active conversations connected to it,
            // so we undeploy it
            agentFactory.undeployAgent(environment, agentId, agentVersion);
            deploymentStore.setDeploymentInfo(environment.toString(), agentId, agentVersion, undeployed);
            LOGGER.info(format("Successfully undeployed Agent (id: %s, version: %d)", agentId, agentVersion));
        } else {
            // not the latest agent, but still has active conversations connected to it,
            // therefore we deploy it as well to make sure we don't interrupt UX
            agentFactory.deployAgent(environment, agentId, agentVersion, null);
        }
    }

    private void endOldConversationsWithOldAgents(String agentId, Integer agentVersion)
            throws ResourceStoreException, ResourceNotFoundException {

        var conversationMemorySnapshots = conversationMemoryStore.loadActiveConversationMemorySnapshot(agentId,
                agentVersion);

        for (var conversationMemory : conversationMemorySnapshots) {
            var documentDescriptor = documentDescriptorStore.readDescriptor(
                    conversationMemory.getAgentId(), conversationMemory.getAgentVersion());

            var timeOfLastInteractionInConversation = documentDescriptor.getLastModifiedOn();

            var isOlderThanMaximumAmountOfDays = isOlderThanDays(
                    Instant.ofEpochMilli(timeOfLastInteractionInConversation.getTime()).atZone(ZoneId.systemDefault())
                            .toLocalDate(),
                    maximumLifeTimeOfIdleConversationsInDays);

            if (isOlderThanMaximumAmountOfDays) {
                String conversationId = conversationMemory.getId();
                conversationMemoryStore.setConversationState(
                        conversationId, ConversationState.ENDED);
                var message = format(
                        "Ended conversation (id: %s) with Agent (name: %s, id: %s, version: %d) " +
                                "because it is %d days older than the maximum idle time of %d days",
                        conversationId, documentDescriptor.getName(), agentId, agentVersion,
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
