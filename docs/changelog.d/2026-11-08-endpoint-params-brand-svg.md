## 🐛 fix(configs): one endpoint-parameter rule; brand SVG refuses CSS escapes (2026-11-08)

**Repo:** EDDI (`fix/endpoint-params-brand-svg`)

### What changed and why

**One answer to "does this parameter name an endpoint?"** Four places asked it, each with its own list, and the lists had drifted. The LLM registry knew Vertex's `apiEndpoint`; the embedding check knew `apiBase`, `api_base` and `apiUrl`; the export merge (`ScrubbedSecrets`) knew neither `apiEndpoint` nor `apiBase`. The merge's list matters most: it decides whether a target's hidden literal credential is put back into a synced config, and it withholds the credential when the config's destination changed. A synced config that changed only `apiEndpoint` or `apiBase` therefore had the target's literal key restored next to an endpoint the editor chose. No shipped builder reads either key together with an `apiKey` today (Vertex uses ADC), so this was latent, but the next builder that does would have opened it.

[`EndpointParameters.isEndpointKey`](../../src/main/java/ai/labs/eddi/utils/EndpointParameters.java) is now the one rule: case-insensitive, any key ending in `url`, plus `uri`, `endpoint`, `apiEndpoint`, `apiBase`, `api_base`, `host` and `hostname`. The rule is the union of the old lists. `ScrubbedSecrets`, `ChatModelRegistry` (the `allowedHosts` check), `EmbeddingParameters` (refusing an endpoint the provider ignores) and `EmbeddingModelFactory` (its `allowedHosts` check) all use it.

Behaviour changes, all in the refusing direction:
- **Agent sync / upgrade:** a change to any of these keys now keeps the `${vault:REDACTED}` placeholder instead of restoring the target's literal credential.
- **LLM `allowedHosts`:** every endpoint parameter that is set is checked against a referenced secret's list. That covers `apiBase`, `hostname`, `uri` and any `…Url`, not only the six keys listed before.
- **Embedding parameters:** an endpoint parameter the provider does not honour is refused under more spellings (`apiEndpoint`, `hostname`, `uri`, `proxyUrl`, …), and with a restricted secret it is reported as an `allowedHosts` refusal first, as the factory's comment already said.

**Brand SVGs refuse backslashes.** [`BrandAssetValidator`](../../src/main/java/ai/labs/eddi/configs/branding/BrandAssetValidator.java) checked `url(` and `@import` as literal text. CSS decodes escapes, so `u\72l(http://…)` and `@\69mport "…"` load external resources while matching neither check. Any backslash now refuses the SVG. The check covers the whole document, not only `<style>` and `style="…"`, because every presentation attribute (`fill`, `filter`, `mask`, …) is parsed as CSS too. Finding where an element really ends would also need a parser that agrees with the browser's (CDATA, end tags). `image-set()` is refused as well, since it loads an image from a plain string without `url()`. An editor that writes a Windows path into its metadata (Inkscape's `inkscape:export-filename`) now trips the check, and the refusal message says to export a plain or optimized SVG.

**Docs:** [White-labeling](../white-labeling.md), [Agent sync](../agent-sync-guide.md) and [Secrets vault](../secrets-vault.md) list the new rules.

### Tests

`EndpointParametersTest`; `ScrubbedSecretsDestinationTest` (every endpoint spelling is a destination; an ordinary parameter is not); `ChatModelRegistryAllowedHostsTest` (`apiBase`, `hostname` and `proxyUrl` are checked); `EmbeddingParametersBranchTest` (`apiEndpoint` on Vertex, `hostname` on Bedrock); `VaultAllowedHostsCoverageTest` (an unsupported endpoint key is judged by `allowedHosts`); `BrandAssetValidatorTest` (escaped `url(`/`@import` in `<style>`, CDATA, `style` and presentation attributes; `image-set()`; editor metadata refused with the way out).
