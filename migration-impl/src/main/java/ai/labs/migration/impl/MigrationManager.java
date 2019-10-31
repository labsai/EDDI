package ai.labs.migration.impl;

import ai.labs.channels.config.IChannelDefinitionStore;
import ai.labs.channels.config.model.ChannelDefinition;
import ai.labs.migration.IMigrationManager;
import ai.labs.models.DocumentDescriptor;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.config.bots.IRestBotStore;
import ai.labs.resources.rest.migration.IMigrationLogStore;
import ai.labs.resources.rest.migration.model.MigrationLog;
import ai.labs.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.List;

@Singleton
@Slf4j
public class MigrationManager implements IMigrationManager {
    private static final String MIGRATION_CHANNELS = "channels";
    private final IRestBotStore restBotStore;
    private final IChannelDefinitionStore channelDefinitionStore;
    private final IMigrationLogStore migrationCheck;

    @Inject
    public MigrationManager(IRestBotStore restBotStore,
                            IChannelDefinitionStore channelDefinitionStore,
                            IMigrationLogStore migrationCheck) {
        this.restBotStore = restBotStore;
        this.channelDefinitionStore = channelDefinitionStore;
        this.migrationCheck = migrationCheck;
    }

    @Override
    public void checkForMigration() {
        try {
            var migrationLog = migrationCheck.readMigrationLog(MIGRATION_CHANNELS);
            if (migrationLog == null || !migrationLog.isFinished()) {
                log.info("Migrating ChannelDefinitions from within BotConfigurations to stand-alone...");
                migrateChannels();
                migrationCheck.createMigrationLog(new MigrationLog(MIGRATION_CHANNELS));
                log.info("Migration of ChannelDefinitions has finished successfully.");
            }
        } catch (MigrationException e) {
            log.error("Error while migrating channels.");
            log.error(e.getLocalizedMessage(), e);
        }
    }

    private void migrateChannels() throws MigrationException {
        List<DocumentDescriptor> botDescriptors;
        var i = 0;
        try {
            do {
                botDescriptors = restBotStore.readBotDescriptors("", i, 20);
                for (var botDescriptor : botDescriptors) {
                    var resourceId = RestUtilities.extractResourceId(botDescriptor.getResource());
                    var botConfiguration = restBotStore.readBot(resourceId.getId(), resourceId.getVersion());
                    var channelDefinition = new ChannelDefinition();
                    var channels = botConfiguration.getChannels();
                    for (var channel : channels) {
                        String type = channel.getType().toString();
                        String channelName = botDescriptor.getName().toLowerCase().replace(" ", "-").
                                concat("-").concat(type.substring(type.lastIndexOf(".") + 1));
                        channelDefinition.setName(channelName);
                        channelDefinition.setType(channel.getType());
                        channelDefinition.setActive(true);
                        channelDefinition.setConfig(new HashMap<>(channel.getConfig()));
                        channelDefinitionStore.createChannelDefinition(channelDefinition);
                    }
                }

                i++;
            } while (!botDescriptors.isEmpty());
        } catch (IResourceStore.ResourceAlreadyExistsException | IResourceStore.ResourceStoreException e) {
            throw new MigrationException(e.getLocalizedMessage(), e);
        }
    }
}
