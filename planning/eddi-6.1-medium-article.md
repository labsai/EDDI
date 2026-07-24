# What to Build Before You Let AI Agents Create AI Agents

*EDDI 6.1: three releases, 417 commits, a badge only 84 projects hold, and the first version where the agent roster moves at runtime.*

---

Until June, an EDDI agent roster was fixed at configuration time. You declared your agents in JSON, deployed them, and that was the cast. Not anymore. An agent working on a task can now create a specialist that doesn't exist yet, delegate to it, and dismiss it when the work is done.

If your first reaction is that this sounds like a bad idea, good. Mine too. A lot of EDDI 6.1 exists so that this one feature could ship without anyone having to be brave.

(New here? EDDI is an open-source multi-agent orchestration engine. Java, Quarkus, MongoDB or PostgreSQL. Agent behavior lives in JSON configuration, and the engine executes it. The April article covers the backstory; this one picks up where it left off.)

## The Shape of the Release

- **6.1.0 (June 3)** brought the new capabilities, and the trust groundwork underneath them.
- **6.1.1 (June 23)** hardened the operational edges and took EDDI to OpenSSF Gold.
- **6.1.2 (June 28)** cashed it all in on one feature: agents that create, recruit, and dismiss other agents.

Capabilities, discipline, payoff. Letting an LLM spawn agents is a party trick without quotas, allow-lists, and lifecycle rules. With them, it's an architecture. The principle from the April piece still holds, that the engine is strict so the AI can be creative. 6.1 is about what "strict" has to mean once the AI can hire.

## The Capabilities: What Agents Can Do Now

**Bring your own RAG stack.** ChromaDB joined pgvector, MongoDB Atlas, Elasticsearch, Qdrant, and the in-memory store as the sixth fully supported vector database, and Gemini embeddings became the eighth embedding provider. Retrieval in EDDI is configuration: pick the vector store and the embedding model per knowledge base, and switch either without touching agent logic. If your team has already standardized on a vector database, EDDI meets you there instead of asking you to migrate.

**Agents can see.** Conversations accept file attachments: images, PDFs, audio, video, up to 20MB. Images are forwarded to multimodal models natively, so an agent can look at the screenshot a user sends instead of apologizing for being text-only. File types are verified by reading the actual bytes, and every access checks conversation ownership. There is no path to another conversation's files.

**Agents where your team already talks.** The Slack integration was rebuilt as a first-class system of its own, decoupled from agent configuration. One channel can host several agents or whole agent groups, routed by deterministic triggers (`architect: how would you scale this?`); a thread locks to the target of its first message, so conversations don't switch voices midway; and direct messages work. Connecting a new channel is configuration, not a deployment, and legacy setups migrate automatically.

**One switch for the whole fleet.** Global Variables let you change the model, an endpoint, or a temperature once, and every agent picks it up without a redeploy. Provider outage, price hike, new model worth trying? Flip `{{vars.default-model}}` and move on.

**Memory that curates itself.** Dream consolidation compresses a user's accumulated memories into fewer, denser entries during background cycles, under a hard cost ceiling, with an insert-before-delete guarantee: garbage output means the originals stay untouched. Agents recall what matters instead of wading through everything they were ever told.

## The Groundwork: What an Agent Needs Before You Trust It

The rest of 6.1.0 demos less well, but it's what lets you say yes to everything above, and to what came three weeks later. A checklist of what an agent needs before you hand it autonomy: restraint, frugality, discovery, an undo button, and papers.

**Restraint.** Dial how carefully an agent behaves per environment without rewriting its prompts: behavioral counterweights inject a safety instruction into the system prompt at one of three levels, from `normal` (nothing) to `strict` (one step at a time, confirm before acting). The same agent can run permissive in development and strict in production. One detail I like: an agent tagged `scheduled` automatically downgrades strict to cautious, because "confirm before every step" is destructive for a cron job nobody is watching.

**Frugality.** Give an agent sixty tools and all sixty schemas used to ride along in the context window before the user said a word. With lazy tool loading, the model starts with one meta-tool, `discover_tools`, and pulls in real definitions as it needs them. Your token bill drops and the window stays free for the actual conversation. Oversized tool responses get the same discipline: truncate, paginate, or summarize through a cheaper model, with a cost ceiling so the summarizer can't become the expensive part.

**Discovery.** Agents publish skills and find each other by skill, not by hardcoded ID, so you can swap a specialist without touching the agents that call it. When several agents match, deterministic selection strategies decide which one answers.

**An undo button.** Conversations can be checkpointed and restored. When an agent talks itself into a corner, you rewind to before the wrong turn instead of throwing the session away.

**Papers.** When agents talk to agents across platforms, you eventually need to prove who said what. Agents can sign their contributions: Ed25519 signatures, replay protection, versioned keys so rotation doesn't need a flag day. Transcripts become tamper-evident. Signing also fails safe: a signature that can't self-verify is discarded and the entry stored unsigned, rather than persisting something cryptographically broken.

## The Unglamorous Middle

6.1.1 is the release you only appreciate when something goes wrong.

**Losing a vault master key is no longer fatal.** Envelope encryption has a sharp edge: change the master key and the tenant's data-encryption key can't be decrypted, ever again. There used to be no way out. Now a tenant reset lets an operator wipe and re-enter secrets instead of being locked out forever, and the error explains the recovery options instead of offering a stack trace and good luck.

**Spending ceilings are enforced, hard.** When a tenant hits its cost ceiling, everything stops immediately, whatever the group's failure policy says. A cost ceiling that doesn't stop spending isn't a ceiling. It's a dashboard.

