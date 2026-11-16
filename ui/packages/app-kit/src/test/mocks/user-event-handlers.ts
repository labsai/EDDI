import { http, HttpResponse } from "msw";

/**
 * The per-user event stream, `GET /me/events` (docs/user-events.md).
 *
 * Like the backend, every stream opens with a `ready` frame and then stays
 * open. Nothing is sent on its own: a test or a dev session that wants an
 * event calls `pushMockUserEvent`, which writes it to every open mock stream
 * (the backend's scoping is not modelled — every mock tab is "the user").
 * `closeMockUserEventStreams` ends them, as a server restart would, so a
 * client's reconnect can be exercised.
 *
 * Event shape follows the backend: `{id, kind, ts, …ids}`, ids and kinds only.
 */

type MockUserEvent = { kind: string } & Record<string, unknown>;

const encoder = new TextEncoder();
const open = new Set<ReadableStreamDefaultController<Uint8Array>>();
let sequence = 0;

/** Writes one event to every open mock stream. Returns how many received it. */
export function pushMockUserEvent(event: MockUserEvent): number {
  const payload = { id: `mock-${++sequence}`, ts: Date.now(), ...event };
  const frame = encoder.encode(`event: ${event.kind}\ndata: ${JSON.stringify(payload)}\n\n`);
  for (const controller of open) {
    try {
      controller.enqueue(frame);
    } catch {
      open.delete(controller);
    }
  }
  return open.size;
}

/** Ends every open mock stream. */
export function closeMockUserEventStreams(): void {
  for (const controller of open) {
    try {
      controller.close();
    } catch {
      // already closed
    }
  }
  open.clear();
}

export const userEventHandlers = [
  http.get("*/me/events", ({ request }) => {
    let mine: ReadableStreamDefaultController<Uint8Array> | null = null;
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        mine = controller;
        open.add(controller);
        controller.enqueue(encoder.encode('event: ready\ndata: {"v":1,"heartbeatSeconds":20}\n\n'));
        request.signal.addEventListener("abort", () => {
          open.delete(controller);
          try {
            controller.close();
          } catch {
            // already closed
          }
        });
      },
      cancel() {
        if (mine) open.delete(mine);
      },
    });
    return new HttpResponse(body, { status: 200, headers: { "Content-Type": "text/event-stream" } });
  }),
];
