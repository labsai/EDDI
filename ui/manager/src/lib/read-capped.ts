/**
 * Read a response body, giving up as soon as it exceeds `maxBytes`.
 *
 * Returns `null` when the cap is hit, having cancelled the stream.
 *
 * `await res.text()` cannot do this: it buffers the whole body first, so the cap
 * is only consulted once the memory is already spent — a hostile or misaddressed
 * URL could exhaust the tab before the check ran. Counting `byteLength` per chunk
 * also measures the right thing; `String.length` counts UTF-16 code units, which
 * undercounts every multi-byte character in a UTF-8 document.
 *
 * The `Content-Length` pre-check short-circuits the common case but cannot
 * replace the streaming count: the header is absent on chunked responses and is
 * not binding even when present.
 *
 * Lives here rather than beside its one caller so it can be unit-tested — the
 * cap is security-relevant, and a page module may not export non-components
 * (`react-refresh/only-export-components`).
 */
export async function readCapped(
  response: Response,
  maxBytes: number,
): Promise<string | null> {
  const declared = Number(response.headers.get("Content-Length"));
  if (Number.isFinite(declared) && declared > maxBytes) {
    // Rejecting on the header alone still leaves the body unread, which holds
    // the connection open until GC. Release it before bailing.
    await response.body?.cancel().catch(() => {});
    return null;
  }

  const reader = response.body?.getReader();
  // No streaming body (some polyfills, and jsdom in tests): fall back to the
  // buffered read, still enforcing the cap afterwards.
  if (!reader) {
    const whole = await response.text();
    return new TextEncoder().encode(whole).byteLength > maxBytes ? null : whole;
  }

  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }

  const joined = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    joined.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(joined);
}
