package ai.labs.eddi.engine.runtime.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Micrometer metrics for NATS JetStream operations.
 *
 * <p>Metrics exposed at {@code /q/metrics}:</p>
 * <ul>
 *   <li>{@code eddi_nats_publish_count} — messages published to NATS</li>
 *   <li>{@code eddi_nats_publish_duration} — publish latency</li>
 *   <li>{@code eddi_nats_consume_count} — messages consumed from NATS</li>
 *   <li>{@code eddi_nats_consume_duration} — consume/processing latency</li>
 *   <li>{@code eddi_nats_dead_letter_count} — messages sent to dead-letter</li>
 * </ul>
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
@LookupIfProperty(name = "eddi.messaging.type", stringValue = "nats")
public class NatsMetrics {

    private final MeterRegistry meterRegistry;

    private Counter publishCount;
    private Timer publishDuration;
    private Counter consumeCount;
    private Timer consumeDuration;
    private Counter deadLetterCount;

    @Inject
    public NatsMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void init() {
        publishCount = meterRegistry.counter("eddi_nats_publish_count");
        publishDuration = meterRegistry.timer("eddi_nats_publish_duration");
        consumeCount = meterRegistry.counter("eddi_nats_consume_count");
        consumeDuration = meterRegistry.timer("eddi_nats_consume_duration");
        deadLetterCount = meterRegistry.counter("eddi_nats_dead_letter_count");
    }

    public Counter getPublishCount() {
        return publishCount;
    }

    public Timer getPublishDuration() {
        return publishDuration;
    }

    public Counter getConsumeCount() {
        return consumeCount;
    }

    public Timer getConsumeDuration() {
        return consumeDuration;
    }

    public Counter getDeadLetterCount() {
        return deadLetterCount;
    }
}
