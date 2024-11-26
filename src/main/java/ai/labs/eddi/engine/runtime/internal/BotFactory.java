package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.IConversation.IConversationOutputRenderer;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IBot;
import ai.labs.eddi.engine.runtime.IBotFactory;
import ai.labs.eddi.engine.runtime.IExecutablePackage;
import ai.labs.eddi.engine.runtime.client.bots.IBotStoreClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.*;

/**
 * @author ginccc
 */

@ApplicationScoped
public class BotFactory implements IBotFactory {
    private final Map<Deployment.Environment, ConcurrentHashMap<BotId, IBot>> environments;
    private final List<BotId> deployedBots;
    private final IBotStoreClientLibrary botStoreClientLibrary;
    private final IDeploymentListener deploymentListener;

    private static final Logger log = Logger.getLogger(BotFactory.class);

    @Inject
    public BotFactory(IBotStoreClientLibrary botStoreClientLibrary,
                      IDeploymentListener deploymentListener,
                      MeterRegistry meterRegistry) {
        this.botStoreClientLibrary = botStoreClientLibrary;
        this.deploymentListener = deploymentListener;
        this.deployedBots = new LinkedList<>();
        meterRegistry.gaugeCollectionSize("eddi_bots_deployed", Tags.empty(), deployedBots);
        this.environments = Collections.unmodifiableMap(createEmptyEnvironments());
    }

    private Map<Deployment.Environment, ConcurrentHashMap<BotId, IBot>> createEmptyEnvironments() {
        Map<Deployment.Environment, ConcurrentHashMap<BotId, IBot>> environments =
                new HashMap<>(Deployment.Environment.values().length);
        environments.put(Deployment.Environment.restricted, new ConcurrentHashMap<>());
        environments.put(Deployment.Environment.unrestricted, new ConcurrentHashMap<>());
        environments.put(Deployment.Environment.test, new ConcurrentHashMap<>());
        return environments;
    }

    @Override
    public IBot getLatestBot(Deployment.Environment environment, String botId) {
        return findLatestBot(environment, botId, null); // No filter, return the most recent bot in any state
    }

    @Override
    public IBot getLatestReadyBot(Deployment.Environment environment, String botId) {
        return findLatestBot(environment, botId, Deployment.Status.READY);
    }

    private IBot findLatestBot(Deployment.Environment environment, String botId, Deployment.Status requiredStatus) {
        Map<BotId, IBot> bots = getBotEnvironment(environment);
        List<BotId> botVersions = bots.keySet().stream()
                .filter(id -> id.getId().equals(botId))
                .sorted(Collections.reverseOrder(Comparator.comparingInt(BotId::getVersion)))
                .toList();

        for (BotId botVersion : botVersions) {
            IBot bot = bots.get(botVersion);
            if (bot != null && (requiredStatus == null || bot.getDeploymentStatus() == requiredStatus)) {
                return bot;
            }
        }

        return null; // Return null if no matching bot is found
    }

    @Override
    public List<IBot> getAllLatestBots(Deployment.Environment environment) {
        Map<String, IBot> ret = new LinkedHashMap<>();

        for (BotId botIdObj : getBotEnvironment(environment).keySet()) {
            IBot nextBot = getLatestBot(environment, botIdObj.getId());
            if (nextBot == null) {
                continue;
            }

            String botId = botIdObj.getId();
            IBot currentBot = ret.get(botId);
            if (ret.containsKey(botId)) {
                if (currentBot.getBotVersion() < nextBot.getBotVersion()) {
                    ret.put(botId, nextBot);
                }
            } else {
                ret.put(botIdObj.getId(), nextBot);
            }

        }

        return new LinkedList<>(ret.values());
    }

    @Override
    public IBot getBot(Deployment.Environment environment, final String botId, final Integer version) {
        var bots = getBotEnvironment(environment);
        var botIdObj = new BotId(botId, version);

        // Check if the bot is already in a non-IN_PROGRESS state
        IBot bot = bots.get(botIdObj);
        if (bot != null) {
            if (bot.getDeploymentStatus() != Deployment.Status.IN_PROGRESS) {
                return bot;
            } else {
                return waitForDeploymentCompletion(botIdObj, environment);
            }
        }

        return null;
    }

    private IBot waitForDeploymentCompletion(BotId botIdObj, Deployment.Environment environment) {
        var deploymentFuture = deploymentListener.getRegisteredDeploymentEvent(botIdObj.getId(), botIdObj.getVersion());

        try {
            if (deploymentFuture != null) {
                deploymentFuture.orTimeout(60, TimeUnit.SECONDS).join();
            }

            // Re-fetch the bot after deployment is complete
            IBot bot = getBotEnvironment(environment).get(botIdObj);
            if (bot == null || bot.getDeploymentStatus() == Deployment.Status.IN_PROGRESS) {
                log.error("Bot deployment did not complete successfully for botId: " + botIdObj);
                return null;
            }

            return bot;
        } catch (CancellationException e) {
            log.error("Waited too long for bot deployment to complete (timeout reached at 60s).", e);
            return null;
        } catch (Exception e) {
            log.error("Error while waiting for bot deployment: " + e.getMessage(), e);
            return null;
        }
    }


