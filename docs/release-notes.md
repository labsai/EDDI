# Release Notes

EDDI is released as a container image (`labsai/eddi:<version>`, also published to
Red Hat's registry); there are no binary downloads. Each release has full notes
on its [GitHub release page](https://github.com/labsai/EDDI/releases), and every
change is recorded with its reasoning in the [changelog](changelog.md) — older
months are in the archive linked at the top of that file. How versions and tags
work is described in [Release & Versioning Strategy](release-versioning.md); how
to verify an image's signature, in [Release Signing](release-signing.md).

This page is the index: one entry per release since 6.0, what it was about, and
what to read before upgrading.

## Upgrade guides

| From | Guide |
| --- | --- |
| 6.5.x to 6.6 and 6.7 | [Upgrading from 6.5](upgrading-from-6.5.md) — covers every change since 6.5: authorization defaults, outbound calls, MCP, newly reserved names, and the 6.7 section on cluster hardening and opt-in features |
| 6.4.x to 6.5 | [Upgrading from 6.4](upgrading-from-6.4.md) — read section 2 first: several defaults now fail closed |
| 6.5.x to 6.6 and later | [Upgrading from 6.5](upgrading-from-6.5.md) — authorization defaults and other changes a working 6.5 client notices |
| 5.x to 6.x | [Upgrading from 5.x](upgrading-from-5x.md) — the first 6.x boot migrates the database in place |

Between other 6.x releases there is no separate guide; the breaking changes are
listed in each release's GitHub notes.

## Releases

| Version | Released | Headline | Notes |
| --- | --- | --- | --- |
| **6.7.0** | 2026-11-05 | White-label branding (brand store, Admin → Branding page, brand-aware Chat UI, Operator and Keycloak theme); a hardened, signed multi-node cluster (NATS host-name verification, signed control plane, fencing, fail-loud readiness) with a multi-process E2E suite; group collaboration (team budgets, native cross-team handoff, human tie-breaks, humans as task-force executors, a model-backed convergence judge); a team learning loop (bets on backlog tasks, outcome events, team lessons shared between teams and consolidated within a cost cap) and agent operations tools (team scorecard, budget proposals, follow-ups); an email channel, conversation ratings and alert rules; an opt-in hard tenant ceiling and per-run `toolQuotas`; HITL edit-on-approve, approval analytics with earned autonomy, and tool-call guardrails; cost accounting with a model price catalog; a new-release check that is on by default (opt out with `eddi.update-check.enabled=false`); a review sweep across secrets, SSRF, GDPR, ingestion, backup and the UIs | [GitHub](https://github.com/labsai/EDDI/releases/tag/6.7.0) · [Upgrading from 6.5](upgrading-from-6.5.md) · [changelog](changelog.md) |
| **6.6.0** | 2026-10-20 | Running EDDI at scale and under failure (virtual threads behind an in-flight limit, NATS JetStream scale-out, early `503` on overload, classified retries and circuit breaking, `Idempotency-Key`); every provider parameter for all 19 LLM integrations plus an `openai-compatible` type; RAG ingestion progress and a knowledge-base inspector; input and output guardrails, a per-conversation cost ceiling, a tenant monthly budget and agent test suites; a signed inbound webhook channel and A2A 1.0; broad security hardening | [GitHub](https://github.com/labsai/EDDI/releases/tag/6.6.0) · [Upgrading from 6.5](upgrading-from-6.5.md) · [changelog](changelog.md) |
| **6.5.0** | 2026-10-02 | The Manager and the Chat UI move into this repository (`ui/`) and are built by Maven; knowledge-base sources and an ingestion pipeline for RAG (crawler, uploaded documents, schedules); secret context values; access-token audience validation; MCP advertised as an OAuth protected resource | [GitHub](https://github.com/labsai/EDDI/releases/tag/6.5.0) · [Upgrading from 6.4](upgrading-from-6.4.md) · [changelog](changelog.md) |
| **6.4.0** | 2026-09-15 | Multi-user: per-user workspaces with ownership and sharing (off by default); Connections as one credential model for outbound calls; `/mcp` and `/secretstore` need an explicit opt-in to run unauthenticated; Slack Observe Mode; UBI 10 base image | [GitHub](https://github.com/labsai/EDDI/releases/tag/6.4.0) · [changelog 2026-09](changelog/2026-09.md) |
| **6.3.0** | 2026-08-20 | Group conversations become a collaboration platform (voting, shared artifacts, bid-based tasks, standing teams, facilitator, humans as members, NEGOTIATION style); the Platform Operator replaces the Agent Father; OpenAI-compatible `/v1` API | [GitHub](https://github.com/labsai/EDDI/releases/tag/6.3.0) · [changelog 2026-08](changelog/2026-08.md) |
| **6.2.0** | 2026-07-27 | Human-in-the-loop: turn-level and per-tool-call approval gates with REST, MCP and Slack surfaces; multimodal attachments end to end; group-conversation lifecycle; Workforce workspace in the Manager | [GitHub](https://github.com/labsai/EDDI/releases/tag/6.2.0) · [changelog 2026-07](changelog/2026-07.md) |
| **6.1.2** | 2026-06-28 | TASK_FORCE discussion style: agents create, recruit and delegate to sub-agents at runtime | [GitHub](https://github.com/labsai/EDDI/releases/tag/6.1.2) · [changelog 2026-06](changelog/2026-06.md) |
| **6.1.1** | 2026-06-23 | Hardening: IDOR fixes across user-facing endpoints, coverage gates raised to 90 % instruction / 80 % branch, Swagger UI overhaul | [GitHub](https://github.com/labsai/EDDI/releases/tag/6.1.1) · [changelog 2026-06](changelog/2026-06.md) |
| **6.1.0** | 2026-06-03 | ChromaDB vector store and Gemini embeddings; Global Variable Store (`${vars:…}`); multimodal attachments; LLM-driven memory summarization; reworked channel integrations | [GitHub](https://github.com/labsai/EDDI/releases/tag/6.1.0) · [changelog 2026-06](changelog/2026-06.md) |
| **6.0.2** | 2026-04-23 | Security hardening and quality assurance | [Release notes](release-notes-6.0.2.md) · [changelog 2026-04](changelog/2026-04.md) |
| **6.0.1** | 2026-04-16 | Slack channel connector (preview); Manager refinements | [GitHub](https://github.com/labsai/EDDI/releases/tag/6.0.1) |
| **6.0.0** | 2026-04-14 | General availability of EDDI 6, a ground-up modernisation: Java 25, Quarkus, LangChain4j, MongoDB or PostgreSQL | [GitHub](https://github.com/labsai/EDDI/releases/tag/6.0.0) · [Upgrading from 5.x](upgrading-from-5x.md) |

Dates are the dates the release tags were created. The "Headline" column
summarises the opening of each release's own notes; the GitHub page and the
changelog are authoritative for the details.
