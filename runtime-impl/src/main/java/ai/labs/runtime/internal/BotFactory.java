package ai.labs.runtime.internal;

import ai.labs.lifecycle.IConversation;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.model.Deployment;
import ai.labs.runtime.IBot;
import ai.labs.runtime.IBotFactory;
import ai.labs.runtime.IExecutablePackage;
import ai.labs.runtime.client.bots.IBotStoreClientLibrary;
import ai.labs.runtime.service.ServiceException;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
@Slf4j
public class BotFactory implements IBotFactory {
    private final Map<Deployment.Environment, ConcurrentHashMap<BotId, IBot>> environments;
    private final IBotStoreClientLibrary botStoreClientLibrary;

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
    public IBot getLatestBot(Deployment.Environment environment, String botId) throws ServiceException {
        List<BotId> botVersions = new LinkedList<>();

        Map<BotId, IBot> bots = getBotEnvironment(environment);
        botVersions.addAll(bots.keySet().stream().filter(id -> id.getId().equals(botId)).collect(Collectors.toList()));
        botVersions.sort(Collections.reverseOrder((botId1, botId2) ->
                botId1.getVersion() < botId2.getVersion() ?
                        -1 : botId1.getVersion().equals(botId2.getVersion()) ? 0 : 1));

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
    public IBot getBot(Deployment.Environment environment, final String botId, final Integer version) {
        Map<BotId, IBot> bots = getBotEnvironment(environment);
        return bots.get(new BotId(botId, version));
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
                    // on failure, remove any entry from environment to allow redeployment
                    botEnvironment.remove(id);
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
    public void undeployBot(Deployment.Environment environment, String botId, Integer version) throws ServiceException, IllegalAccessException {
        Map<BotId, IBot> botEnvironment = getBotEnvironment(environment);

        BotId id = new BotId(botId, version);
        if (botEnvironment.containsKey(id)) {
            botEnvironment.remove(id);
        }
    }

    private ConcurrentHashMap<BotId, IBot> getBotEnvironment(Deployment.Environment environment) {
        return environments.get(environment);
    }

    private Bot createInProgressDummyBot(String botId, Integer version) throws IllegalAccessException {
        Bot bot = new Bot(botId, version) {
            @Override
            public void addPackage(IExecutablePackage executablePackage) throws IllegalAccessException {
                throw createBotInProgressException();
            }

            @Override
            public IConversation startConversation(IConversation.IConversationOutputRenderer outputProvider)
                    throws InstantiationException, IllegalAccessException {
                throw createBotInProgressException();
            }

            @Override
            public IConversation continueConversation(IConversationMemory conversationMemory,
                                                      IConversation.IConversationOutputRenderer outputProvider)
                    throws InstantiationException, IllegalAccessException {
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
            log.info(String.format("Deploying Bot... (environment=%s, id=%s , version=%s)",
                    environment, botId, botVersion));
        } else {
            log.info(String.format("Bot is deployed. (environment=%s, id=%s , version=%s)  Status: %s",
                    environment, botId, botVersion, status));
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
