# What to Build Before You Let AI Agents Act Alone

*Inside EDDI 6.2: agents that stop and ask, and a workspace built for the person answering.*

---

EDDI can now stop an agent mid-flight and wait for a human. The agent suspends at the exact point where a person needs to weigh in, and picks up from that same point once someone decides. The person can approve, reject, or rewrite what the agent was about to do.

The previous article ended with a promise: an agent that can hire other agents needs a human who can overrule it. 6.2 keeps that promise, then spends the rest of the release making sure that human has decent working conditions.

It's the largest release since 6.0. One half is the approval machinery. The other half is **Workforce**, a new workspace built for the humans who work with agents rather than the ones who configure them.

(New here? EDDI is an open-source multi-agent orchestration engine. Java, Quarkus, MongoDB or PostgreSQL. Agent behavior lives in JSON configuration, and the engine executes it. The previous article covered how agents learned to create other agents; this one is about staying in charge of them.)

## Where a Human Gets to Say No

There are three gates, sharing one pause, timeout, and audit mechanism underneath.

**Before a whole turn.** A behavior rule emits `PAUSE_CONVERSATION`, and the rest of the pipeline stops until someone approves. Use it when the topic is sensitive: a refund above a threshold, a medical question, anything where a person should read the exchange before the agent answers.

**Before a single tool call.** This is the gate that changes how much autonomy you can safely grant. You declare which tools need sign-off by pattern, and when the model decides to call a matching one, the conversation pauses *before the call executes*. It works across all seven kinds of tool an EDDI agent can reach: built-in, HTTP, MCP, agent-to-agent, dynamically created, memory, and conversation recall. So an agent can be free to search, summarize, and reason all day, and still be unable to issue a refund, send an email, or write to a production system without a human in the path.

The detail I like most: the approver can **amend the arguments** before the call runs. When an agent has the right idea and the wrong number, you fix the number and let it proceed, instead of rejecting it and coaxing the model toward a better attempt.

**Before a group moves on.** Mark a phase of a multi-agent discussion as requiring approval, and the group waits for a human before advancing, either once per phase or once per task result.

### Four ways to answer

An approval request is useless if it only exists somewhere nobody looks, so a pause surfaces in four places: **Slack**, with buttons and the pending call spelled out; the **REST API**, for wiring into your own tooling; **MCP**, so you can approve from Claude Desktop or Cursor alongside everything else; and the **Manager UI**, which now has an approvals inbox. Groups add a cross-group inbox, so one person can see everything waiting on them.

### Waiting, safely

Pausing an agent creates problems that only show up in production, and this release deals with them explicitly.

Waiting can't be forever, so every gate has a timeout policy: keep waiting, auto-approve, auto-reject, or abort. It's set per task with an agent-level default, and it holds across a multi-pod deployment rather than depending on whichever instance took the request.

Waiting also can't lose work. Approved tool executions are written to a journal before they run, and resume replays the conversation so the model re-enters its reasoning loop at exactly the tool it was on. An agent that was six tool calls deep when it paused doesn't restart from the top.

And waiting has to survive a crash. If EDDI goes down while conversations are parked awaiting approval, a startup process repairs them, in the background so it never delays boot.

One smaller thing that saves real pain: misconfiguration is caught when you save. A near-miss on a reserved action name (`PAUSE_CONVERSATON`) or an approval block that could never match anything is flagged at deploy time, rather than discovered later by an agent that quietly never paused.

## Workforce: A Workspace for People Who Don't Write JSON

Everything above assumes somebody is there to approve. That person is usually not the engineer who wrote the agent's configuration, and until now EDDI only really had a home for the engineer.

**Workforce** is a second workspace beside the admin dashboard, built around agents rather than around configuration. You land on a chooser, pick the workspace you want, and EDDI remembers.

Inside it:

- **One-to-one agent chat** with streaming replies, markdown, file attachments, conversation history, and approvals inline where they belong.
- **Group boards.** Create a multi-agent group through a wizard, then watch a Task Force work through a live task board with its plan and verification steps rendered as structured output rather than a wall of text. Follow up, continue, or close the discussion from the same screen. Pin the groups you use often and save them as templates.
- **An analytics dashboard** where the filters actually drive everything: charts, KPIs, agent leaderboard, plus performance and comparison views.
- **Settings that match the backend**, including human oversight, dynamic agents, resilience, and task definitions, so features don't exist only for people willing to hand-edit JSON.

It's responsive, works right-to-left, and is fully translated across eleven locales, as is the rest of the Manager.

For the engineers, the admin side grew a **debug panel** worth calling out on its own: a pipeline waterfall you can expand task by task, a cost dashboard with per-turn breakdown, a searchable memory inspector, and a prompt viewer that shows what each role actually received. When an agent misbehaves, that's the difference between a theory and an answer.

## What Agents Can Do Now

**Files, properly.** 6.1 let agents see images. 6.2 finishes the job. Attachments arrive as an uploaded blob, a URL, or base64, under one consistent set of size limits, and are then matched against what the target model can genuinely handle. PDFs go native when the model supports documents, and fall back to extracted text inlined into the prompt when it doesn't, so a PDF is useful on every model rather than only the expensive ones. Text-like files (JSON, XML, CSV, YAML, plain text) always inline.

