## ⬆️ chore(deps): backend major upgrades — json-path 3, langchain4j 1.20.2; three libraries blocked on Jackson 3 (2026-09-28)

**Repo:** EDDI (`chore/deps-major-backend-libs`, stacked on `chore/deps-minor-upgrades`)

### What changed

| Artifact | From → To | Note |
|---|---|---|
| `com.jayway.jsonpath:json-path` | 2.10.0 → **3.0.0** | Java 17 baseline plus optional Jackson 3 providers that load only when selected. The public `JsonPath` class is `javap`-identical, the default provider is still json-smart 2.6.0 (runtime scope, unchanged). EDDI's one caller, `ContextMatcher`, needed no change |
| langchain4j (`langchain4j.version` / `langchain4j-beta.version`) | 1.20.0 / 1.20.0-beta30 → **1.20.2 / 1.20.2-beta30** | Every module moves except `langchain4j-community-oci-genai`, which stays at **1.20.0-beta30** |

### The one langchain4j artifact off the properties

The community reactor never published `langchain4j-community-oci-genai` 1.20.2-beta30, and the
pom says a split between langchain4j modules shows up at runtime as a `NoSuchMethodError`. The module
is now pinned to a literal `1.20.0-beta30`, and the comment above the langchain4j properties records
this as the single exception, the two conditions it depends on, and when to undo it. Both conditions
were checked:

- **Mediation.** `dependency:tree -Dverbose -Dincludes=dev.langchain4j:langchain4j-core` shows the
  OCI module's own `langchain4j-core:1.20.0` as *omitted for conflict with 1.20.2*. Every module on
  the classpath gets core 1.20.2.
- **Linkage.** The OCI jar's constant pools reference 43 langchain4j-core classes (163 distinct
  class, method and field refs), and it implements `ChatModel` and `StreamingChatModel`. None of those
  changed. `javap -p -constants` over **every** class in core 1.20.0 and 1.20.2 produces identical
  output, and `diff -r` of the unpacked jars differs only in `META-INF/maven/.../pom.*`: all `.class`
  files are byte-identical. So in 1.20.x the pin cannot cause a linkage error.

`BuildQualityGatesTest.langchain4jArtifactsUseOneOfTwoProperties` guards the property rule, so it
now carries an explicit `LANGCHAIN4J_PINNED_EXCEPTIONS` map (artifact → exact literal). An entry is
tolerated only while its `major.minor` matches `langchain4j.version`. The next minor bump (1.21) fails
the gate and forces the re-check. An entry that no longer matches a pom dependency also fails, so the
allowance cannot outlive the pin. A mutation run with the pin moved to `1.19.0-beta29` failed with
the "off the 1.20 line" message. Files:
[`pom.xml`](../../pom.xml),
[`BuildQualityGatesTest.java`](../../src/test/java/ai/labs/eddi/BuildQualityGatesTest.java).

No test builds a live `OciGenAiChatModel`, because building one needs OCI auth. The only OCI test
coverage, `LanguageModelBuildersTest`, instantiates the builder.

### Not taken: every other upgrade in scope needs Jackson 3

Each of the libraries below compiles against `tools.jackson.*` (Jackson 3) in its public API. The
enforcer rule `ban-jackson3` forbids that, and the rest of EDDI is on `com.fasterxml.jackson` 2.x.
They stay where they are:

| Artifact | Stays at | Blocked by (Maven Central pom and `javap`) |
|---|---|---|
| `com.networknt:json-schema-validator` | 1.5.9 | 3.0.7 depends on `tools.jackson.core:jackson-databind` (compile scope), and `Schema.validate(ExecutionContext, tools.jackson.databind.JsonNode)` takes Jackson 3 nodes |
| `com.github.victools:jsonschema-generator` + `-module-jackson` | 4.38.0 | In 5.0.0, `SchemaGenerator.generateSchema(...)` returns `tools.jackson.databind.node.ObjectNode` |
| `de.undercouch:bson4jackson` | 2.18.0 | In 3.x, `BsonFactory extends tools.jackson.core.json.JsonFactory` |

Taking any of them means migrating EDDI to Jackson 3 first. That is a project-level decision, not a
dependency bump.

```decision-log
| 2026-09-28 | langchain4j-community-oci-genai stays pinned at 1.20.0-beta30 while the rest of langchain4j moves to 1.20.2 | No 1.20.2-beta30 of the OCI module exists. langchain4j-core 1.20.2 is class-for-class byte-identical to 1.20.0, and Maven mediates the OCI module onto core 1.20.2 | Holding all of langchain4j at 1.20.0 until the community reactor catches up |
| 2026-09-28 | json-schema-validator 3.x, victools jsonschema-generator 5.x and bson4jackson 3.x not taken | All three require Jackson 3 (tools.jackson.*) in their public API, which the ban-jackson3 enforcer rule forbids | Lifting the Jackson 3 ban for three libraries as part of a dependency bump |
```
