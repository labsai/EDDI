import { api } from "../api-client";

export interface AuthEventSourceOptions {
  onMessage?: (event: { type: string; data: string }) => void;
  onError?: (error: Error) => void;
  onOpen?: () => void;
  signal?: AbortSignal;
}

export interface AuthEventSourceHandle {
  close: () => void;
}

/**
 * Parse one SSE frame — the text between blank-line separators — into its event
 * type and data payload.
 *
 * Currently used by `sendMessageStreaming` in `chat.ts`. There are three other
 * SSE readers in this repo and they do NOT all follow the rules below — inventory
 * so the next person does not have to rediscover it:
 *
 *  - `createAuthEventSource` (below) — incremental, line-based. Appends `data:`
 *    lines, strips `\r`, honours the optional-space rule. Close, but NOT
 *    equivalent on two inputs: it dispatches only when `eventData` is non-empty,
 *    so a frame carrying just `event: done` (no data) is dropped where this
 *    returns it; and because it accumulates across dispatches it treats a leading
 *    empty `data:` line differently. Do not swap one for the other blind.
 *  - `BearerEventSource` (`src/lib/bearer-event-source.ts`) — incremental.
 *    Appends and strips `\r`, but uses `trimStart()` on the payload, so it drops
 *    more than the single optional space and does not preserve leading runs.
 *  - `readGroupSSE` (`src/lib/api/groups.ts`) — frame-based, and the one worth
 *    migrating. It appends `data:` lines correctly (it cites the spec) but
 *    `.trim()`s each one, losing meaningful whitespace, and normalises CRLF per
 *    decoded chunk, which misses a `\r\n` straddling a chunk boundary. It also
 *    drops any frame without an explicit `event:` line.
 *
 * If you change the rules here, reconcile them with those three rather than
 * assuming they match.
 *
 * It follows the WHATWG spec on the three points that are easy to get wrong and
 * that a hand-rolled parser reliably gets wrong:
 *
 *  - **Multiple `data:` lines in one frame concatenate with "\n".** They do not
 *    overwrite. Assigning instead of appending silently truncates every
 *    multi-line payload to its final line.
 *  - **Only the single optional space after the colon is stripped.** Calling
 *    `.trim()` destroys leading and trailing whitespace, which corrupts token
 *    streams where a lone " " is a meaningful chunk.
 *  - **A trailing "\r" from CRLF framing is removed** before the value is read.
 *
 * Returns `null` for a frame carrying no field lines (a `:` heartbeat comment,
 * or blank padding), so callers can simply skip it.
 */
export function parseSseFrame(
  frame: string,
  defaultEventType = "message",
): { type: string; data: string } | null {
  let type = defaultEventType;
  const dataLines: string[] = [];
  let sawField = false;

  for (const rawLine of frame.split("\n")) {
    const line = rawLine.endsWith("\r") ? rawLine.slice(0, -1) : rawLine;
    if (line.startsWith(":")) continue; // comment / heartbeat
    if (line.startsWith("event:")) {
      // Same optional-space-only rule as `data:` — the previous `.trim()` here
      // contradicted the contract documented above. An explicitly empty
      // `event:` reverts to the caller's default rather than yielding type "",
      // which no consumer switch has a case for.
      const value = line[6] === " " ? line.slice(7) : line.slice(6);
      type = value === "" ? defaultEventType : value;
      sawField = true;
    } else if (line.startsWith("data:")) {
      dataLines.push(line[5] === " " ? line.slice(6) : line.slice(5));
      sawField = true;
    }
  }

  if (!sawField) return null;
  return { type, data: dataLines.join("\n") };
}

/**
 * Create an auth-aware SSE stream using fetch + ReadableStream.
 * Unlike native EventSource, this supports Authorization headers.
 */
export function createAuthEventSource(
  path: string,
  options?: AuthEventSourceOptions,
): AuthEventSourceHandle {
  const url = `${api.getBaseUrl()}${path}`;
  const abort = new AbortController();

  // Link external signal to our abort controller
  if (options?.signal) {
    if (options.signal.aborted) {
      abort.abort();
    } else {
      options.signal.addEventListener("abort", () => abort.abort(), { once: true });
    }
  }

  (async () => {
    try {
      const response = await fetch(url, {
        headers: {
          Accept: "text/event-stream",
          ...api.getAuthHeader(),
        },
        signal: abort.signal,
      });

      if (!response.ok || !response.body) {
        options?.onError?.(
          new Error(`SSE connection failed: ${response.status}`),
        );
        return;
      }

      options?.onOpen?.();

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      let eventType = "message";
      let eventData = "";

      while (true) {
        const { done, value } = await reader.read();
        if (done) {
          // Process any remaining data in the buffer before disconnecting.
          // If the server closed without a trailing newline, the final event
          // would otherwise be silently lost.
          if (buffer) {
            const trimmed = buffer.replace(/\r$/, "");
            if (trimmed.startsWith("event:")) {
              const payload = trimmed[6] === " " ? trimmed.slice(7) : trimmed.slice(6);
              eventType = payload.trim();
            } else if (trimmed.startsWith("data:")) {
              const payload = trimmed[5] === " " ? trimmed.slice(6) : trimmed.slice(5);
              eventData += (eventData ? "\n" : "") + payload;
            }
          }
          if (eventData) {
            options?.onMessage?.({ type: eventType, data: eventData });
          }

          // Treat a normal stream end as a disconnect so callers can reconnect.
          if (!abort.signal.aborted) {
            options?.onError?.(new Error("SSE connection closed"));
          }
          break;
        }

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        buffer = lines.pop() ?? "";

        for (const line of lines) {
          const trimmed = line.replace(/\r$/, "");
          if (trimmed.startsWith("event:")) {
            // SSE spec: space after colon is optional
            const payload = trimmed[6] === " " ? trimmed.slice(7) : trimmed.slice(6);
            eventType = payload.trim();
          } else if (trimmed.startsWith("data:")) {
            // SSE spec: space after colon is optional
            const payload = trimmed[5] === " " ? trimmed.slice(6) : trimmed.slice(5);
            eventData += (eventData ? "\n" : "") + payload;
          } else if (trimmed === "") {
            if (eventData) {
              options?.onMessage?.({ type: eventType, data: eventData });
              eventType = "message";
              eventData = "";
            }
          }
        }
      }
    } catch (e) {
      if (e instanceof DOMException && e.name === "AbortError") return;
      options?.onError?.(e instanceof Error ? e : new Error(String(e)));
    }
  })();

  return { close: () => abort.abort() };
}
