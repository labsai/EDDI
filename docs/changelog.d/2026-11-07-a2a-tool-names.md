## 🐛 fix(a2a): tool names within 64 characters, never empty; card cache keyed by credential (2026-11-07)

**Repo:** EDDI (`fix/a2a-tool-names`)

### What changed and why

- **A2A tool names over 64 characters.** The agent name and the skill id were each bounded at 64 characters, then joined, so a tool name reached 129. OpenAI, Anthropic and Gemini refuse a function name over 64, and one such name fails every request of the agent, not just the call to that tool: a 40-character configured agent name next to a 50-character peer skill id was enough, and a hostile peer card could do it on purpose. `A2AToolProviderManager` now makes every tool name in one place (`toolName`): each part sanitized, the whole cut to 64 with a short hash of the uncut name — the hash suffix `distinctNamePart` already used for skill ids, now shared — so two long ids that share a prefix stay two tools.
- **Empty A2A tool names.** Sanitizing keeps only `[a-z0-9_]`, so an agent named in a script other than Latin (`検索エージェント`) gave an empty name. `ToolSpecification` does not check the name, so the blank name reached the provider, which refuses it, and every request of the agent failed. With skills, the name lost its agent part, so two such peers collided on `lookup` and the second was skipped. A part that sanitizing empties now becomes `a2a_<hash of the original>` (`skill_<hash>` for a skill id).
- **A blank configured `name` now falls back to the card's name**, as an absent one always did. The Manager writes `""` once a typed name is cleared; that gave an empty or agent-less tool name before, and the shared `a2a` fallback after the change above, so a second such peer was skipped as a name collision.
- **Agent cards cached by URL alone.** A card fetched with one config's credential — possibly an authenticated card listing skills the peer shows only to that caller — was served for five minutes to every config naming the same URL with another credential or none, and its skills became their tools. The cache is now keyed on the URL plus a SHA-256 digest of the *unresolved* `apiKey`, as `McpToolProviderManager.cacheKey` does for MCP clients; configs naming one vault reference still share a card. An unauthenticated fetch keeps the bare URL as its key.
- The discovery branch for a credential or malformed-config failure now logs `A2A agent at … skipped: <reason>` instead of claiming every such failure was a credential problem (a card naming an endpoint on another origin lands there too).

### Design decisions

- **The 64-character cap is on the whole name, the per-part 64 stays.** The part bound exists for memory (an unbounded agent name was copied into every skill's tool name); the whole-name cap is what providers enforce. Each does its own job.
- **Fallback with a hash, not a bare `a2a`.** A bare fallback makes every non-Latin peer collide, and the second is silently skipped as a duplicate.
- **The digest helper is local, not a shared `RemoteCacheKey`.** Extracting one would edit `McpToolProviderManager`, outside this change; the codebase already keeps such SHA-256 helpers local to their class.

**Files:** [`A2AToolProviderManager.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/A2AToolProviderManager.java), [`A2AToolProviderManagerTest.java`](../../src/test/java/ai/labs/eddi/modules/llm/impl/A2AToolProviderManagerTest.java), [`a2a-protocol.md`](../a2a-protocol.md)
