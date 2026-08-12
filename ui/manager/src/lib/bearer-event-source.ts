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
    private readonly headers: Record<string, string> = {}
  ) {
    this.connect();
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
    this.abortController = new AbortController();
    try {
      const response = await fetch(this.url, {
        headers: { Accept: "text/event-stream", ...this.headers },
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
      let receivedData = false;

      this.resetInactivityTimer();

      while (!this._closed) {
        const { done, value } = await reader.read();
        this.resetInactivityTimer();
        if (done) break;

        // Refill the retry budget only once the stream has actually produced
        // bytes — NOT when the response headers arrive.
        //
        // Resetting on headers looked equivalent and was not: a server that
        // answers 200 and closes the body immediately would reset the counter,
        // fall out of this loop, and schedule another attempt at zero. That
        // reinstates the unbounded 5s retry loop the attempt cap exists to
        // prevent, just behind a success status. Bytes on the wire are the
        // earliest point at which the connection is demonstrably working; EDDI's
        // log stream sends a heartbeat comment every 15s, so a genuinely healthy
        // idle stream still refills promptly.
        if (!receivedData) {
          receivedData = true;
          this.reconnects.reset();
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
        for (const block of blocks) {
          const msg = this.parseBlock(block.trim());
          if (msg) this.dispatch(msg);
        }
      }

      // Flush remaining buffer — normalize any held-back trailing \r
      if (buffer.length > 0) {
        buffer = buffer.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
        const blocks = buffer.split("\n\n");
        for (const block of blocks) {
          const trimmed = block.trim();
          if (trimmed) {
            const msg = this.parseBlock(trimmed);
            if (msg) this.dispatch(msg);
          }
        }
      }

      this.clearInactivityTimer();
      // Clean EOF — reconnect to keep streaming
      if (!this._closed) this.scheduleReconnect();
    } catch {
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
    const handlers = this.listeners.get(event.type);
    if (handlers) handlers.forEach((fn) => fn(event));
    // onmessage fires ONLY for the default "message" type — this mirrors native
    // EventSource semantics. Named events (with an `event:` field) are delivered
    // exclusively to their addEventListener handlers, so a caller that registers
    // both addEventListener("log", fn) and onmessage = fn (as an unnamed-event
    // fallback) does not receive named "log" events twice.
    if (event.type === "message") this.onmessage?.(event);
  }
}
