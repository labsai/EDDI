## 🐛 test(cluster): NatsTlsHandshakeTest race that failed unrelated PRs, mutual-TLS test re-enabled (2026-11-10)

**Repo:** EDDI (`fix/nats-tls-test-race`)

### What changed and why

`NatsTlsHandshakeTest` failed `Build & Test` intermittently on the Linux CI
runner with `expected: <CONNECTED> but was: <CLOSED>`, on branches that touch
neither NATS nor TLS (`acceptsACertificateWhoseOnlySanIsTheConfiguredHostName`
most often, `presentsTheJvmDefaultClientCertificateWhenNoEddiKeyStoreIsConfigured`
once). `presentsTheClientCertificateToABrokerThatRequiresMutualTls` had been
quarantined with `@Disabled` for 6.7.0 for the same symptom.

The cause was the test's own `connect(...)` helper. It opened the connection
with `Nats.connect(manager.buildOptions())` and shut the manager down in a
`finally`. `buildOptions()` installs the manager's connection listener, and
jnats delivers CONNECTED to it on its own executor thread. Whenever that event
arrived before `shutdown()` ran, the listener adopted the connection
(`connection = conn`, `status = CONNECTED`), so `shutdown()` no longer took its
"never started" early return and drained and closed the very connection the
test was about to assert on. Faster machines usually ran `shutdown()` first,
which is why it passed locally.

- The helper keeps each manager alive and `@AfterEach` shuts the managers down
  after the test has asserted on and closed its connection, then stops the fake
  servers.
- The helper now waits until the manager reports `isConnected()` before it
  returns, so the listener has always adopted the connection by the time a test
  asserts. The race outcome that used to fail now and then is the one every run
  takes: putting the old `manager.shutdown()` back into the helper fails all
  three CONNECTED tests every time (checked locally), instead of rarely.
- `presentsTheClientCertificateToABrokerThatRequiresMutualTls` is re-enabled.

`NatsConnectionManager` is unchanged: in production its listener is installed
only through `start()`, and closing the connection that listener adopted is
exactly what `shutdown()` must do. Only a test that builds the options without
`start()` could be surprised by it.

**Files:** [`NatsTlsHandshakeTest.java`](../../src/test/java/ai/labs/eddi/engine/cluster/NatsTlsHandshakeTest.java)

```regression-note
| 2026-11-10 | NatsTlsHandshakeTest intermittently CLOSED instead of CONNECTED on CI, failing unrelated PRs; mutual-TLS test quarantined | Test helper shut the manager down in a finally; when jnats' CONNECTED event reached the manager's listener first, shutdown() closed the adopted connection | Managers shut down in @AfterEach; helper waits for the listener to adopt the connection, making the old bug deterministic; mutual-TLS test re-enabled | fix/nats-tls-test-race |
```
