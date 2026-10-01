## 📈 fix(metrics): static files no longer exhaust the HTTP metrics URI tags (2026-10-02)

**Repo:** EDDI (`fix/http-metrics-uri-cardinality`)

### The bug

`http.server.requests` tags each request with its URI, and Micrometer registers at most
`quarkus.micrometer.binder.http-server.max-uri-tags` distinct values. After that, every new URI is
denied and gets no request metrics, with a single WARN to show for it. REST endpoints are tagged by
template, but Quarkus tags a static file by its literal path, and the image ships 816 content-hashed
Manager chunks under `/assets` plus 65 fonts. A few minutes of clicking through the Manager on the
6.5.0 release candidate used up the cap of 200. Every API endpoint first called after that went
unmeasured.

The cap was also too small without the assets: the API has 265 distinct paths (the Manager's
OpenAPI snapshot). The value came in with an unrelated commit in 2022 and had never been sized.

### What changed

- [`application.properties`](../../src/main/resources/application.properties):
  `match-patterns` folds `/assets/*`, `/fonts/*`, `/scripts/*` and `/img/*` into one tag per
  directory (`/assets/{file}` …). `max-uri-tags` goes from 200 to 500.
- `HttpMetricsUriTagsConfigTest` (new) parses the patterns the way Quarkus 3.40 does (first `=`,
  trimmed, whole-path `matches()`, read from the bytecode of `HttpBinderConfiguration` and
  `RequestMetricInfo`). It checks that representative asset, font, script and image paths fold, that
  REST and UI-shell paths are left alone, and that the cap stays at least 100 above the path count
  in `ui/manager/src/test/mocks/openapi-operations.json`. As the API grows into the headroom, this
  test fails before production does.
- [`metrics.md`](../metrics.md) explains the `uri` values and the cap.

### Verified

On the CI image of 1150bc1e1, with the two settings passed as environment variables, I requested
all 881 static files the jar ships and then the UI shells and an API call. That produced 13 distinct
`uri` tags and no cap warning. Without the settings, a handful of Manager pages produced 95.
