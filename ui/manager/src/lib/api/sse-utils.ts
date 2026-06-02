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
          if (trimmed.startsWith("event: ")) {
            eventType = trimmed.slice(7).trim();
          } else if (trimmed.startsWith("data: ")) {
            eventData += (eventData ? "\n" : "") + trimmed.slice(6);
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
