/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.ChannelConnector;
import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.IRestChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.ResourceUtilities;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.descriptors.model.ResourceVisibility;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.engine.security.spaces.DescriptorAccess;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.configs.IRestVersionInfo.versionQueryParam;
import static ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus.deployed;
import static ai.labs.eddi.utils.RestUtilities.createURI;
import static ai.labs.eddi.utils.RestUtilities.extractResourceId;

import java.util.*;
import java.util.Locale;

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
 * <h3>Every created channel gets its descriptor</h3> The router, the REST
 * listings and this migration's own duplicate check all find channel
 * configurations by listing {@code ai.labs.channel} descriptors, and nothing
 * writes one for an in-process create ({@code DocumentDescriptorFilter} runs
 * only for HTTP responses). The first version of this migration wrote none: its
 * channels were invisible, the router kept routing through the legacy
 * connectors, and a re-run after a partial failure created every channel again.
 * The completion flag was therefore renamed, so a deployment that finished that
 * first run migrates once more and gets visible configurations. The documents
 * the first run created stay where they are, unreferenced: no API lists or
 * reads them, and removing them would need a scan of the raw collection for
 * nothing a user can reach.
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class ChannelConnectorMigration {

    private static final Logger LOGGER = Logger.getLogger(ChannelConnectorMigration.class);
    /**
     * {@code -v2}: the first run, recorded as
     * {@code channel-connector-migration-complete}, wrote no descriptors.
     */
    private static final String MIGRATION_KEY = "channel-connector-migration-v2-complete";

    /**
     * Trigger keywords reserved by the router — migration must not generate these.
     */
    private static final Set<String> RESERVED_TRIGGERS = Set.of("help");

    private final IDeploymentStore deploymentStore;
    private final IAgentStore agentStore;
    private final IChannelIntegrationStore channelStore;
    private final IDocumentDescriptorStore descriptorStore;
    private final IMigrationLogStore migrationLogStore;

    @Inject
    public ChannelConnectorMigration(IDeploymentStore deploymentStore,
            IAgentStore agentStore,
            IChannelIntegrationStore channelStore,
            IDocumentDescriptorStore descriptorStore,
            IMigrationLogStore migrationLogStore) {
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
            int[] result = migrateConnectors(); // [created, failed, skipped]
            int created = result[0];
            int failed = result[1];
            int skipped = result[2];
            LOGGER.infof("Channel connector migration complete: %d created, %d failed, %d skipped (already existed)",
                    created, failed, skipped);

            if (failed > 0) {
                LOGGER.errorf("Channel connector migration had %d failure(s) — "
                        + "will retry on next startup. Check WARN logs above for details.", failed);
                return; // Don't set flag so it retries
            }
        } catch (Exception e) {
            LOGGER.error("Channel connector migration failed — will retry on next startup", e);
            return; // Don't set flag so it retries
        }

        migrationLogStore.createMigrationLog(new MigrationLog(MIGRATION_KEY));
    }

    private int[] migrateConnectors() {
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
                    DocumentDescriptor agentDescriptor = readAgentDescriptor(agentId, status.getAgentVersion());
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
                                .add(new ConnectorEntry(connector, agentId, channelType, agentDescriptor));
                    }
                } catch (Exception e) {
                    LOGGER.warnf("Skipping agent %s during channel migration: %s", agentId, e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to read deployment infos for channel migration", e);
            throw new RuntimeException("Cannot read deployment infos", e);
        }

        // Pre-load existing channel configs to avoid creating duplicates on retry.
        // channelStore.create() generates a new UUID every time, so re-running
        // after partial failure would accumulate duplicate configs without this check.
        var existingKeys = loadExistingChannelKeys();

        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (var entry : channelGroups.entrySet()) {
            // Skip if a config for this channelType:channelId already exists
            if (existingKeys.contains(entry.getKey())) {
                skipped++;
                LOGGER.debugf("  Skipping %s — config already exists", entry.getKey());
                continue;
            }
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

            IResourceStore.IResourceId createdId;
            try {
                createdId = channelStore.create(config);
            } catch (Exception e) {
                failed++;
                LOGGER.warnf("  Failed to create config for %s:%s — %s", channelType, channelId, e.getMessage());
                continue;
            }
            try {
                descriptorStore.createDescriptor(createdId.getId(), createdId.getVersion(),
                        channelDescriptor(createdId, config, first.agentDescriptor()));
                created++;
                LOGGER.infof("  Migrated channel %s:%s (%d targets)", channelType, channelId, targets.size());
            } catch (Exception e) {
                // Without its descriptor the config is invisible to the router and to the
                // duplicate check, so the next run would create it again: undo it instead.
                failed++;
                LOGGER.warnf("  Failed to write the descriptor for %s:%s — %s", channelType, channelId, e.getMessage());
                removeCreatedChannel(createdId);
            }
        }

        return new int[]{created, failed, skipped};
    }

    private record ConnectorEntry(ChannelConnector connector, String agentId,
            String channelType, DocumentDescriptor agentDescriptor) {

        /** The agent's display name, or {@code null} when it has none. */
        String agentName() {
            if (agentDescriptor == null || agentDescriptor.getName() == null || agentDescriptor.getName().isBlank()) {
                return null;
            }
            return agentDescriptor.getName();
        }
    }

    /**
     * The descriptor of a migrated channel: named like the configuration, and owned
     * and filed like the agent it routes to by default, so whoever could see that
     * agent sees its channel.
     * <p>
     * The agent's visibility is copied only when it is {@code private}. Any wider
     * one becomes {@code space}, the default for a new resource: a channel holds
     * the bot's credentials, and an agent published or opened to the organisation
     * <em>to talk to</em> does not make its channel configuration something
     * everyone should read. Grants are not copied, for the same reason. An agent
     * without a descriptor, or without an owner, leaves the channel unowned, as
     * legacy data — which is what that agent is too.
     */
    private static DocumentDescriptor channelDescriptor(IResourceStore.IResourceId createdId,
                                                        ChannelIntegrationConfiguration config, DocumentDescriptor agentDescriptor) {
        var descriptor = ResourceUtilities.createDocumentDescriptor(createURI(
                IRestChannelIntegrationStore.resourceURI, createdId.getId(), versionQueryParam, createdId.getVersion()));
        descriptor.setName(config.getName());
        if (agentDescriptor != null) {
            descriptor.setOwnerId(agentDescriptor.getOwnerId());
            descriptor.setSpaceId(agentDescriptor.getSpaceId());
            descriptor.setVisibility(agentDescriptor.resourceVisibility() == ResourceVisibility.privateAccess
                    ? ResourceVisibility.privateAccess.wireName()
                    : ResourceVisibility.space.wireName());
        }
        return DescriptorAccess.rebuildIndex(descriptor);
    }

    private void removeCreatedChannel(IResourceStore.IResourceId createdId) {
        try {
            channelStore.deleteAllPermanently(createdId.getId());
        } catch (Exception e) {
            LOGGER.errorf(e, "  Channel config %s has no descriptor and could not be removed again; it is unreachable "
                    + "through every API and can be deleted from the channel collection by hand.", createdId.getId());
        }
    }

    /**
     * Load all existing channel integration configs and return their
     * {@code channelType:channelId} keys. Used to detect duplicates on retry.
     * <p>
     * A listing that fails aborts the run, which then retries on the next start:
     * carrying on without the keys would create a second config for every channel
     * that already has one.
     */
    private Set<String> loadExistingChannelKeys() {
        var keys = new HashSet<String>();
        try {
            var descriptors = descriptorStore.readDescriptors(
                    "ai.labs.channel", "", 0, IDescriptorStore.NO_LIMIT, false);
            for (var descriptor : descriptors) {
                try {
                    var resId = extractResourceId(
                            descriptor.getResource());
                    if (resId == null || resId.getId() == null)
                        continue;
                    var config = channelStore.read(resId.getId(), resId.getVersion());
                    if (config != null && config.getChannelType() != null
                            && config.getPlatformConfig() != null) {
                        String chId = config.getPlatformConfig().get("channelId");
                        if (chId != null && !chId.isBlank()) {
                            keys.add(config.getChannelType().toLowerCase(Locale.ROOT)
                                    + ":" + chId);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debugf("Skipping descriptor during duplicate check: %s",
                            e.getMessage());
                }
            }
        } catch (Exception e) {
            // Only the listing itself lands here; one unreadable config is skipped above.
            throw new IllegalStateException("Cannot list the existing channel configs, so duplicates cannot be ruled out", e);
        }
        return keys;
    }

    /**
     * Read an agent's document descriptor, for its name and its ownership. Returns
     * {@code null} if the descriptor is not found or cannot be read.
     */
    private DocumentDescriptor readAgentDescriptor(String agentId, Integer version) {
        try {
            return descriptorStore.readDescriptor(agentId, version);
        } catch (Exception e) {
            LOGGER.debugf("Could not read descriptor for agent %s: %s", agentId, e.getMessage());
            return null;
        }
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