    @Override
    public void deployBot(Deployment.Environment environment, final String botId, final Integer version,
                          DeploymentProcess deploymentProcess) {
        var finalDeploymentProcess = defaultIfNull(deploymentProcess);

        BotId id = new BotId(botId, version);
        ConcurrentHashMap<BotId, IBot> botEnvironment = getBotEnvironment(environment);

        botEnvironment.compute(id, (key, existingBot) -> {
            if (existingBot != null) {
                // If a bot already exists, ensure it is in a valid state
                if (existingBot.getDeploymentStatus() == Deployment.Status.READY) {
                    log.debug(String.format("Bot is already deployed: %s (environment=%s, version=%d)", botId, environment, version));
                    finalDeploymentProcess.completed(Deployment.Status.READY);
                    return existingBot; // No need to redeploy
                }

                if (existingBot.getDeploymentStatus() == Deployment.Status.IN_PROGRESS) {
                    log.debug(String.format("Bot deployment is already in progress: %s (environment=%s, version=%d)", botId, environment, version));
                    return existingBot; // Keep the IN_PROGRESS state
                }
            }

            // Begin deployment
            logBotDeployment(environment.toString(), botId, version, Deployment.Status.IN_PROGRESS);
            var progressDummyBot = createInProgressDummyBot(botId, version);

            try {
                IBot bot = botStoreClientLibrary.getBot(botId, version);
                ((Bot) bot).setDeploymentStatus(Deployment.Status.READY);

                finalDeploymentProcess.completed(Deployment.Status.READY);
                logBotDeployment(environment.toString(), botId, version, Deployment.Status.READY);

                // Add the deployed bot to the deployedBots list if it's not already there
                synchronized (deployedBots) {
                    if (!deployedBots.contains(id)) {
                        deployedBots.add(id);
                    }
                }

                return bot; // Replace the dummy bot with the actual bot
            } catch (ServiceException e) {
                progressDummyBot.setDeploymentStatus(Deployment.Status.ERROR);
                finalDeploymentProcess.completed(Deployment.Status.ERROR);
                logBotDeployment(environment.toString(), botId, version, Deployment.Status.ERROR);
                throw new IllegalStateException(String.format("Failed to deploy bot: %s (environment=%s, version=%d)", botId, environment, version), e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private DeploymentProcess defaultIfNull(DeploymentProcess deploymentProcess) {
        return deploymentProcess == null ? status -> {
        } : deploymentProcess;
    }

    @Override
    public void undeployBot(Deployment.Environment environment, String botId, Integer version) {
        Map<BotId, IBot> botEnvironment = getBotEnvironment(environment);

        BotId id = new BotId(botId, version);
        botEnvironment.remove(id);
        deployedBots.remove(id);
    }

    private ConcurrentHashMap<BotId, IBot> getBotEnvironment(Deployment.Environment environment) {
        return environments.get(environment);
    }

    private Bot createInProgressDummyBot(String botId, Integer version) {
        Bot bot = new Bot(botId, version) {
            @Override
            public void addPackage(IExecutablePackage executablePackage) throws IllegalAccessException {
                throw createBotInProgressException();
            }

            @Override
            public IConversation startConversation(String userId,
                                                   Map<String, Context> context,
                                                   IPropertiesHandler propertiesHandler,
                                                   IConversationOutputRenderer outputProvider)
                    throws IllegalAccessException {

                throw createBotInProgressException();
            }

            @Override
            public IConversation continueConversation(IConversationMemory conversationMemory,
                                                      IPropertiesHandler propertiesHandler,
                                                      IConversationOutputRenderer outputProvider)
                    throws IllegalAccessException {

                throw createBotInProgressException();
            }
        };

        bot.setDeploymentStatus(Deployment.Status.IN_PROGRESS);
        return bot;
    }

    private static IllegalAccessException createBotInProgressException() {
        return new IllegalAccessException("Bot deployment is still in progress!");
    }

    private void logBotDeployment(String environment, String botId, Integer botVersion, Deployment.Status status) {
        if (status == Deployment.Status.IN_PROGRESS) {
            log.info(String.format("Deploying Bot... (environment=%s, botId=%s , version=%s)",
                    environment, botId, botVersion));
        } else {
            log.info(String.format("Bot deployed with status: %s (environment=%s, botId=%s , version=%s)", status,
                    environment, botId, botVersion));
        }
    }

    @AllArgsConstructor
    @Getter
    @Setter
    @EqualsAndHashCode
    private static class BotId {
        private String id;
        private Integer version;

        @Override
        public String toString() {
            return id + ":" + version;
        }
    }
}
