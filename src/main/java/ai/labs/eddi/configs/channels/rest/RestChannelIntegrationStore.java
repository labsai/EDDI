package ai.labs.eddi.configs.channels.rest;

import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.IRestChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * REST implementation for channel integration configuration CRUD. Includes
 * validation for trigger uniqueness, default target, and channel type.
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class RestChannelIntegrationStore implements IRestChannelIntegrationStore {
    private static final Logger LOG = Logger.getLogger(RestChannelIntegrationStore.class);

    /**
     * Currently registered channel type adapters. Future: make this discoverable
     * via CDI so forks can register custom adapters.
     */
    private static final Set<String> REGISTERED_CHANNEL_TYPES = Set.of("slack");

    private final IChannelIntegrationStore channelStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final RestVersionInfo<ChannelIntegrationConfiguration> restVersionInfo;

    @Inject
    public RestChannelIntegrationStore(IChannelIntegrationStore channelStore,
            IDocumentDescriptorStore documentDescriptorStore) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, channelStore, documentDescriptorStore);
        this.channelStore = channelStore;
        this.documentDescriptorStore = documentDescriptorStore;
    }

    @Override
    public List<DocumentDescriptor> readChannelDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.channel", filter, index, limit);
    }

    @Override
    public ChannelIntegrationConfiguration readChannel(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateChannel(String id, Integer version,
                                  ChannelIntegrationConfiguration channelConfiguration) {
        validateConfiguration(channelConfiguration);
        Response response = restVersionInfo.update(id, version, channelConfiguration);
        syncDescriptor(id, channelConfiguration);
        return response;
    }

    @Override
    public Response createChannel(ChannelIntegrationConfiguration channelConfiguration) {
        validateConfiguration(channelConfiguration);
        Response response = restVersionInfo.create(channelConfiguration);
        URI location = response.getLocation();
        if (location != null) {
            try {
                var resourceId = RestUtilities.extractResourceId(location);
                syncDescriptor(resourceId.getId(), channelConfiguration);
            } catch (Exception e) {
                LOG.warn("Failed to sync channel descriptor on create", e);
            }
        }
        return response;
    }

    @Override
    public Response duplicateChannel(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        ChannelIntegrationConfiguration config = restVersionInfo.read(id, version);
        Response response = restVersionInfo.create(config);
        URI location = response.getLocation();
        if (location != null) {
            try {
                var resourceId = RestUtilities.extractResourceId(location);
                syncDescriptor(resourceId.getId(), config);
            } catch (Exception e) {
                LOG.warn("Failed to sync channel descriptor on duplicate", e);
            }
        }
        return response;
    }

    @Override
    public Response deleteChannel(String id, Integer version, Boolean permanent) {
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id)
            throws IResourceStore.ResourceNotFoundException {
        return channelStore.getCurrentResourceId(id);
    }

    // ─── Validation ────────────────────────────────────────────────────────────

    private void validateConfiguration(ChannelIntegrationConfiguration config) {
        if (config.getName() == null || config.getName().isBlank()) {
            throw new BadRequestException("Channel integration name is required.");
        }

        // Channel type must be a registered adapter
        String channelType = config.getChannelType();
        if (channelType == null || channelType.isBlank()) {
            throw new BadRequestException("Channel type is required.");
        }
        if (!REGISTERED_CHANNEL_TYPES.contains(channelType.toLowerCase())) {
            throw new BadRequestException(
                    "Unknown channel type: '" + channelType + "'. Registered types: "
                            + REGISTERED_CHANNEL_TYPES);
        }

        // At least one target
        List<ChannelTarget> targets = config.getTargets();
        if (targets == null || targets.isEmpty()) {
            throw new BadRequestException("At least one target is required.");
        }

        // Default target must reference an existing target
        String defaultName = config.getDefaultTargetName();
        if (defaultName == null || defaultName.isBlank()) {
            throw new BadRequestException("Default target name is required.");
        }
        boolean defaultFound = targets.stream()
                .anyMatch(t -> t.getName() != null
                        && t.getName().equalsIgnoreCase(defaultName));
        if (!defaultFound) {
            throw new BadRequestException(
                    "Default target '" + defaultName
                            + "' does not match any target name.");
        }

        // No duplicate trigger keywords across targets
        Set<String> allTriggers = new HashSet<>();
        for (ChannelTarget target : targets) {
            if (target.getName() == null || target.getName().isBlank()) {
                throw new BadRequestException("Every target must have a name.");
            }
            if (target.getTargetId() == null || target.getTargetId().isBlank()) {
                throw new BadRequestException(
                        "Target '" + target.getName() + "' must have a targetId.");
            }
            // Observe mode is schema-ready but not yet implemented
            if (target.isObserveMode()) {
                throw new BadRequestException(
                        "Target '" + target.getName()
                                + "': observeMode is not yet implemented. "
                                + "Set observeMode to false or omit it.");
            }
            if (target.getTriggers() != null) {
                for (String trigger : target.getTriggers()) {
                    if (trigger == null || trigger.isBlank()) {
                        throw new BadRequestException(
                                "Target '" + target.getName()
                                        + "' contains a null or blank trigger keyword.");
                    }
                    String normalized = trigger.toLowerCase().trim();
                    if (!allTriggers.add(normalized)) {
                        throw new BadRequestException(
                                "Duplicate trigger keyword: '" + trigger
                                        + "'. Each trigger must be unique across all targets.");
                    }
                }
            }
        }
    }

    // ─── Descriptor sync ───────────────────────────────────────────────────────

    /**
     * Sync the channel config's name onto the DocumentDescriptor so that the
     * descriptors endpoint returns meaningful display information.
     */
    private void syncDescriptor(String resourceId,
                                ChannelIntegrationConfiguration config) {
        try {
            var currentResourceId = channelStore.getCurrentResourceId(resourceId);
            var descriptor = documentDescriptorStore.readDescriptor(
                    resourceId, currentResourceId.getVersion());
            boolean changed = false;

            if (config.getName() != null
                    && !config.getName().equals(descriptor.getName())) {
                descriptor.setName(config.getName());
                changed = true;
            }

            // Use channelType as description for quick identification in lists
            String desc = config.getChannelType() != null
                    ? config.getChannelType() + " integration"
                    : null;
            if (desc != null && !desc.equals(descriptor.getDescription())) {
                descriptor.setDescription(desc);
                changed = true;
            }

            if (changed) {
                documentDescriptorStore.setDescriptor(
                        resourceId, currentResourceId.getVersion(), descriptor);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to sync channel descriptor for id=%s",
                    sanitizeForLog(resourceId));
        }
    }

    private static String sanitizeForLog(String value) {
        if (value == null)
            return null;
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t' || c < 0x20 || c == 0x7F) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