**The MCP control plane became dependable.** You can manage all of EDDI, agents, conversations, configurations, from Claude Desktop, Cursor, or any MCP client. 6.1.1 put that surface through heavy real-world use, and a batch of fixes later it holds up as a control plane, not a demo.

**And the API explorer became navigable.** Roughly fifty REST interfaces, organized into nine categories with descriptions and a proper dark mode. Finding an endpoint is a browse, not a scroll of doom.

## One of 84

In June, EDDI reached Gold on the OpenSSF Best Practices badge. As I write this, 11,176 projects are registered with the program and eighty-four hold Gold. The company at that tier is the Linux kernel, curl, Jaeger. Alongside it, EDDI's OpenSSF Scorecard sits at 9.7 out of 10, up from the 8.4 I quoted in April.

Here's the thing nobody tells you about Gold: most of it isn't a security control, it's proof of process. Review on every change. A reproducible build. A published security policy with a real response path. Signed release artifacts. And a hard coverage bar, which is where the work went: the test suite grew from about 5,500 tests to more than 9,000 in a single release, and the build now fails below 90% instruction and 80% branch coverage. Not a number in a README. A build that fails.

That test count is not a brag. It's an invoice. It's what the bar costs.

Around the badge sits the supply chain: CodeQL on every push, Trivy blocking on critical CVEs, secret scanning over the full git history, continuous fuzzing of the input parsers, and every build shipping an SBOM, SLSA provenance, and a keyless cosign signature on a Red Hat certified, digest-pinned image. If you've ever had to walk an AI platform through a corporate security review, this is the part that's for you: the evidence pack that review demands already exists. You point at it instead of assembling it.

None of it makes EDDI unhackable; no badge does. What it buys is narrower and more valuable. It makes the boring failures structurally hard: an unpinned action, a leaked key, a vulnerable dependency, an unreviewed change. Those stop depending on everyone remembering everything, every time.

## The Payoff: Agents That Build Agents

Which brings us to 6.1.2, and the feature the other two releases were paying for.

Group conversations gained a sixth discussion style: Task Force. The existing five (Round Table, Peer Review, Devil's Advocate, Delphi, Debate) are ways for agents to think together. Task Force is a way for them to work together: PLAN → EXECUTE → VERIFY → SYNTHESIS. A moderator decomposes a goal into a shared task list, agents claim and complete tasks in parallel, and the results get verified and synthesized. Hand it a ready-made task list and it skips planning.

Inside a Task Force, an agent can change its own roster. Four tools:

- `create_sub_agent`: spin up a specialist that doesn't exist yet
- `find_agents_by_capability`: discover one that does
- `converse_with_agent`: delegate to it
- `teardown_agent`: dismiss it

Concretely: a task force working through a data migration hits a subtask that needs SQL tuning, and no SQL agent exists. One of the agents creates one from an allow-listed model, hands over the subtask, folds the answer back into the plan, and dismisses it. The specialist exists exactly as long as it's useful. You don't have to predict every kind of expert your system will ever need. The system staffs itself, within rules you wrote.

And the rules have teeth: provider and model allow-lists, per-discussion creation caps, depth limits, tenant quotas, and a lifecycle policy for created agents (`ephemeral`, `keep-deployed`, `undeploy-only`, or `agent-decides`). For the first time, the cast is not fixed when the curtain goes up. That should worry you. It worried me. It's why every one of those knobs exists.

The important part: the agent's logic is still configuration, and the engine still executes it. An agent can create a colleague, but only from a provider on your list, only within your quota, only inside a lifecycle you defined. The roster moves. The rules don't.

## The Numbers

12 LLM providers · 65 MCP tools · 6 named group discussion styles plus custom · 8 embedding providers · 6 vector stores (pgvector, MongoDB Atlas, Elasticsearch, Qdrant, Chroma, in-memory) · 9,645 tests, zero failures · 90% instruction / 80% branch coverage, enforced by the build · OpenSSF Best Practices Gold, one of 84 · OpenSSF Scorecard 9.7/10 · Red Hat certified image · MongoDB or PostgreSQL with one environment variable · Apache 2.0.

## Community

Some of the best parts of 6.1 arrived in other people's pull requests. The ChromaDB and Gemini embedding support above? Contributed by **niedch**, who followed up with a fix to how extensions declare their task types. **rolandpickl** overhauled the Keycloak integration, so authentication works out of the box instead of needing hand-wired configuration. And since the release, **nightcityblade** has been landing the kind of small, careful fixes that make a repository feel inhabited. Thank you. Building in the open keeps being the right call.

## What's Next

6.2 is already taking shape, and it reads like the natural sequel: if 6.1 made agents autonomous enough to hire each other, 6.2 is about keeping humans in charge of the result.

The Human-in-the-Loop framework has already landed on main. A conversation can pause and wait for a person, either for a whole turn or for a single tool call before it executes, with timeout policies, an audit trail, and approvals straight from Slack. If an agent is about to do something destructive, a human gets to say no first, and the conversation survives the wait.

Behind it, in review: follow-ups and continuation for group conversations, so you can question a finished discussion instead of starting over; an enterprise pass on model cascading, so the audit trail records which model actually answered and the cost savings become measurable; a shared retry layer with exponential backoff across LLM, HTTP, and MCP calls, so a provider hiccup degrades one turn instead of killing the conversation; and unified ownership enforcement across the REST and MCP surfaces.

An agent that can hire other agents needs a human who can overrule it. That's 6.2.

---

*EDDI is Apache 2.0 and lives at [github.com/labsai/EDDI](https://github.com/labsai/EDDI). One command installs the whole stack:*

```
curl -fsSL https://raw.githubusercontent.com/labsai/EDDI/main/install.sh | bash
```

*If you open a pull request, it gets the same review discipline the badge demands. Consider that a feature.*
