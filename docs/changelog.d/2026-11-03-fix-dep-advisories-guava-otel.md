## Clear the Guava advisory flagged by Scorecard; OpenTelemetry blocked on Quarkus (2026-11-03)

**Repo:** EDDI, branch `fix/dep-advisories-guava-otel`

**What changed** (`pom.xml`, `dependencyManagement` only): `com.google.guava:guava` 33.6.0-jre -> 33.7.2-jre, for GHSA-xxph-c9ww-hj94 (uncontrolled memory allocation when `CompactHashMap`, `CompactHashSet` or `MapMakerInternalMap` is deserialized). Pulled in by `langchain4j-qdrant`. Not reachable from a known code path; fixed because OpenSSF Scorecard counts advisories regardless of reachability.

**Not fixed - GHSA-ccrj-gw8r-854m** (quadratic CPU in W3C `tracestate` parsing, `opentelemetry-api` <= 1.66.0, fixed in 1.67.0). Quarkus 3.40.1.1, the latest platform, manages OpenTelemetry at 1.62.0, and 1.67.0 cannot be forced in either shape; both were tried and both fail at startup (Backend E2E caught the first, a local boot the second):
- Whole family at 1.67.0 (BOM imports): `NoSuchMethodError: io.opentelemetry.exporter.internal.ExporterBuilderUtil.validateEndpoint(String)`, because `quarkus-opentelemetry` calls an internal exporter method that 1.67 changed.
- API line only (`opentelemetry-api`, `-context`, `-common`) at 1.67.0 with SDK and exporter at 1.62.0: `NoClassDefFoundError: io.opentelemetry.api.internal.ConfigUtil`, which 1.67 removed and the 1.62 SDK still uses.

Both pass every unit test, so only a real boot shows it. Revisit when a Quarkus platform manages OpenTelemetry 1.67.0 or later. Until then the exposure is a long inbound `tracestate` header; capping header size at the proxy (or `quarkus.http.limits.max-header-size`) bounds it.

The `minimatch` advisory (GHSA-g3hv-75wg-m249, dev tooling only) is handled in a separate PR.
