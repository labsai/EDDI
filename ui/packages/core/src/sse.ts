/* ──────────────────────────────────────────────
   @eddi/core — Server-Sent Events
   The one frame parser and stream reader for EDDI's agent stream
   (POST /agents/{conversationId}/stream) and group streams
   (/groups/.../discuss/stream and friends).

   It replaces three readers that each got a different subset right: the
   Manager's `sse-utils.ts` + `chat.ts` loop, the group reader in the Manager's
   `groups.ts`, and the Chat UI's `parseSseFrame` + loop in `chat-api.ts`.
   ────────────────────────────────────────────── */

/** One parsed frame: its event type and its (joined) data payload. */
export interface SseFrame {
  type: string;
  data: string;
}

/**
 * Parse one SSE frame — the text between blank-line separators — into its event
 * type and data payload.
 *
 * It follows the WHATWG spec on the three points that are easy to get wrong and
 * that a hand-rolled parser reliably gets wrong:
 *
 *  - **Multiple `data:` lines in one frame concatenate with "\n".** They do not
 *    overwrite. Assigning instead of appending silently truncates every
 *    multi-line payload to its final line.
 *  - **Only the single optional space after the colon is stripped.** Calling
 *    `.trim()` destroys leading and trailing whitespace, which corrupts token
 *    streams where a lone " " is a meaningful chunk. EDDI writes that delimiter
 *    explicitly: RESTEasy's SseUtil emits "data:" with no space of its own, so
 *    `RestAgentEngineStreaming.padDataLines` prefixes every data line with one.
 *    A token " word" therefore arrives as "data:  word" — strip exactly one.
 *  - **A trailing "\r" from CRLF framing is removed** before the value is read.
 *
 * Returns `null` for a frame carrying no field lines (a `:` heartbeat comment,
 * or blank padding), so callers can simply skip it. Defaulting such a frame to a
 * type made every keep-alive render as visible text in the agent's message.
 */
export function parseSseFrame(frame: string, defaultEventType = "message"): SseFrame | null {
  let type = defaultEventType;
  const dataLines: string[] = [];
  let sawField = false;

  for (const rawLine of frame.split("\n")) {
    const line = rawLine.endsWith("\r") ? rawLine.slice(0, -1) : rawLine;
    if (line.startsWith(":")) continue; // comment / heartbeat
    if (line.startsWith("event:")) {
      // Same optional-space-only rule as `data:`. An explicitly empty `event:`
      // reverts to the caller's default rather than yielding type "", which no
      // consumer switch has a case for.
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

export interface ReadSseOptions {
  /**
   * The type of a frame with no `event:` line. The agent stream uses "token"
   * (EDDI names every event, so this only matters for a bare `data:` frame).
   * `null` skips such frames: group events are dispatched by name, so a frame
   * without one has nothing to dispatch on.
   */
  defaultEventType: string | null;
}

/**
 * Sentinel for "this frame carried no `event:` line", used when the caller
 * asked for such frames to be skipped. `parseSseFrame` always returns a type,
 * falling back to the default it is given, so a sentinel tells the two apart
 * without re-parsing.
 */
const NO_EVENT_TYPE = "\u0000no-event-type";

/**
 * Read an SSE response body as a stream of frames.
 *
 *  - Line endings are normalised on the ACCUMULATED buffer, not per chunk, and a
 *    trailing lone "\r" is held back: it may be the first half of a "\r\n" that
 *    lands with the next read. Per-chunk normalisation missed a "\r\n" straddling
 *    two `reader.read()` calls, and the frame silently failed to split. A bare
 *    "\r" line ending (allowed by the spec) is handled too.
 *  - At end of stream the DECODER is flushed (every read passes
 *    `{ stream: true }`, which holds back a trailing incomplete UTF-8 sequence),
 *    and then the BUFFER: a server or proxy that closes right after the last
 *    frame drops the blank line that terminates it, and that frame is usually
 *    the one that matters (`done`, `error`, `group_complete`).
 */
export async function* readSseStream(
  body: ReadableStream<Uint8Array> | null | undefined,
  options: ReadSseOptions,
): AsyncGenerator<SseFrame> {
  const reader = body?.getReader();
  if (!reader) throw new Error("No readable stream");

  const fallbackType = options.defaultEventType ?? NO_EVENT_TYPE;
  const decoder = new TextDecoder();
  let buffer = "";

  function toFrame(part: string): SseFrame | null {
    const parsed = parseSseFrame(part, fallbackType);
    if (!parsed || parsed.type === NO_EVENT_TYPE) return null;
    return parsed;
  }

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      buffer = buffer.replace(/\r\n/g, "\n").replace(/\r(?!$)/g, "\n");
      const parts = buffer.split("\n\n");
      buffer = parts.pop() ?? "";

      for (const part of parts) {
        const frame = toFrame(part);
        if (frame) yield frame;
      }
    }

    buffer += decoder.decode();
    buffer = buffer.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
    for (const part of buffer.split("\n\n")) {
      const frame = toFrame(part);
      if (frame) yield frame;
    }
  } finally {
    reader.releaseLock();
  }
}
