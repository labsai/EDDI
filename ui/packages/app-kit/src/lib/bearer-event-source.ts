/**
 * A drop-in replacement for the browser's EventSource that uses fetch+ReadableStream
 * so it can include custom HTTP headers (e.g. Authorization: Bearer ...).
 *
 * The native EventSource API has no way to set custom headers, which means
 * authenticated SSE streams fail with 401 when the backend requires bearer tokens.
 * This class exposes the same interface used by the callers in this codebase
 * (addEventListener, onmessage, onerror, onopen, close) while using fetch internally.
 */

import { createReconnectScheduler } from "./sse-reconnect";

type EventHandler = (event: MessageEvent) => void;

/**
 * The headers for one connection attempt — a fixed map, or a function asked
 * again on EVERY (re)connect.
 *
 * Pass a function for anything carrying a bearer token. A map is read once, at
 * construction, so a stream opened with a token that has since been refreshed
 * reconnects with the expired one, gets a 401 on every attempt, spends its retry
 * budget and stops: the live log and coordinator streams went dead roughly one
 * token lifetime after the page was opened.
 */
export type HeaderSource = Record<string, string> | (() => Record<string, string>);

/**
 * How long a connection must have stayed up to count as healthy when no real
 * event came (a quiet stream that only sends heartbeats).
 */
export const BEARER_MIN_HEALTHY_UPTIME_MS = 30_000;

export class BearerEventSource {
  private abortController: AbortController | null = null;
  private inactivityTimer: ReturnType<typeof setTimeout> | null = null;
  private listeners: Map<string, EventHandler[]> = new Map();
  private _closed = false;
  private readonly reconnects = createReconnectScheduler(() => this.connect());

  onmessage: EventHandler | null = null;
  onerror: (() => void) | null = null;
  onopen: (() => void) | null = null;
  /**
   * Fired once the retry budget is spent and this source has stopped trying.
   *
   * Distinct from `onerror`, which fires on every failed attempt. A consumer
   * that shows "reconnecting…" needs to know when that stops being true —
   * otherwise a permanently refused stream (a 403 from `/administration/logs`
   * for a user without `eddi-admin`) shows a spinner forever.
   */
  onexhausted: (() => void) | null = null;

  constructor(
    private readonly url: string,
    private readonly headers: HeaderSource = {}
  ) {
    this.connect();
  }

  private currentHeaders(): Record<string, string> {
    return typeof this.headers === "function" ? this.headers() : this.headers;
  }

