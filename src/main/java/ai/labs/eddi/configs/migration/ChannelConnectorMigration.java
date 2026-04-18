package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.ChannelConnector;
import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.migration.model.MigrationLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;

import static ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus.deployed;

/**
 * One-shot startup migration: converts legacy {@link ChannelConnector} entries
 * embedded in {@link AgentConfiguration#getChannels()} into standalone
 * {@link ChannelIntegrationConfiguration} documents.
 * <p>
 * Follows the same flag-based pattern as {@link V6RenameMigration}: checks a
 * {@code MigrationLogStore} flag on startup and runs only once.
 * <p>
 * Since Slack channel support was introduced as a preview feature with very few
 * users, this migration is intentionally simple. It creates one
 * {@code ChannelIntegrationConfiguration} per unique {@code channelId}, merging
 * multiple agents targeting the same channel into a multi-target config.
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class ChannelConnectorMigration {

    private static final Logger LOGGER = Logger.getLogger(ChannelConnectorMigration.class);
    private static final String MIGRATION_KEY = "channel-connector-migration-complete";

    private final IDeploymentStore deploymentStore;
    private final IAgentStore agentStore;
    private final IChannelIntegrationStore channelStore;
    private final MigrationLogStore migrationLogStore;

    @Inject
    public ChannelConnectorMigration(IDeploymentStore deploymentStore,
            IAgentStore agentStore,
            IChannelIntegrationStore channelStore,
            MigrationLogStore migrationLogStore) {
        this.deploymentStore = deploymentStore;
        this.agentStore = agentStore;
        this.channelStore = channelStore;
        this.migrationLogStore = migrationLogStore;
    }

    /**
     * Run the channel connector migration if not already applied. Called from
     * {@code AgentDeploymentManagement.autoDeployAgents()}.
     */
    public void runIfNeeded() {
        if (migrationLogStore.readMigrationLog(MIGRATION_KEY) != null) {
            LOGGER.debug("Channel connector migration already applied — skipping");
            return;
        }

        LOGGER.info("Starting channel connector migration...");

        try {
            int migrated = migrateConnectors();
            LOGGER.infof("Channel connector migration complete: %d configs created", migrated);
        } catch (Exception e) {
            LOGGER.error("Channel connector migration failed — will retry on next startup", e);
            return; // Don't set flag so it retries
        }

        migrationLogStore.createMigrationLog(new MigrationLog(MIGRATION_KEY));
    }

    private int migrateConnectors() {
        // Group connectors by channelType:channelId
        var channelGroups = new LinkedHashMap<String, List<ConnectorEntry>>();

        try {
            var statuses = deploymentStore.readDeploymentInfos(deployed);
            for (var status : statuses) {
                if (status.getAgentId() == null || status.getAgentVersion() == null) {
                    continue;
                }
                String agentId = status.getAgentId();
                try {
                    var agentConfig = agentStore.read(agentId, status.getAgentVersion());
                    if (agentConfig == null || agentConfig.getChannels() == null) {
                        continue;
                    }
                    for (var connector : agentConfig.getChannels()) {
                        if (connector.getType() == null || connector.getConfig() == null) {
                            continue;
                        }
                        String channelId = connector.getConfig().get("channelId");
                        if (channelId == null || channelId.isBlank()) {
                            continue;
                        }
                        String channelType = connector.getType().toString().toLowerCase();
                        String groupKey = channelType + ":" + channelId;
                        channelGroups.computeIfAbsent(groupKey, k -> new ArrayList<>())
                                .add(new ConnectorEntry(connector, agentId, channelType));
                    }
                } catch (Exception e) {
                    LOGGER.warnf("Skipping agent %s during channel migration: %s", agentId, e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to read deployment infos for channel migration", e);
            throw new RuntimeException("Cannot read deployment infos", e);
        }

        int created = 0;
        for (var entry : channelGroups.entrySet()) {
            var entries = entry.getValue();
            // Sort for deterministic default target
            entries.sort(Comparator.comparing(ConnectorEntry::agentId));

            var first = entries.get(0);
            String channelId = first.connector().getConfig().get("channelId");
            String channelType = first.channelType();

            var config = new ChannelIntegrationConfiguration();
            config.setName(channelType + " — " + channelId);
            config.setChannelType(channelType);
            config.setPlatformConfig(new HashMap<>(first.connector().getConfig()));

            var targets = new ArrayList<ChannelTarget>();
            var usedNames = new HashSet<String>();

            for (var ce : entries) {
                var target = new ChannelTarget();
                String baseName = ce.agentId().toLowerCase().replaceAll("[^a-z0-9-]", "-");
                String targetName = baseName;
                if (!usedNames.add(targetName)) {
                    int counter = 2;
                    while (!usedNames.add(targetName)) {
                        targetName = baseName + "-" + counter++;
                    }
                }
                target.setName(targetName);
                target.setType(ChannelTarget.TargetType.AGENT);
                target.setTargetId(ce.agentId());

                String groupId = ce.connector().getConfig().get("groupId");
                if (groupId != null && !groupId.isBlank()) {
                    target.setType(ChannelTarget.TargetType.GROUP);
                    target.setTargetId(groupId);
                }

                target.setTriggers(List.of(targetName));
                targets.add(target);
            }

            config.setTargets(targets);
            config.setDefaultTargetName(targets.get(0).getName());

            try {
                channelStore.create(config);
                created++;
                LOGGER.infof("  Migrated channel %s:%s (%d targets)", channelType, channelId, targets.size());
            } catch (Exception e) {
                LOGGER.warnf("  Failed to create config for %s:%s — %s", channelType, channelId, e.getMessage());
            }
        }

        return created;
    }

    private record ConnectorEntry(ChannelConnector connector, String agentId, String channelType) {
    }
}
