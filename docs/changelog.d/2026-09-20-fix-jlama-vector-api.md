## 🐛 fix(llm): Jlama was running on the scalar fallback in every JVM we ship (2026-09-20)

**Repo:** EDDI (`fix/jlama-vector-api`)

The `jlama` provider is registered in `LlmModule`, documented in `docs/langchain.md`, counted
among the supported providers, and offered by the agent wizard, the setup API, `McpSetupTools`
and `CreateSubAgentTool`. It was also, in every JVM this repository starts, running Jlama's
pure-scalar tensor backend.

### Why it was invisible

Jlama picks its backend once, at first use, in `TensorOperationsProvider.pickFastestImplementation()`:

1. it tries `NativeSimdTensorOperations`, which needs `com.github.tjake:jlama-native` — not on
   our classpath — logs "Native operations not available" and moves on;
2. it falls back to `MachineSpec.VECTOR_TYPE`, which reads `FloatVector.SPECIES_PREFERRED`
   inside a `catch (Throwable)`. Without `--add-modules=jdk.incubator.vector` that throws, the
   catch logs one line through Jlama's own logger, and the type stays `NONE`;
3. `NONE` selects `NaiveTensorOperations` — scalar Java matrix arithmetic.

So a Jlama agent *worked*. It answered correctly, orders of magnitude too slowly to use, and
nothing in the build could see it: no test configured a Jlama agent, and the container ITs
exercise the image over HTTP.

### What changed

- **`src/main/docker/Dockerfile`** — the flag on a new `ENV JDK_JAVA_OPTIONS`, **not** on
  `JAVA_OPTS_APPEND` where EDDI's other JVM settings live. The launcher reads
  `JDK_JAVA_OPTIONS` itself, so the flag survives an operator overriding `JAVA_OPTS` or
  `JAVA_OPTS_APPEND` — and a `docker run -e JAVA_OPTS_APPEND=...` *replaces* the image's value
  rather than appending to it. `docs/setup-eddi-on-aws-with-mongodb-atlas.md` does exactly that
  to pass a MongoDB connection string, so the obvious placement would have been silently dropped
  by a deployment shape we document ourselves.
- **`src/main/docker/Dockerfile.demo`** — the flag in the `ENTRYPOINT` array. The demo image
  starts EDDI with a bare `java` command, so it has neither `run-java.sh` nor any ENV to inherit
  from.
- **`pom.xml`** — the flag on the Surefire fork's `argLine`, and deliberately **not** on the
  Failsafe fork's.
  Failsafe's `argLine` *parameter* defaults to the `${argLine}` property, which both
  `jacoco:prepare-agent-integration` and the Quarkus Maven extension populate; declaring an
  explicit element there replaces the lot, dropping the JaCoCo IT agent that feeds the merged
  90/80 coverage gate, the add-opens Quarkus injects, and the serialized app-model path. An
  earlier revision of this branch did exactly that. ITs exercise the image over HTTP and never
  build a Jlama model in the test JVM, so the flag buys nothing there.
- **`mise.toml`** — the flag via `-Djvm.args` on **both** tasks that fork a dev JVM, `dev` and
  `debug`. `debug` was missed initially and the test could not see it, because it matched only
  the first `quarkus:dev` line.
- **`JlamaRuntimeSupport`** (new) — reads `MachineSpec.VECTOR_TYPE`, the field Jlama's own
  `TensorOperationsProvider` branches on, and logs one warning that distinguishes the two causes:
  a missing flag (fixable in one line) versus a CPU exposing no vector species Jlama accepts (not
  fixable by any flag). Warns rather than fails — a degraded Jlama still answers correctly, and
  failing closed would turn a slow deployment into a broken one on upgrade, for a condition the
  operator may not be able to fix.
- **`JlamaLanguageModelBuilder`** — exposes four of the five settings Jlama accepts and EDDI
  was dropping: `modelCachePath`, `quantizeModelAtRuntime`, `workingDirectory`,
  `workingQuantizedType`. Booleans go through `ModelParameterValues.applyBoolean` rather than
  `Boolean.parseBoolean`, so `"ture"` leaves the provider default instead of silently meaning
  `false`; `workingQuantizedType` resolves against Jlama's `DType` enum with an unknown name
  logged and ignored rather than thrown on every turn. The parameter mapping moved into a
  package-visible `applyTo` that stops short of `build()`.
