/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.connector;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every {@link SourceConnector} bean, by {@link SourceConnector#type()}.
 *
 * <p>
 * Discovery is CDI: a connector is found because it is a bean, so adding a
 * source type edits no registry. Two connectors claiming one type fail at
 * startup rather than letting bean order decide which one runs.
 */
@ApplicationScoped
public class SourceConnectors {

    private final Map<String, SourceConnector> byType;

    @Inject
    public SourceConnectors(Instance<SourceConnector> connectors) {
        this(collect(connectors));
    }

    private SourceConnectors(List<SourceConnector> connectors) {
        Map<String, SourceConnector> map = new TreeMap<>();
        for (SourceConnector connector : connectors) {
            SourceConnector previous = map.putIfAbsent(connector.type(), connector);
            if (previous != null) {
                throw new IllegalStateException("Two source connectors claim type '" + connector.type() + "': "
                        + previous.getClass().getName() + " and " + connector.getClass().getName());
            }
        }
        this.byType = Collections.unmodifiableMap(map);
    }

    /** A fixed set, for callers that assemble the pipeline by hand. */
    public static SourceConnectors of(SourceConnector... connectors) {
        return new SourceConnectors(List.of(connectors));
    }

    private static List<SourceConnector> collect(Instance<SourceConnector> connectors) {
        List<SourceConnector> list = new ArrayList<>();
        connectors.forEach(list::add);
        return list;
    }

    /**
     * @throws IllegalArgumentException
     *             when no connector serves the type — a source that passed
     *             validation should never get here
     */
    public SourceConnector forType(String type) {
        SourceConnector connector = type == null ? null : byType.get(type);
        if (connector == null) {
            throw new IllegalArgumentException(
                    "No source connector for type '" + type + "'. Available: " + byType.keySet());
        }
        return connector;
    }

    /** The registered types, sorted. */
    public List<String> types() {
        return List.copyOf(byType.keySet());
    }
}
