/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.prometheus.client.exemplars.DefaultExemplarSampler;
import io.prometheus.client.exemplars.ExemplarSampler;
import io.prometheus.client.exemplars.tracer.common.SpanContextSupplier;
import io.quarkus.opentelemetry.runtime.QuarkusContextStorage;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.interceptor.Interceptor;

import java.util.Optional;
import java.util.function.Function;

/**
 * The Prometheus exemplar sampler, minus the OpenTelemetry API misuse that put
 * {@code WARNING [io.ope.usage] OpenTelemetry API usage issue detected} into
 * every boot log.
 * <p>
 * Quarkus registers {@code OpentelemetryExemplarSamplerProvider} whenever
 * {@code quarkus-opentelemetry} is on the classpath. Its span supplier calls
 * {@code Span.fromContextOrNull(QuarkusContextStorage.INSTANCE.current())}, and
 * the storage answers {@code null} on a thread with no OpenTelemetry context —
 * which is every meter recorded at startup ({@code JVMInfoBinder}) and on the
 * Vert.x worker pool. OpenTelemetry reports a {@code null} context as an API
 * usage issue. The warning pointed at nothing EDDI does, and an operator who
 * enabled FINEST logging to follow its advice found a Quarkus stack trace.
 * <p>
 * This producer replaces it with the same sampler whose supplier treats "no
 * current context" as "no span", which is what the original means by it. With
 * tracing enabled ({@code quarkus.otel.sdk.disabled=false}) exemplars are
 * attached exactly as before.
 */
@ApplicationScoped
public class NullSafeExemplarSamplerProducer {

    @Produces
    @Alternative
    @Priority(Interceptor.Priority.APPLICATION)
    public Optional<ExemplarSampler> exemplarSampler() {
        return Optional.of(new DefaultExemplarSampler(new NullSafeSpanContextSupplier()));
    }

    /** The span context of the current OpenTelemetry context, if there is one. */
    static final class NullSafeSpanContextSupplier implements SpanContextSupplier {

        @Override
        public String getTraceId() {
            return get(SpanContext::getTraceId);
        }

        @Override
        public String getSpanId() {
            return get(SpanContext::getSpanId);
        }

        @Override
        public boolean isSampled() {
            return Boolean.TRUE.equals(get(SpanContext::isSampled));
        }

        private static <T> T get(Function<SpanContext, T> attribute) {
            return spanContextOf(QuarkusContextStorage.INSTANCE.current()).map(attribute).orElse(null);
        }

        static Optional<SpanContext> spanContextOf(Context context) {
            if (context == null) {
                // No context on this thread, so no span to attach. Handing the null to
                // Span.fromContextOrNull is the misuse OpenTelemetry reports.
                return Optional.empty();
            }
            return Optional.ofNullable(Span.fromContextOrNull(context)).map(Span::getSpanContext);
        }
    }
}
