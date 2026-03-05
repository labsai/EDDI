# EDDI Backend — AI Agent Instructions

> **This file is automatically loaded by AI coding assistants. Follow ALL rules below.**

## 1. Project Context

**EDDI** (Enhanced Dialog Driven Interface) is a multi-agent orchestration middleware for conversational AI. This repo is the **Java/Quarkus backend**.

### Ecosystem (5 repos, all under `c:\dev\git\`)

| Repo                       | Tech                      | Purpose                                                    |
| -------------------------- | ------------------------- | ---------------------------------------------------------- |
| **EDDI** (this repo)       | Java 21, Quarkus, MongoDB | Backend engine, REST API, lifecycle pipeline               |
| **EDDI-Manager**           | React, TypeScript         | Admin dashboard (served from EDDI at `/chat/unrestricted`) |
| **eddi-chat-ui**           | React, TypeScript         | Standalone chat widget                                     |
| **eddi-website**           | HTML → migrating to Astro | Marketing site at eddi.labs.ai                             |
| **EDDI-integration-tests** | Java                      | End-to-end API tests                                       |

### Key Architecture

- **Config-driven engine**: Bot logic is JSON configs, Java is the processing engine
- **Lifecycle pipeline**: Input → Parse → Behavior Rules → Actions → Tasks → Output
- **Stateless tasks, stateful memory**: `ILifecycleTask` implementations are singletons; all state lives in `IConversationMemory`
- **Action-based orchestration**: Tasks emit/listen for string-based actions, never call each other directly
- **CI**: CircleCI (compile → test → Docker build → integration tests → push to Docker Hub), migrating to GitHub Actions

---

## 2. Mandatory Workflow Protocol

### Before Starting Any Work

1. **Read the planning docs** in [`docs/v6-planning/`](docs/v6-planning/):
   - [`implementation_plan.md`](docs/v6-planning/implementation_plan.md) — Full architecture audit (14 appendices, A-N) and phased roadmap
   - [`business-logic-analysis.md`](docs/v6-planning/business-logic-analysis.md) — Configuration model, Bot Father, parser/expression deep dive
   - [`changelog.md`](docs/v6-planning/changelog.md) — Running log of all changes, decisions, and reasoning across sessions
   - [`phase0-walkthrough.md`](docs/v6-planning/phase0-walkthrough.md) — Phase 0 completion summary (CORS + PathNavigator)
2. **Check git status**: Run `git status` and `git log -5 --oneline` to see current branch state and recent work.

### During Work

3. **Branch: `feature/version-6.0.0`**: All v6.0 work branches from and merges back to `feature/version-6.0.0`. **Do NOT commit directly to `main`.**
4. **Commit often**: Every working unit gets a commit. Use conventional commits:
   ```
   feat(scope): description
   fix(scope): description
   chore(scope): description
   refactor(scope): description
   ```
5. **Each commit must build**: Run `./mvnw compile` (or `./mvnw test` for backend) before committing. Never commit broken code.

### After Completing Work (or if interrupted/switching sessions)

6. **Update the changelog**: Edit [`docs/v6-planning/changelog.md`](docs/v6-planning/changelog.md) and add an entry with:
   - Date and short title
   - Repo and branch
   - What changed (files + reasoning)
   - Design decisions made
   - What's in progress / what's next (if interrupted mid-task)

---

## 3. Development Order (Master Plan)

Follow this order unless the user explicitly requests something different.
**Backend first, then testing, then frontend. Website last.**