  addEventListener(type: string, listener: EventHandler): void {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type)!.push(listener);
  }

  close(): void {
    this._closed = true;
    this.reconnects.cancel();
    if (this.inactivityTimer !== null) {
      clearTimeout(this.inactivityTimer);
      this.inactivityTimer = null;
    }
    this.abortController?.abort();
    this.abortController = null;
  }

  private clearInactivityTimer(): void {
    if (this.inactivityTimer !== null) {
      clearTimeout(this.inactivityTimer);
      this.inactivityTimer = null;
    }
  }

  private resetInactivityTimer(): void {
    this.clearInactivityTimer();
    if (this._closed) return;
    this.inactivityTimer = setTimeout(() => {
      if (this.abortController) {
        this.abortController.abort();
      }
    }, 45000);
  }

  private async connect(): Promise<void> {
    if (this._closed) return;
    // A connection still open (a reconnect that fired while the previous attempt
    // is alive) is ended first: its reader would otherwise run on, delivering the
    // same events twice. It is told apart from a failure by `isCurrent` below.
    this.abortController?.abort();
    this.abortController = new AbortController();
    const controller = this.abortController;
    const isCurrent = () => this.abortController === controller;
    try {
      const response = await fetch(this.url, {
        headers: { Accept: "text/event-stream", ...this.currentHeaders() },
        signal: this.abortController.signal,
      });

      if (!response.ok || !response.body) {
        this.scheduleReconnect();
        return;
      }

      this.onopen?.();

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      const openedAt = Date.now();
      let refilled = false;
      // Refill the retry budget once the connection has proven itself: a real event
      // (not a comment — a server that sends one heartbeat and drops would
      // otherwise reset the budget on every attempt and be retried every five
      // seconds for good), or an uptime of BEARER_MIN_HEALTHY_UPTIME_MS.
      const refill = () => {
        if (refilled) return;
        refilled = true;
        this.reconnects.reset();
      };
      const deliver = (blocks: string[]) => {
        for (const block of blocks) {
          const msg = this.parseBlock(block.trim());
          if (!msg) continue;
          this.dispatch(msg);
          refill();
        }
      };

      this.resetInactivityTimer();

      try {
        while (!this._closed && isCurrent()) {
          const { done, value } = await reader.read();
          this.resetInactivityTimer();
          if (done) {
            // The held-back trailing CR can no longer be half of a CRLF: it ends a
            // line, and may complete the last block. Only COMPLETE blocks are
            // delivered; the unterminated tail is dropped (below).
            buffer = (buffer + decoder.decode()).replace(/\r\n/g, "\n").replace(/\r/g, "\n");
            const complete = buffer.split("\n\n");
            complete.pop();
            deliver(complete);
            break;
          }

          const chunk = decoder.decode(value, { stream: true });
          buffer += chunk;

          // Normalize CRLF on the accumulated buffer (not per-chunk, because
          // \r\n may be split across two reader.read() calls). Hold back a
          // trailing lone \r — it may be the first half of \r\n in the next chunk.
          buffer = buffer.replace(/\r\n/g, "\n").replace(/\r(?!$)/g, "\n");

          // SSE blocks are separated by double newlines
          const blocks = buffer.split("\n\n");
          buffer = blocks.pop() ?? "";

          deliver(blocks);
        }
      } finally {
        if (Date.now() - openedAt >= BEARER_MIN_HEALTHY_UPTIME_MS) refill();
        // However the loop ended — EOF, close(), a newer connection, a throw — the
        // body is released, so a failing listener cannot leave the reader open.
        void reader.cancel().catch(() => undefined);
      }

      // What is left in the buffer is an event the connection dropped in the
      // middle of. Per the SSE spec an unterminated event is never dispatched:
      // delivering half a message (a truncated JSON payload) is worse than
      // losing it, and the reconnect brings the next whole one.
      this.clearInactivityTimer();
      // Clean EOF — reconnect to keep streaming (unless close() or a newer
      // connection took over)
      if (!this._closed && isCurrent()) this.scheduleReconnect();
    } catch {
      // Superseded by a newer connection: its timer is not ours to clear.
      if (!isCurrent()) return;
      this.clearInactivityTimer();
      if (this._closed) return; // intentional abort
      this.scheduleReconnect();
    }
  }

  /**
   * Report the failure, then queue a retry on the shared policy.
   *
   * Bounded on purpose. This used to be an unconditional
   * `setTimeout(connect, 5000)` with no attempt counter, so a stream the backend
   * will never serve was re-requested every five seconds for the whole session.
   */
  private scheduleReconnect(): void {
    if (this._closed) return;
    this.onerror?.();
    if (!this.reconnects.schedule()) {
      this.onexhausted?.();
    }
  }

  private parseBlock(block: string): MessageEvent | null {
    if (!block) return null;
    let eventType = "message";
    const dataParts: string[] = [];
    for (let line of block.split("\n")) {
      if (line.endsWith("\r")) line = line.slice(0, -1);
      
      if (line.startsWith("event:")) {
        eventType = line.slice(6).trim();
      } else if (line.startsWith("data:")) {
        dataParts.push(line.slice(5).trimStart());
      }
    }
    if (dataParts.length === 0) return null;
    return new MessageEvent(eventType, { data: dataParts.join("\n") });
  }

  private dispatch(event: MessageEvent): void {
    // Named-event listeners
    // Each listener on its own: one that throws must not skip the others, nor
    // end the read loop (and with it the connection) for every other consumer.
    const handlers = this.listeners.get(event.type);
    if (handlers) handlers.forEach((fn) => this.safely(() => fn(event)));
    // onmessage fires ONLY for the default "message" type — this mirrors native
    // EventSource semantics. Named events (with an `event:` field) are delivered
    // exclusively to their addEventListener handlers, so a caller that registers
    // both addEventListener("log", fn) and onmessage = fn (as an unnamed-event
    // fallback) does not receive named "log" events twice.
    if (event.type === "message") this.safely(() => this.onmessage?.(event));
  }

  private safely(call: () => void): void {
    try {
      call();
    } catch (error) {
      console.error("[BearerEventSource] a listener threw:", error);
    }
  }
}
