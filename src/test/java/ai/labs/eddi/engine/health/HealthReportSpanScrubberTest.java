/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("HealthReportSpanScrubber — no owner report token in a trace")
class HealthReportSpanScrubberTest {

    static final String TOKEN = "Ab3_-".repeat(8) + "xyz";

    @Test
    @DisplayName("scrub: the token segment only, whatever follows; other paths untouched")
    void scrub() {
        assertEquals("/health/reports/{token}", HealthReportSpanScrubber.scrub("/health/reports/" + TOKEN));
        assertEquals("https://eddi.example/health/reports/{token}?x=1",
                HealthReportSpanScrubber.scrub("https://eddi.example/health/reports/" + TOKEN + "?x=1"));
        assertEquals("/health/reports/{token}", HealthReportSpanScrubber.scrub("/health/reports/{token}"));
        assertEquals("/agents/a/health/reports/r-1", HealthReportSpanScrubber.scrub("/agents/a/health/reports/r-1"), "the owner side");
        assertEquals("/health/agents", HealthReportSpanScrubber.scrub("/health/agents"));
        assertNull(HealthReportSpanScrubber.scrub(null));
    }

    @Test
    @DisplayName("a server span started with the token in url.path is exported without it")
    void spanAttributes() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(new HealthReportSpanScrubber())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        Span span = provider.get("test").spanBuilder("GET /health/reports/{token}").setSpanKind(SpanKind.SERVER)
                .setAttribute("url.path", "/health/reports/" + TOKEN).setAttribute("http.target", "/health/reports/" + TOKEN).startSpan();
        span.end();
        SpanData data = exporter.getFinishedSpanItems().get(0);
        assertEquals("/health/reports/{token}", data.getAttributes().get(AttributeKey.stringKey("url.path")));
        assertEquals("/health/reports/{token}", data.getAttributes().get(AttributeKey.stringKey("http.target")));
        provider.close();
    }
}