- **`threadCount` is deliberately NOT exposed**, though Jlama's builder accepts it and an
  earlier revision of this branch mapped it. `JlamaModel.Loader` hands the value to
  `ModelSupport.loadModel`, which calls the process-global
  `PhysicalCoreExecutor.overrideThreadCount` — a one-shot latch
  (`if (!started.compareAndSet(false, true)) throw new IllegalStateException(...)`) that the
  executor's memoized `instance` supplier also arms merely by running inference. A per-model
  parameter cannot honour a process-global one-shot setting: the second Jlama model built in
  a process would throw `"Executor already started"` during load, even with an identical
  value. And `ChatModelRegistry` rebuilds models on cache eviction, secret rotation and a
  30-minute idle TTL, so a second build is routine rather than exotic. Caught in review;
  pinned by a test, because the setter sits on the builder right next to the mapped ones and
  re-adding it looks like an obvious omission being corrected.

`modelCachePath` is the one that matters operationally, though not for the reason that first
looked obvious. Jlama caches weights under `${user.home}/.jlama/models`, and in the EDDI image
that path *is* writable — including for an arbitrary OpenShift UID, because the base image sets
`HOME` and the JDK falls back to it. The real problem is that it resolves to the pod's ephemeral
writable layer: the multi-gigabyte weights live exactly as long as the pod, so every restart
re-downloads them from Hugging Face before the first turn can be answered, and an air-gapped
deployment cannot start at all.

### Design decisions

- **No `jlama-native`.** It would put platform-specific, glibc-sensitive shared objects into a
  digest-pinned UBI image, add Trivy scan surface and require `--enable-native-access`. The
  Vector API path is pure Java and gets most of the benefit.
- **No live-inference smoke test.** It would download gigabytes from Hugging Face on every CI
  run. `JlamaRuntimeSupportTest#simdBackendIsAvailableInThisJvm` asserts that *Jlama's own*
  backend selection finds a vector type in the test JVM instead — which is the thing that was
  silently false, and it costs microseconds.
- **Probe Jlama's decision, not the module.** An earlier revision asked whether
  `jdk.incubator.vector` was resolved. That is a strictly weaker question: `MachineSpec` accepts
  only a 512- or 256-bit preferred species (or 128-bit on ARM), so on an x86 host exposing a
  128-bit species — a hypervisor masking AVX2, or `-XX:UseAVX=0` — the module resolves, the class
  loads, and Jlama still picks the scalar backend with no warning. Reading
  `MachineSpec.VECTOR_TYPE` cannot diverge from what Jlama actually does.
- **`timeout` stays a pipeline key.** `JlamaChatModel.builder()` has no timeout setter; the
  value is applied by `ObservableChatModel` as a wall-clock bound. Both tempting "fixes" —
  adding it to `recognisedParameters()`, or deleting it from the documented example — would be
  wrong, so `JlamaTests#timeoutIsNotABuilderParameter` pins the current behaviour.

### Verified against the real base image

Rather than reasoning about whether `run-java.sh` forwards a non-`-D` flag, this was run inside
`ubi10/openjdk-25-runtime` at the exact digest the Dockerfile pins:

- without the flag: `moduleResolved=false classLoadable=false` — the bug, reproduced in the
  published base image;
- with it: `moduleResolved=true classLoadable=true`, plus the expected
  `WARNING: Using incubator modules` line;
- with it on `JDK_JAVA_OPTIONS` and `JAVA_OPTS` *or* `JAVA_OPTS_APPEND` overridden by the
  operator: still `true`. That is why the flag lives there and not on `JAVA_OPTS_APPEND`.

### Tests

`JlamaRuntimeFlagsTest` (5) greps both Dockerfiles, every `<argLine>` in `pom.xml` and every mise
task that forks a dev JVM, so losing the flag from any of the five places it has to appear fails
the build where the change was made. It also asserts the flag is on *exactly one* `<argLine>`, so
re-adding it to failsafe fails here rather than quietly costing the IT coverage data.
`JlamaRuntimeSupportTest` (10) proves the SIMD backend is genuinely selected in a running JVM —
the two are complementary: a typo fails both, a flag written into a config block Maven never
applies fails only the second. `LanguageModelBuildersTest.JlamaTests` (10) covers the parameter
mapping, including that every key in `recognisedParameters()` actually changes builder state,
that a mistyped boolean does not silently mean `false`, that an unknown `DType` name is ignored
rather than thrown, that `threadCount` stays unmapped, and that the Hugging Face token stays
masked.

### Docs

`docs/langchain.md`'s Jlama section rewritten: a full parameter table, the required JVM flag and
why its absence is silent, and the three container behaviours that differ without erroring —
`modelCachePath` landing on the ephemeral layer, inference threads being sized from a CPU
*limit* but not a *request* (cgroup shares have been ignored since JDK 19), and memory sizing for
memory-mapped safetensors that count against the container limit but not the heap. Plus the
air-gap caveat that an empty cache with no egress fails rather than degrades.

Assessment behind this work: [`planning/inference-stack-fit.md`](../../planning/inference-stack-fit.md).
It also carries a correction — an earlier draft wrongly called `timeout` an unrecognised
parameter.

---
