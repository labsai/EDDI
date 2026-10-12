/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.descriptors;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.descriptors.model.ResourceDescriptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The descriptor types that are <em>configuration</em> — the only ones the
 * generic descriptor and sharing APIs ({@code /descriptorstore/descriptors/**})
 * may address.
 *
 * <h3>Why this exists</h3> Configuration descriptors share the
 * {@code descriptors} collection with conversation descriptors, and the generic
 * API used to take any id or type it was given. A conversation descriptor read
 * through it, renamed, and written back lost every field the configuration
 * shape does not model — the conversation's {@code userId} among them — after
 * which the ownership check admitted every caller. So the generic API answers
 * only for configuration types, and an id of anything else is "not found".
 *
 * <h3>Derived, not listed</h3> The set is read from the configuration stores
 * themselves: every store's REST interface extends {@link IRestVersionInfo} and
 * names its resource URI, whose authority is the descriptor type. A new
 * configuration store is therefore covered the moment it exists, and a
 * non-configuration resource (conversations, user conversations, triggers,
 * schedules) can never be added by forgetting to exclude it. Resolved on first
 * use rather than at construction, so this bean adds no edge to the
 * construction graph of the stores it reads.
 */
@ApplicationScoped
public class ConfigResourceTypes {

    private static final Logger LOGGER = Logger.getLogger(ConfigResourceTypes.class);

    private static final String EDDI_SCHEME = "eddi";

    private final Supplier<Resolution> source;
    private volatile Set<String> types;

    @Inject
    public ConfigResourceTypes(@Any Instance<IRestVersionInfo> configStores) {
        this.source = () -> collect(configStores.stream().toList());
    }

    private ConfigResourceTypes(Set<String> fixed) {
        this.source = () -> new Resolution(fixed, true);
    }

    /** A fixed set — for tests and for callers outside a CDI container. */
    public static ConfigResourceTypes of(String... types) {
        return new ConfigResourceTypes(Set.copyOf(Arrays.asList(types)));
    }

    /** The configuration descriptor types, e.g. {@code ai.labs.agent}. */
    public Set<String> types() {
        Set<String> resolved = types;
        if (resolved == null) {
            Resolution resolution = source.get();
            resolved = Set.copyOf(resolution.types());
            if (resolution.complete() && !resolved.isEmpty()) {
                // An empty or partial answer is never cached: it fails closed (a type
                // that could not be read is not configuration), and must not stay that
                // way for the life of the process if the failure was transient.
                types = resolved;
            }
        }
        return resolved;
    }

    /**
     * Whether {@code type} (a descriptor type such as {@code ai.labs.agent}) is
     * configuration.
     */
    public boolean isConfigType(String type) {
        return type != null && types().contains(type);
    }

    /**
     * Whether the descriptor describes a configuration resource. A descriptor
     * without a resource URI, or with one that is not an {@code eddi://} URI, is
     * not — the generic API has no business with it.
     */
    public boolean isConfigDescriptor(ResourceDescriptor descriptor) {
        return descriptor != null && isConfigType(typeOf(descriptor.getResource()));
    }

    /**
     * The descriptor type of an {@code eddi://} resource URI — its authority — or
     * {@code null} for anything else.
     */
    public static String typeOf(URI resource) {
        if (resource == null || !EDDI_SCHEME.equals(resource.getScheme())) {
            return null;
        }
        return resource.getAuthority();
    }

    /** The types read, and whether every store answered. */
    private record Resolution(Set<String> types, boolean complete) {
    }

    private static Resolution collect(Collection<? extends IRestVersionInfo> stores) {
        Set<String> collected = new TreeSet<>();
        boolean complete = true;
        for (IRestVersionInfo store : stores) {
            try {
                String type = typeOf(URI.create(store.getResourceURI()));
                if (type != null) {
                    collected.add(type);
                }
            } catch (RuntimeException e) {
                complete = false;
                LOGGER.warnf("Could not read the resource type of configuration store %s: %s", store.getClass().getName(), e.getMessage());
            }
        }
        LOGGER.debugf("Configuration descriptor types: %s", collected);
        return new Resolution(collected, complete);
    }
}
