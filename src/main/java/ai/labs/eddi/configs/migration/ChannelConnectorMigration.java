package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.ChannelConnector;
import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.model.MigrationLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.Locale;

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

    /**
     * Trigger keywords reserved by the router — migration must not generate these.
     */
    private static final Set<String> RESERVED_TRIGGERS = Set.of("help");

    private final IDeploymentStore deploymentStore;
    private final IAgentStore agentStore;
    private final IChannelIntegrationStore channelStore;
    private final IDocumentDescriptorStore descriptorStore;
    private final MigrationLogStore migrationLogStore;

    @Inject
    public ChannelConnectorMigration(IDeploymentStore deploymentStore,
            IAgentStore agentStore,
            IChannelIntegrationStore channelStore,
            IDocumentDescriptorStore descriptorStore,
            MigrationLogStore migrationLogStore) {
        this.deploymentStore = deploymentStore;
        this.agentStore = agentStore;
        this.channelStore = channelStore;
        this.descriptorStore = descriptorStore;
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
                        String channelType = connector.getType().toString().toLowerCase(Locale.ROOT);
                        String groupKey = channelType + ":" + channelId;
                        channelGroups.computeIfAbsent(groupKey, k -> new ArrayList<>())
                                .add(new ConnectorEntry(connector, agentId, channelType,
                                        lookupAgentName(agentId, status.getAgentVersion())));
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

            // Warn on credential divergence across agents sharing the same channel
            warnOnCredentialDivergence(entry.getKey(), entries);

            var first = entries.get(0);
            String channelId = first.connector().getConfig().get("channelId");
            String channelType = first.channelType();

            var config = new ChannelIntegrationConfiguration();
            config.setName(channelType + " — " + channelId);
            config.setChannelType(channelType);
            // Clean platformConfig: only carry channel-level credentials
            // (channelId, botToken, signingSecret), not per-connector fields like groupId
            var cleanedPlatformConfig = new HashMap<String, String>();
            var rawConfig = first.connector().getConfig();
            for (String credKey : List.of("channelId", "botToken", "signingSecret")) {
                if (rawConfig.containsKey(credKey)) {
                    cleanedPlatformConfig.put(credKey, rawConfig.get(credKey));
                }
            }
            config.setPlatformConfig(cleanedPlatformConfig);

            var targets = new ArrayList<ChannelTarget>();
            var usedNames = new HashSet<String>();

            for (var ce : entries) {
                var target = new ChannelTarget();
                String baseName = slugify(ce.agentName() != null ? ce.agentName() : ce.agentId());
                String targetName = baseName;
                int counter = 2;
                while (!usedNames.add(targetName)) {
                    targetName = baseName + "-" + counter++;
                }
                target.setName(targetName);
                target.setType(ChannelTarget.TargetType.AGENT);
                target.setTargetId(ce.agentId());

                String groupId = ce.connector().getConfig().get("groupId");
                if (groupId != null && !groupId.isBlank()) {
                    target.setType(ChannelTarget.TargetType.GROUP);
                    target.setTargetId(groupId);
                }

                // Only assign trigger if it's not a reserved keyword
                if (!RESERVED_TRIGGERS.contains(targetName)) {
                    target.setTriggers(List.of(targetName));
                } else {
                    LOGGER.warnf("  Skipping reserved trigger '%s' for target in channel %s:%s",
                            targetName, channelType, channelId);
                    target.setTriggers(List.of());
                }
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

    private record ConnectorEntry(ChannelConnector connector, String agentId,
            String channelType, String agentName) {
    }

    /**
     * Look up the human-readable name for an agent via its document descriptor.
     * Returns {@code null} if the descriptor is not found or has no name.
     */
    private String lookupAgentName(String agentId, Integer version) {
        try {
            var descriptor = descriptorStore.readDescriptor(agentId, version);
            if (descriptor != null && descriptor.getName() != null && !descriptor.getName().isBlank()) {
                return descriptor.getName();
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not read descriptor for agent %s: %s", agentId, e.getMessage());
        }
        return null;
    }

    /**
     * Slugify a name for use as a target name / trigger keyword.
     */
    private static String slugify(String input) {
        String slug = input.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        // Fallback: if input was all special chars (emoji, etc.), use a prefix
        return slug.isEmpty() ? "target" : slug;
    }

    /**
     * Log WARN if agents sharing the same channelId have divergent credentials, so
     * operators can reconcile manually after migration.
     */
    private void warnOnCredentialDivergence(String groupKey, List<ConnectorEntry> entries) {
        if (entries.size() < 2)
            return;
        String refBotToken = entries.get(0).connector().getConfig().get("botToken");
        String refSigning = entries.get(0).connector().getConfig().get("signingSecret");

        var divergentAgents = new ArrayList<String>();
        for (int i = 1; i < entries.size(); i++) {
            var cfg = entries.get(i).connector().getConfig();
            if (!Objects.equals(refBotToken, cfg.get("botToken"))
                    || !Objects.equals(refSigning, cfg.get("signingSecret"))) {
                divergentAgents.add(entries.get(i).agentId());
            }
        }
        if (!divergentAgents.isEmpty()) {
            LOGGER.warnf("  CREDENTIAL DIVERGENCE for %s — agents %s have different "
                    + "botToken/signingSecret than %s. Only the first agent's credentials "
                    + "will be used. Please reconcile manually after migration.",
                    groupKey, divergentAgents, entries.get(0).agentId());
        }
    }
}
