package io.sls.runtime.internal;

import ai.labs.lifecycle.IConversation;
import io.sls.memory.IConversationMemory;
import io.sls.memory.model.Deployment;
import io.sls.runtime.IBot;
import io.sls.runtime.IBotFactory;
import io.sls.runtime.IExecutablePackage;
import io.sls.runtime.client.bots.IBotStoreClientLibrary;
import io.sls.runtime.service.ServiceException;

import javax.inject.Inject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
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
        Collections.sort(botVersions,
                Collections.reverseOrder((botId1, botId2) ->
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
    public void deployBot(Deployment.Environment environment, final String botId, final Integer version) throws ServiceException, IllegalAccessException {
        BotId id = new BotId(botId, version);
        ConcurrentHashMap<BotId, IBot> botEnvironment = getBotEnvironment(environment);
        // fast path
        if (!botEnvironment.containsKey(id)) {
            Bot emptyBot = new Bot(botId, version) {
                @Override
                public void addPackage(IExecutablePackage executablePackage) throws IllegalAccessException {
                    throw new IllegalAccessException("Bot deployment is still in progress!");
                }

                @Override
                public IConversation startConversation(IConversation.IConversationOutputRenderer outputProvider) throws InstantiationException, IllegalAccessException {
                    throw new IllegalAccessException("Bot deployment is still in progress!");
                }

                @Override
                public IConversation continueConversation(IConversationMemory conversationMemory, IConversation.IConversationOutputRenderer outputProvider) throws InstantiationException, IllegalAccessException {
                    throw new IllegalAccessException("Bot deployment is still in progress!");
                }
            };
            emptyBot.setDeploymentStatus(Deployment.Status.IN_PROGRESS);

            // atomically register dummy bot in environment
            if (botEnvironment.putIfAbsent(id, emptyBot) == null) {
                IBot bot;
                try {
                    bot = botStoreClientLibrary.getBot(botId, version);
                } catch (ServiceException e) {
                    emptyBot.setDeploymentStatus(Deployment.Status.ERROR);
                    // on failure, remove any entry from environment to allow redeployment
                    botEnvironment.remove(id);
                    throw e;
                }

                ((Bot) bot).setDeploymentStatus(Deployment.Status.READY);
                botEnvironment.put(id, bot);
            }
        }
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

    private class BotId {
        private String id;
        private Integer version;

        private BotId(String id, Integer version) {
            this.id = id;
            this.version = version;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Integer getVersion() {
            return version;
        }

        public void setVersion(Integer version) {
            this.version = version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            BotId botId = (BotId) o;

            return id.equals(botId.id) && version.equals(botId.version);

        }

        @Override
        public int hashCode() {
            int result = (id != null ? id.hashCode() : 0);
            result = 31 * result + (version != null ? version.hashCode() : 0);
            return result;
        }
    }
}