A new `readAttachment` tool lets an agent go back to a file it saw several turns ago. Attachments reach every member of a group, survive an approval pause, and can be shared across conversations through explicit grants rather than by loosening access. And nothing fails silently: every size cap, storage failure, or unsupported file is recorded and left where the model can tell the user what happened.

**Multi-agent discussions have a lifecycle.** A group discussion used to be a single shot. Now it's a thread you come back to: ask one member a follow-up question by name or ID, re-run the whole group with a new question while the agents keep the memory of earlier rounds, and explicitly close the discussion when it's done, which shuts down member conversations and cleans up any agents that were created for it. Every response tells the client which of those actions are currently available, so tools built on top don't have to guess.

**The model cascade is ready for a finance review.** Cascading means trying a cheap model first and escalating to an expensive one only when confidence is too low, and its whole value proposition is cost. In 6.2 that claim became measurable. The audit trail records which model actually produced each answer, along with its token usage and cost, so you can reconstruct any given response after the fact. You can set hard ceilings on wall-clock time and dollars per run, after which escalation stops and you get the best answer so far.

There's a real judge-model option for scoring confidence, live events as the cascade escalates, and metrics covering escalation reasons, accepted step, latency, and spend. The final step now streams token by token instead of making the user wait for a buffered response.

## When Things Go Wrong

Model providers have bad days. So do HTTP APIs and MCP servers. Most of this release's reliability work is about a hiccup costing you one turn instead of a whole conversation.

There's now a shared retry configuration with exponential backoff and proper classification of what's worth retrying, applied across LLM, HTTP, and MCP calls, and configurable per subsystem. MCP calls can continue on error or trip a circuit breaker instead of failing the turn.

LLM responses are validated against policies you configure, for the three ways a response goes wrong in practice: empty, truncated, or filtered by the provider. A streaming response that dies before producing a token is retried, a partial response comes back labeled as partial, and an interrupted stream is finally treated as the failure it is rather than a success. HTTP error bodies are kept in memory, so an agent can read a 422 and react to it instead of only knowing that something broke.

When a conversation does get stuck, an admin endpoint resets its state. Failures produce audit entries, live events, and metrics tagged by error type, so they show up on a dashboard rather than in a support ticket.

## Ceilings That Hold

A budget that cannot stop spending is a report, not a budget. Three of them became real.

The in-turn tool context has a ceiling now (`maxToolContextTokens`, default 60,000). Long tool-heavy turns used to grow their context until the model or your invoice complained; the oldest tool exchanges are now dropped, and the log tells you which setting to raise.

Per-conversation budget limits actually bind, because built-in tools are finally priced and rate-limited under their real names. A ceiling that cannot take effect now warns you at deploy instead of sitting there looking reassuring.

Tenant limits are enforced where they're checked. The cap on agents per tenant was stored, editable through the API, and read by nothing; it's enforced on deploy now, counting both persisted deployments and live agents so the two can't disagree. Quota denials return `429` rather than a `500`, and all the counters live in one place instead of several that could drift apart.

The audit ledger is worth one line of its own: it now records real token counts, real tool-call counts, and real costs. If you're keeping it for EU AI Act purposes, the numbers in it are numbers.

## Two Things to Know Before You Upgrade

**Tool results are now cached per user.** The tool-result cache used to key only on the tool name and its arguments, which means two different users making a byte-identical call could share a cached result. Cache entries are now scoped to the caller by default, fall back to the conversation, and bypass the cache entirely rather than share a bucket when there's no identity to key on. The practical effect on upgrade: your cache hit rate will fall and your tool spend will rise, in proportion to how much cross-user reuse was quietly happening before. Where a result genuinely doesn't depend on who asked, such as a public weather lookup, you can opt that specific tool back into a shared cache. No config migration is required.

**Timestamps on the REST API are ISO-8601 strings.** They were epoch numbers that any client doing the obvious thing with them rendered as 1970. Stored data is untouched, and the bundled Manager already matches.

## The Numbers

12 LLM providers · 77 MCP tools · 6 group discussion styles plus custom · 8 embedding providers · 6 vector stores · 12,000+ tests across 725 test files · 90% instruction / 80% branch coverage, enforced by the build · 11 locales in both workspaces · OpenSSF Best Practices Gold, one of 84 · OpenSSF Scorecard 9.7/10 · Red Hat certified image · MongoDB or PostgreSQL with one environment variable · Apache 2.0.

## Community

Four people outside the core landed changes in this cycle. **Alex Bevilacqua** added MongoDB driver handshake metadata, so an EDDI deployment now identifies itself properly to the database, which is exactly the kind of operational courtesy you only notice when it's missing. **nightcityblade** kept up a steady run of careful fixes. **knightxiaoxi** cleaned up logger naming, and **Joe-You-Know** fixed task-type names in the docs, which is the sort of contribution that saves the next person an hour of confusion.

Thank you, all of you. Building in the open keeps being the right call.

---

An agent that can act needs somewhere for a person to stand. That's what 6.2 builds. The agents got their autonomy in the last release; this one is for the humans.

---

*EDDI is Apache 2.0 and lives at [github.com/labsai/EDDI](https://github.com/labsai/EDDI). One command installs the whole stack:*

```
curl -fsSL https://raw.githubusercontent.com/labsai/EDDI/main/install.sh | bash
```

*If you open a pull request, it gets the same review discipline the badge demands. Consider that a feature.*
