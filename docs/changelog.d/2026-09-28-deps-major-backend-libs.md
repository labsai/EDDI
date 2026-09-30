## ⬆️ chore(deps): json-path 3; langchain4j and three Jackson-3 libraries held to what Quarkus ships (2026-09-28)

**Repo:** EDDI (`chore/deps-major-backend-libs`, on top of `chore/deps-minor-upgrades`)

### The rule

**If a library comes from Quarkus or integrates with it, EDDI takes the version the Quarkus platform
ships, and nothing newer.** A newer version waits until Quarkus has it; otherwise there has to be a
concrete reason. Each item below either sits outside Quarkus entirely or is held on that rule.

### What changed

| Artifact | From → To | Note |
|---|---|---|
| `com.jayway.jsonpath:json-path` | 2.10.0 → **3.0.0** | Not in the Quarkus BOM. 3.0 raises the baseline to Java 17 and adds optional Jackson 3 providers, which load only when selected. The public `JsonPath` class is identical under `javap`, and the default provider is still json-smart 2.6.0 (runtime scope, unchanged). EDDI's one caller, `ContextMatcher`, needed no change: `ContextMatcherTest` passes 22/22 and `RuleConfigValidationTest` 14/14 |

### Held

**langchain4j stays at 1.20.0.** The Quarkus 3.39.5 platform ships langchain4j **1.19.3** through
`quarkus-langchain4j-bom`. EDDI is already ahead of that at 1.20.0, so it goes no further until
Quarkus moves.

An earlier commit on this branch took 1.20.2, pinning `langchain4j-community-oci-genai` at
`1.20.0-beta30` because that module has no 1.20.2 release. That commit is reverted, together with
its pom-comment exception and its `BuildQualityGatesTest` allowance. The investigation still holds
for when Quarkus moves:

- Every `.class` in langchain4j-core 1.20.2 is byte-identical to 1.20.0.
- Maven mediates the OCI module onto the newer core.

**Three libraries need Jackson 3.** Each uses `tools.jackson.*` in its public API, which the
`ban-jackson3` enforcer rule forbids. The Quarkus 3.39.5 BOM manages Jackson 2 (2.22.2), so they stay
on their newest Jackson-2 versions until Quarkus moves to Jackson 3:

| Artifact | Stays at | Blocked by (Maven Central pom and `javap`) |
|---|---|---|
| `com.networknt:json-schema-validator` | 1.5.9 | 3.0.7 depends on `tools.jackson.core:jackson-databind` (compile scope), and `Schema.validate(ExecutionContext, tools.jackson.databind.JsonNode)` takes Jackson 3 nodes |
| `com.github.victools:jsonschema-generator` + `-module-jackson` | 4.38.0 | In 5.0.0, `SchemaGenerator.generateSchema(...)` returns `tools.jackson.databind.node.ObjectNode` |
| `de.undercouch:bson4jackson` | 2.18.0 | In 3.x, `BsonFactory extends tools.jackson.core.json.JsonFactory` |

```decision-log
| 2026-09-29 | Libraries that come from Quarkus or integrate with it take the version the Quarkus platform ships, never a newer one | Quarkus validates its platform as a set; running ahead of it invites classpath and behaviour mismatches nobody tested | Taking the newest stable release of every library independently |
| 2026-09-29 | langchain4j stays at 1.20.0 (1.20.2 reverted) | The Quarkus 3.39.5 platform ships langchain4j 1.19.3 via quarkus-langchain4j-bom | 1.20.2 with langchain4j-community-oci-genai pinned at 1.20.0-beta30 |
| 2026-09-28 | json-schema-validator 3.x, victools jsonschema-generator 5.x and bson4jackson 3.x not taken | All three require Jackson 3 (tools.jackson.*) in their public API; the ban-jackson3 enforcer rule forbids it and the Quarkus BOM ships Jackson 2 | Lifting the Jackson 3 ban for three libraries as part of a dependency bump |
```
