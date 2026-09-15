/* ──────────────────────────────────────────────
   Test harness — SSE streams and status-aware fetch
   ────────────────────────────────────────────── */

/**
 * Build a ReadableStream from raw wire chunks.
 * Chunks are enqueued verbatim, so a caller can deliberately split an SSE
 * frame across chunk boundaries to exercise the parser's buffering.
 */
export function sseStream(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk));
      }
      controller.close();
    },
  });
}

/** Install a global fetch that streams `chunks` as an SSE body. */
export function mockFetchSSE(chunks: string[], status = 200): void {
  globalThis.fetch = (() =>
    Promise.resolve(
      new Response(sseStream(chunks), {
        status,
        headers: { "Content-Type": "text/event-stream" },
      }),
    )) as typeof fetch;
}

/** Install a global fetch returning a fixed status/body — for status handling tests. */
export function mockFetchResponse(
  status: number,
  body = "",
  headers: Record<string, string> = {},
): void {
  globalThis.fetch = (() =>
    Promise.resolve(new Response(body || null, { status, headers }))) as typeof fetch;
}

/** Capture the single request a call makes, alongside a canned response. */
export function captureFetch(
  status = 200,
  body = "",
  headers: Record<string, string> = {},
): { calls: Array<{ url: string; init?: RequestInit }> } {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  globalThis.fetch = ((url: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(url), init });
    return Promise.resolve(new Response(body || null, { status, headers }));
  }) as typeof fetch;
  return { calls };
}
