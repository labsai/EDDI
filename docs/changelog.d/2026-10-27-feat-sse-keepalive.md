## SSE keep-alive on turn streams (2026-10-27)

**Repo:** EDDI · **Branch:** `feat/sse-keepalive`

**Problem.** A turn can go a minute without a single SSE event: a model that is working out a tool call, or a structured
(JSON) reply, emits no tokens until it is done. Load balancers and proxies close a connection that carries no bytes for
their idle timeout — 60 s on an AWS ALB by default — and with `eddi.streaming.cancel-on-client-disconnect` on (the
default), that close also discards the turn. Seen in a production-like deployment on 2026-10-27: a turn spent about
50 s before its first action, and the slowest ones run past 60 s.

**Change.** `RestAgentEngineStreaming` writes an SSE comment line, `: keep-alive`, on a turn stream that has carried
nothing for `eddi.streaming.keepalive-interval-seconds` (default `15`, `0` turns it off).

- One daemon thread (`eddi-sse-keepalive`) ticks every open turn stream at the interval; a tick writes only when the
  stream has been silent for the whole interval, so a stream of tokens carries no extra frames.
- Writes to the sink are serialised, so a comment never lands between the lines of an event.
- The tick stops when the stream ends (`done`, `error`, start failure) or the client is gone.
- A tick that finds the sink closed is handled like any send to a vanished client: with
  `cancel-on-client-disconnect` on, the turn is cancelled during the silent stretch instead of at its next event. A
  send that fails on a sink that is still open is not taken for a disconnect, as before.
- SSE clients ignore comment lines by definition, so no consumer changes. `docs/rest-api-reference.md` notes that a
  hand-written parser must skip lines starting with `:`.

**Files.** `RestAgentEngineStreaming.java`, new `RestAgentEngineStreamingKeepAliveTest` (8 tests, the scheduler and
clock replaced so each tick runs by hand), [`configuration-reference.md`](../configuration-reference.md),
[`rest-api-reference.md`](../rest-api-reference.md).

**Decision.** Comment frames rather than a named `ping` event: a named event reaches every client's handler and some
treat unknown events as errors, while comments are dropped by every SSE parser. The interval is a setting because the
right value depends on the shortest idle timeout between EDDI and the client.
