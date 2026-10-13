/**
 * Parse one SSE frame — the text between blank-line separators — into its event
 * type and data payload.
 *
 * Used by `sendMessageStreaming` in `chat.ts` and `readGroupSSE` in `groups.ts`.
 * The one other SSE reader in this repo does NOT follow the rules below:
 *
 *  - `BearerEventSource` (`src/lib/bearer-event-source.ts`) — incremental.
 *    Appends and strips `\r`, but uses `trimStart()` on the payload, so it drops
 *    more than the single optional space and does not preserve leading runs.
 *
 * If you change the rules here, reconcile them with that one rather than
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