```
Phase 0: Security Quick Wins (6 SP)
  0a. Restrict CORS origins                          1 SP
  0b. Create PathNavigator (replace OGNL calls)      5 SP

Phase 1: Backend Foundation (20 SP)
  1. Extract ConversationService from RestBotEngine   5 SP
  2. Decompose LangchainTask into focused classes     5 SP
  3. Add SSE streaming API endpoint                   5 SP
  4. Typed memory accessors (MemoryKey<T>)            3 SP
  5. Extract ConfigurationLoader utility              2 SP

Phase 2: Testing Infrastructure (14 SP)
  6. Migrate integration tests to main repo (JUnit5) 3 SP
  7. Add @QuarkusTest + Testcontainers component tests 3 SP
  8. Fill unit test gaps (SizeMatcher, tasks, etc.)   3 SP
  9. API contract tests (JSON Schema)                 2 SP
  10. Langchain/agent integration test (WireMock)     3 SP

Phase 3: Manager (Greenfield Rewrite) (36 SP)
  11. Study existing Manager, document patterns       2 SP
  12. Init Vite + shadcn/ui + Tailwind + i18n         2 SP
  13. Layout shell (sidebar, responsive, dark/light)  3 SP
  14. Bots page (cards, health, dedup)                5 SP
  15. Bot detail page (packages, config editor)       5 SP
  16. Chat panel (shared @eddi/chat-core + SSE)       5 SP
  17. Conversations page (pagination, search, export) 3 SP
  18. Resources pages                                 3 SP
  19. i18n + RTL                                      3 SP
  20. Import/export UI + bot creation wizard          5 SP

Phase 4: Chat-UI Rewrite (10 SP)
  21. CRA → Vite migration                            2 SP
  22. SSE streaming support                           3 SP
  23. Extract shared @eddi/chat-core package           3 SP
  24. Dark/light mode + i18n                          2 SP

Phase 5: Website — Astro Migration (14 SP)
  25. Scaffold Astro + Tailwind + i18n                2 SP
  26. Migrate content into components                 3 SP
  27. Dark/light + RTL                                3 SP
  28. Documentation pages (Content Collections)       5 SP
  29. GitHub Actions deployment                       1 SP

Phase 6: CI/CD (8 SP)
  30. GitHub Actions for EDDI (replace CircleCI)      3 SP
  31. GitHub Actions for Manager + Chat-UI + Website  5 SP

Deferred (post v6.0):
  - NATS JetStream message queue
  - PostgreSQL / DB-agnostic architecture
  - Redis distributed cache
  - MCP Server + Client
  - Multi-bot orchestration, cascading routing
  - Persistent user memory, multi-channel adapters
  - Helm chart + OpenTelemetry
```

---

## 4. Backend Java Guidelines

(The following rules apply specifically when writing Java code for this repo.)

### Golden Rules

1. **Logic is Configuration, Java is the Engine** — Bot behavior MUST NOT be hard-coded in Java. Bot logic belongs in JSON configs (`behavior.json`, `httpcalls.json`, `langchain.json`).
2. **Stateless Tasks, Stateful Memory** — `ILifecycleTask` implementations are singletons. All state goes in `IConversationMemory`.
3. **Action-Based Orchestration** — Tasks emit/listen for string-based actions, never call each other directly.
4. **Quarkus CDI** — Use `@ApplicationScoped` + `@Inject`. No manual module registration.
5. **Thread Safety** — Tasks are shared across conversations. Never store conversation data in instance fields.

### New Feature Checklist

A new `ILifecycleTask` requires ALL of:

- [ ] Configuration POJO (`*Configuration.java`, use records)
- [ ] Store interface (`IResourceStore<T>`)
- [ ] MongoDB store (`@ApplicationScoped`, `@ConfigurationUpdate`)
- [ ] REST interface (JAX-RS, extends `IRestVersionInfo`)
- [ ] REST implementation (`@ApplicationScoped`)
- [ ] `ExtensionDescriptor` (UI field definitions)
- [ ] Unit test with Mockito

### Code Patterns

- **Null safety**: Always check `getLatestData()` for null before `.getResult()`
- **Metrics**: Use Micrometer (`MeterRegistry`) for execution tracking
- **Security**: Validate URLs with `UrlValidationUtils`, use `SafeMathParser` not `ScriptEngine`
- **Logging**: JBoss Logger, never System.out
- **Template data**: Use `IMemoryItemConverter.convert(memory)` for template objects
- **Pre/Post**: Use `PrePostUtils` for pre-request and post-response processing

### Key Files

| File                                        | Purpose                                                   |
| ------------------------------------------- | --------------------------------------------------------- |
| `src/main/resources/application.properties` | Quarkus config (CORS, health, OpenAPI, MongoDB)           |
| `src/main/resources/initial-bots/`          | Bot Father and sample bot configs                         |
| `.circleci/config.yml`                      | Current CI (migrating to GitHub Actions)                  |
| `docs/`                                     | 40 markdown files, published at docs.labs.ai              |
| `docs/v6-planning/`                         | Architecture analysis, changelog, business logic analysis |
| `docker-compose.yml`                        | EDDI + MongoDB local setup                                |

---

## 5. Handoff Protocol

**If picking up from a previous session:**

1. Read `HANDOFF.md` — it has the current status, what's done, and what's next
2. Run `git log -5 --oneline` on `feature/version-6.0.0` to see recent commits
3. Run `git status` to check for uncommitted changes
4. Check which phase/item from Section 3 is currently in progress

**If ending a session (or at a natural break point):**

1. Commit all working code (even partial) with `wip:` prefix if incomplete
2. Update `HANDOFF.md` with:
   - What was completed (with commit hashes)
   - What's next (the specific Phase/Item from Section 3)
   - Any open questions or decisions needed
3. **Tell the user it's a good time for a new conversation** if:
   - A phase is complete
   - A major item (3+ SP) is done and tests pass
   - Context is getting long (many files explored)
