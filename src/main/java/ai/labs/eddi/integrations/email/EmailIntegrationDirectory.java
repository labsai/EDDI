/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The deployment's email channel integrations, read straight from the channel
 * store (the channel router keys integrations by a platform channel id, which a
 * mailbox does not have). {@link #all()} is cached for a short while, for the
 * loop guard's "is this another integration's address" question;
 * {@link #scan()} and {@link #find} always read the store.
 */
@ApplicationScoped
public class EmailIntegrationDirectory {

    private static final Logger LOGGER = Logger.getLogger(EmailIntegrationDirectory.class);

    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    /**
     * The channel store could not be read. {@link #scan()} lets it through rather
     * than skipping the integration: a partial list would let address uniqueness,
     * GDPR identity expansion and schedule ensuring silently miss one.
     */
    public static final class StoreUnavailableException extends IllegalStateException {
        public StoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** One email integration, at its current version. */
    public record EmailIntegration(String id, ChannelIntegrationConfiguration configuration, EmailChannelSettings settings) {
    }

    private final IChannelIntegrationStore channelStore;
    private final IDocumentDescriptorStore descriptorStore;

    private volatile List<EmailIntegration> cached = List.of();
    private volatile long cachedAt;

    @Inject
    public EmailIntegrationDirectory(IChannelIntegrationStore channelStore, IDocumentDescriptorStore descriptorStore) {
        this.channelStore = channelStore;
        this.descriptorStore = descriptorStore;
    }

    /**
     * The current version of integration {@code id}, if it exists and is an email
     * integration.
     */
    public Optional<EmailIntegration> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            IResourceStore.IResourceId current = channelStore.getCurrentResourceId(id);
            ChannelIntegrationConfiguration configuration = channelStore.read(id, current.getVersion());
            if (configuration == null || !EmailChannelSettings.CHANNEL_TYPE.equalsIgnoreCase(configuration.getChannelType())) {
                return Optional.empty();
            }
            return Optional.of(new EmailIntegration(id, configuration, EmailChannelSettings.from(configuration.getPlatformConfig())));
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Optional.empty();
        } catch (IResourceStore.ResourceStoreException e) {
            throw new StoreUnavailableException("The channel store could not be read", e);
        }
    }

    /**
     * Every email integration, read now. A descriptor that cannot be parsed is
     * skipped; a store failure throws, never a partial list.
     */
    public List<EmailIntegration> scan() {
        List<EmailIntegration> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            var descriptors = descriptorStore.readDescriptors("ai.labs.channel", "", 0, IDescriptorStore.NO_LIMIT, false);
            for (var descriptor : descriptors) {
                try {
                    var resourceId = RestUtilities.extractResourceId(descriptor.getResource());
                    if (resourceId == null || resourceId.getId() == null || !seen.add(resourceId.getId())) {
                        continue;
                    }
                    find(resourceId.getId()).ifPresent(found::add);
                } catch (StoreUnavailableException e) {
                    throw e;
                } catch (RuntimeException e) {
                    LOGGER.debugf("Skipping a channel descriptor while listing email integrations: %s", e.getMessage());
                }
            }
        } catch (StoreUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new StoreUnavailableException("The channel integrations could not be listed", e);
        }
        return found;
    }

    /**
     * {@link #scan()}, cached for {@link #CACHE_TTL}; the last good list when the
     * store fails.
     */
    public List<EmailIntegration> all() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_TTL.toMillis()) {
            return cached;
        }
        try {
            cached = List.copyOf(scan());
        } catch (RuntimeException e) {
            LOGGER.warnf("Listing email integrations failed, using the last known list: %s", e.getMessage());
        }
        cachedAt = now;
        return cached;
    }

    /**
     * The addresses of every email integration but {@code exceptId}, from the
     * cache.
     */
    public Set<String> otherAddresses(String exceptId) {
        Set<String> addresses = new HashSet<>();
        for (EmailIntegration integration : all()) {
            if (!integration.id().equals(exceptId) && integration.settings().address() != null) {
                addresses.add(integration.settings().address());
            }
        }
        return addresses;
    }

    /** Drops the cache (an integration was saved or deleted). */
    public void invalidate() {
        cachedAt = 0;
    }
}
