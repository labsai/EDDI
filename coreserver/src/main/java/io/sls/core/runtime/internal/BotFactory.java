package io.sls.core.runtime.internal;

import io.sls.core.runtime.*;
import io.sls.core.runtime.client.bots.BotStoreClientLibrary;
import io.sls.core.runtime.service.IBotStoreService;
import io.sls.core.runtime.service.ServiceException;
import io.sls.memory.IConversationMemory;
import io.sls.memory.model.Deployment;

import javax.inject.Inject;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * User: jarisch
 * Date: 09.06.12
 * Time: 17:46
 */
public class BotFactory implements IBotFactory {
    private final Map<BotId, IBot> bots = new ConcurrentHashMap<>();
    private final Map<Deployment.Environment, Map<BotId, IBot>> environments = new ConcurrentHashMap<>();
    private final BotStoreClientLibrary botStoreClientLibrary;

    @Inject
    public BotFactory(IBotStoreService botStoreService, IPackageFactory packageFactory) {
        environments.put(Deployment.Environment.restricted, new ConcurrentHashMap<>());
        environments.put(Deployment.Environment.unrestricted, new ConcurrentHashMap<>());
        environments.put(Deployment.Environment.test, new ConcurrentHashMap<>());
        botStoreClientLibrary = new BotStoreClientLibrary(botStoreService, packageFactory);
    }

    @Override
    public IBot getLatestBot(Deployment.Environment environment, String botId) throws ServiceException {
        List<BotId> botVersions = new LinkedList<>();

        Map<BotId, IBot> bots = getBotEnvironment(environment);
        botVersions.addAll(bots.keySet().stream().filter(id -> id.getId().equals(botId)).collect(Collectors.toList()));
        Collections.sort(botVersions, Collections.reverseOrder((botId1, botId2) -> botId1.getVersion() < botId2.getVersion() ? -1 : botId1.getVersion().equals(botId2.getVersion()) ? 0 : 1));

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
        Map<BotId, IBot> botEnvironment = getBotEnvironment(environment);
        if (!botEnvironment.containsKey(id)) {
            if(bots.containsKey(id)) {
                botEnvironment.put(id, bots.get(id));
            }else {
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
                botEnvironment.put(id, emptyBot);
                IBot bot;
                try {
                    bot = botStoreClientLibrary.getBot(botId, version);
                    bots.put(id, bot);
                } catch (ServiceException e) {
                    emptyBot.setDeploymentStatus(Deployment.Status.ERROR);
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

    private Map<BotId, IBot> getBotEnvironment(Deployment.Environment environment) {
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
