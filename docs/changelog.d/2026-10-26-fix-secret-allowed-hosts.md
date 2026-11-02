## 🔒 fix(secrets): `allowedHosts` now also governs transport, connections, Slack bot tokens and discovery's `authHeaderRef` (2026-10-26)

**Repo:** EDDI (`fix/secret-allowed-hosts`)

Four findings from the repository security scan of `daecb3513`, all about a vaulted secret reaching a destination its `allowedHosts` list was meant to exclude.

### What changed and why

- **Plaintext http to an allowed host.** [`VaultHostAllowlist`](../../src/main/java/ai/labs/eddi/secrets/VaultHostAllowlist.java) compared host and port only, so `http://api.openai.com` matched `api.openai.com` and an editor could send a restricted key unencrypted. A secret with a list is now refused for `http://` / `ws://` to any non-loopback host, even a listed one. Loopback (`localhost`, `127.0.0.1`, `[::1]`) is exempt, the same rule a connection's `allowPlaintextRemoteOrigins` applies. A deployment that does send restricted secrets to an internal service over http sets `eddi.vault.allow-plaintext-remote-hosts=true`, which still admits only listed hosts. Schemeless destinations (pgvector/Qdrant `host:port`) and `mongodb://` are not judged by scheme. Unrestricted secrets are unchanged.
- **A connection bypassed the secret's own list.** [`ConnectionResolver`](../../src/main/java/ai/labs/eddi/connections/ConnectionResolver.java) checked the target against the connection's `baseUrlAllowlist` and then turned the `${vault:…}` references behind a STATIC `valueTemplate` or BASIC `passwordRef` into plaintext without consulting their `allowedHosts`. [`CredentialReferenceResolver`](../../src/main/java/ai/labs/eddi/connections/CredentialReferenceResolver.java) now takes the target and checks every vault reference, after `${vars:…}` expansion and before decryption; a refusal is `TARGET_NOT_ALLOWED`. Because the check sits in the resolver, it covers every caller (`ApiCallExecutor`, MCP), not only the one the finding named.
- **Slack `botToken` resolved without a destination check.** [`ChannelTargetRouter`](../../src/main/java/ai/labs/eddi/integrations/channels/ChannelTargetRouter.java) now checks a vaulted `botToken` (new-style integrations and legacy agent connectors) against `SlackWebApiClient.SLACK_API_BASE` before resolving it. A refused token is withheld (the integration cannot post) with a `[SECURITY]` WARN. Signing secrets are inbound-only and not checked.
- **`authHeaderRef` prefix check.** [`RestApiCallsStore`](../../src/main/java/ai/labs/eddi/configs/apicalls/rest/RestApiCallsStore.java) discovery accepted any value that *started with* a reference prefix, so `${vault:x}sk-live-…` passed and the literal was copied into every generated call. The value must now be exactly one `${vault|eddivault|vars|caller:…}` reference, whole-string.

### What operators see

A restricted secret that is used today over plain http to a non-loopback host — e.g. an in-cluster `http://elasticsearch:9200` or an OpenAI-compatible server at `http://vllm:8000` — now fails at that call/build with a message naming the destination and `eddi.vault.allow-plaintext-remote-hosts`. Switch the endpoint to https, or set the property. Docs: [secrets-vault.md → Destination allow-list](../secrets-vault.md#destination-allow-list-allowedhosts).

### Tests

`VaultHostAllowlistTest` (new `EncryptedTransport` group), new `ConnectionResolverVaultAllowedHostsTest`, new `ChannelTargetRouterAllowedHostsTest`, and a new case in `RestApiCallsStoreBranchTest`. Each was mutation-checked: with the fix reverted, the new tests fail.

### Not done

- The Manager's `isAuthReference` (`ui/manager/src/lib/secret-reference.ts`) is still a prefix check. The server now refuses what it lets through, but it should use the same whole-string grammar.
- An OAuth connection's client secret is not checked against its own `allowedHosts` at the token endpoint. `credentialEndpointAllowlist` governs where it goes.
