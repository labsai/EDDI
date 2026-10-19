## chore(deploy): production-sized runtime defaults for the image, Helm and Kustomize (2026-10-17)

**Repo:** EDDI · **Branch:** `chore/enterprise-runtime-defaults`

### What changed and why

The defaults fitted a laptop demo, not the load profile the throughput work measured. EDDI waits on
LLMs far more than it computes, so memory, not CPU, decides how many conversation turns a node runs
at once. Each in-flight turn holds its conversation (about 1 MB for short conversations in the
load-test harness). The old defaults were a 1 GiB limit at the image's 50 % heap ratio, i.e. a
512 MB heap, and ParallelGC, which stops every thread on a full collection.

- **Image** ([`Dockerfile`](../../src/main/docker/Dockerfile)):
  - `JAVA_MAX_MEM_RATIO=75`.
  - `GC_CONTAINER_OPTIONS=-XX:+UseG1GC`, replacing run-java's ParallelGC.
  - `-XX:+ExitOnOutOfMemoryError` on `JDK_JAVA_OPTIONS`, so the flag survives an operator's
    `JAVA_OPTS`. A JVM out of heap exits and is restarted, instead of holding its turns and leases.
- **Helm** `eddi.resources` and the **Kustomize base**: request 1 CPU / 3 GiB, limit 4 CPU / 3 GiB.
  - Memory request = limit, for Guaranteed QoS.
  - A CPU limit is kept because the JVM sizes its GC, carrier and Jlama threads from it.
- **Production overlay:** request 2 CPU / 4 GiB, limit 4 CPU / 4 GiB.
- **Quickstart:** 1 GiB request, 2 GiB limit (was 384 Mi / 1 Gi).
- **docker-compose.yml:** `ulimits.nofile` 65536.
- **Docs:** a "Sizing a node" section in [`docs/kubernetes.md`](../kubernetes.md#sizing-a-node), and
  the Jlama memory note in [`docs/langchain.md`](../langchain.md). In-process Jlama weights are
  memory-mapped outside the heap, so lower the ratio and add the model size to the limit.

### Design decisions

- **G1, not ZGC, as the default.** G1 is the JDK's own server default and fits heaps of a few GB.
  ZGC is documented as the override for heaps of 8 GB and more.
- **Keep a CPU limit.** Removing it avoids CFS throttling, but the JVM would then size its threads
  for every core of a large node. The limit stays, set generously (4) with a request of 1.
- **No bump to the dependency charts** (MongoDB, PostgreSQL, Keycloak). Size them per installation.

### Not yet verified

- **The image's JVM command line** (heap ratio and GC through run-java) was not checked against a
  real container. Docker was unavailable when this was committed.
- **G1 vs ParallelGC under load** (p99 and the longest pause at 1000 conversations) is still to be
  measured with `scripts/loadtest`.
