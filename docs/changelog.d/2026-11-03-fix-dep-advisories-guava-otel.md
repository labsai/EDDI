## Clear the Guava and OpenTelemetry advisories flagged by Scorecard (2026-11-03)

**Repo:** EDDI, branch `fix/dep-advisories-guava-otel`

**What changed** (`pom.xml`, `dependencyManagement` only):
- `com.google.guava:guava` 33.6.0-jre -> 33.7.2-jre, for GHSA-xxph-c9ww-hj94 (uncontrolled memory allocation when `CompactHashMap`, `CompactHashSet` or `MapMakerInternalMap` is deserialized). Pulled in by `langchain4j-qdrant`.
- OpenTelemetry family 1.62.0 -> 1.67.0 by importing `opentelemetry-bom` and `opentelemetry-bom-alpha` ahead of the Quarkus BOM, for GHSA-ccrj-gw8r-854m (quadratic CPU in W3C `tracestate` parsing, `opentelemetry-api` <= 1.66.0).

**Design decisions:**
- Pinned the whole OpenTelemetry family rather than `opentelemetry-api` alone, so api, sdk and the OTLP exporter stay on one release; the alpha BOM keeps `opentelemetry-api-incubator` aligned.
- The Quarkus BOM (3.40.1.1) still manages OpenTelemetry at 1.62.0. Remove both BOM imports once Quarkus manages 1.67.0 or later.
- Not reachable from a known code path in either case; fixed because OpenSSF Scorecard counts advisories regardless of reachability.
- The `minimatch` advisory (GHSA-g3hv-75wg-m249, dev tooling only) is handled in a separate PR.

**Test fixtures:** `OutboundHttpTracePropagationTest` and `A2ATaskCredentialWiringTest` built an `SdkTracerProvider` with no span processor. On 1.67 such a provider returns a non-recording span with an invalid context, so no `traceparent` is sent and three assertions failed. Both now register a `SimpleSpanProcessor` over an `InMemorySpanExporter`, as the other tracing tests already did. Production code is unchanged.
