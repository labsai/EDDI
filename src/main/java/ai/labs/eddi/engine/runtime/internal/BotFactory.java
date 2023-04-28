package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.IConversation.IConversationOutputRenderer;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.runtime.IBot;
import ai.labs.eddi.engine.runtime.IBotFactory;
import ai.labs.eddi.engine.runtime.IExecutablePackage;
import ai.labs.eddi.engine.runtime.client.bots.IBotStoreClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.models.Context;
import ai.labs.eddi.models.Deployment;
import lombok.*;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */

@ApplicationScoped
public class BotFactory implements IBotFactory {
    private final Map<Deployment.Environment, ConcurrentHashMap<BotId, IBot>> environments;
    private final IBotStoreClientLibrary botStoreClientLibrary;

    private static final Logger log = Logger.getLogger(BotFactory.class);

    @Inject
    public BotFactory(IBotStoreClientLibrary botStoreClientLibrary) {
        this.botStoreClientLibrary = botStoreClientLibrary;
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

        Map<BotId, IBot> bots = getBotEnvironment(environment);
        List<BotId> botVersions = bots.keySet().stream().filter(id -> id.getId().equals(botId)).
                sorted(Collections.reverseOrder((botId1, botId2) ->
                        botId1.getVersion() < botId2.getVersion() ?
                                -1 : botId1.getVersion().equals(botId2.getVersion()) ? 0 : 1)).
                collect(Collectors.toCollection(LinkedList::new));

        IBot latestBot = null;

        if (!botVersions.isEmpty()) {
            for (BotId botVersion : botVersions) {
                IBot bot = bots.get(botVersion);
                if (bot.getDeploymentStatus() == Deployment.Status.READY) {
                    latestBot = bot;
                    break;
                }
            }
        }

        return latestBot;
    }

    @Override
    public List<IBot> getAllLatestBots(Deployment.Environment environment) {
        Map<String, IBot> ret = new LinkedHashMap<>();

        for (BotId botIdObj : getBotEnvironment(environment).keySet()) {
            IBot nextBot = getLatestBot(environment, botIdObj.getId());
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
        IBot bot;
        bot = waitIfBotIsInDeployment(bots, botIdObj);
        if (bot == null) return null;

        return bot;
    }

    private static IBot waitIfBotIsInDeployment(ConcurrentHashMap<BotId, IBot> bots, BotId botIdObj) {
        IBot bot;
        int counter = 0;
        while ((bot = bots.get(botIdObj)) != null &&
                bot.getDeploymentStatus() == Deployment.Status.IN_PROGRESS) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                log.error(e.getLocalizedMessage(), e);
                return null;
            }
            counter++;

            if (counter > 60) {
                log.error("Waited too long for bot deployment to complete (timeout reached at 60s).");
                bot = null;
                break;
            }
        }

        return bot;
    }

    @Override
    public void deployBot(Deployment.Environment environment, final String botId, final Integer version,
                          DeploymentProcess deploymentProcess) throws ServiceException, IllegalAccessException {
        deploymentProcess = defaultIfNull(deploymentProcess);

        BotId id = new BotId(botId, version);
        ConcurrentHashMap<BotId, IBot> botEnvironment = getBotEnvironment(environment);
        // fast path
        if (!botEnvironment.containsKey(id)) {
            logBotDeployment(environment.toString(), botId, version, Deployment.Status.IN_PROGRESS);
            Bot progressDummyBot = createInProgressDummyBot(botId, version);
            // atomically register dummy bot in environment
            if (botEnvironment.putIfAbsent(id, progressDummyBot) == null) {
                IBot bot;
                try {
                    bot = botStoreClientLibrary.getBot(botId, version);
                } catch (ServiceException e) {
                    Deployment.Status error = Deployment.Status.ERROR;
                    progressDummyBot.setDeploymentStatus(error);
                    deploymentProcess.completed(error);
                    logBotDeployment(environment.toString(), botId, version, error);
                    throw e;
                }

                Deployment.Status ready = Deployment.Status.READY;
                ((Bot) bot).setDeploymentStatus(ready);
                botEnvironment.put(id, bot);
                deploymentProcess.completed(ready);
                logBotDeployment(environment.toString(), botId, version, ready);
            }
        }
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
    @ToString
    private class BotId {
        private String id;
        private Integer version;
    }
}
