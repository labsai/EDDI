import { afterEach, describe, expect, it } from "vitest";
import { HttpClient } from "@eddi/core";
import { createUserEventStream, type UserEvent, type UserEventStream } from "@eddi/core/user-events";
import { closeMockUserEventStreams, pushMockUserEvent } from "@eddi/app-kit/test/mocks/handlers";

/** The mock /me/events stream behaves like the backend's, so UI built on it meets the real contract. */
describe("user event handlers — GET /me/events", () => {
  let stream: UserEventStream | null = null;

  afterEach(() => {
    stream?.stop();
    stream = null;
    closeMockUserEventStreams();
  });

  async function until(condition: () => boolean) {
    for (let i = 0; i < 100 && !condition(); i++) {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
  }

  it("opens with ready, then delivers what is pushed as typed events", async () => {
    stream = createUserEventStream({ http: new HttpClient({ baseUrl: "http://localhost" }) });
    const seen: UserEvent[] = [];
    stream.on("approval.pending", (event) => seen.push(event));

    stream.start();
    await until(() => stream?.status === "open");
    expect(stream.status).toBe("open");

    expect(pushMockUserEvent({ kind: "approval.pending", surface: "regular", conversationId: "conv-1" })).toBe(1);
    await until(() => seen.length > 0);

    expect(seen).toHaveLength(1);
    expect(seen[0]).toMatchObject({ kind: "approval.pending", conversationId: "conv-1" });
    expect(typeof seen[0]?.id).toBe("string");
  });

  it("a closed mock stream makes the client wait to reconnect", async () => {
    stream = createUserEventStream({ http: new HttpClient({ baseUrl: "http://localhost" }), initialDelayMs: 60_000 });
    stream.start();
    await until(() => stream?.status === "open");

    closeMockUserEventStreams();
    await until(() => stream?.status === "waiting");

    expect(stream.status).toBe("waiting");
  });
});
