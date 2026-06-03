/**
 * A drop-in replacement for the browser's EventSource that uses fetch+ReadableStream
 * so it can include custom HTTP headers (e.g. Authorization: Bearer ...).
 *
 * The native EventSource API has no way to set custom headers, which means
 * authenticated SSE streams fail with 401 when the backend requires bearer tokens.
 * This class exposes the same interface used by the callers in this codebase
 * (addEventListener, onmessage, onerror, onopen, close) while using fetch internally.
 */

type EventHandler = (event: MessageEvent) => void;

export class BearerEventSource {
  private abortController: AbortController | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private listeners: Map<string, EventHandler[]> = new Map();
  private _closed = false;

  onmessage: EventHandler | null = null;
  onerror: (() => void) | null = null;
  onopen: (() => void) | null = null;

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
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.abortController?.abort();
    this.abortController = null;
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

      while (!this._closed) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        // SSE blocks are separated by double newlines
        const blocks = buffer.split("\n\n");
        buffer = blocks.pop() ?? "";
        for (const block of blocks) {
          const msg = this.parseBlock(block.trim());
          if (msg) this.dispatch(msg);
        }
      }
      // Clean EOF — reconnect to keep streaming
      if (!this._closed) this.scheduleReconnect();
    } catch (err) {
      if (this._closed) return; // intentional abort
      if (err instanceof DOMException && err.name === "AbortError") return;
      this.scheduleReconnect();
    }
  }

  private scheduleReconnect(): void {
    this.onerror?.();
    if (!this._closed) {
      this.reconnectTimer = setTimeout(() => this.connect(), 5000);
    }
  }

  private parseBlock(block: string): MessageEvent | null {
    if (!block) return null;
    let eventType = "message";
    const dataParts: string[] = [];
    for (const line of block.split("\n")) {
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
    // onmessage gets ALL events (mirrors native EventSource behaviour)
    this.onmessage?.(event);
  }
}
