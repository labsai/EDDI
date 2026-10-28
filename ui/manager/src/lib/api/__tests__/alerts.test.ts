import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { acknowledgeAlertEvents } from "../alerts";

function captureBody() {
  const bodies: unknown[] = [];
  server.use(
    http.post("*/alerts/events/acknowledge", async ({ request }) => {
      const body = await request.json();
      bodies.push(body);
      const ids = (body as { ids?: string[] }).ids;
      if (ids !== undefined && ids.length === 0) {
        return HttpResponse.json({ error: "INVALID_PARAMETER", message: "ids is empty" }, { status: 400 });
      }
      return HttpResponse.json({ acknowledged: ids?.length ?? 7 });
    }),
  );
  return bodies;
}

describe("acknowledgeAlertEvents — the body selects ids or the filter", () => {
  it("null or undefined acknowledges by filter: the body has no ids", async () => {
    const bodies = captureBody();
    expect(await acknowledgeAlertEvents(null, { agentId: "a1" })).toBe(7);
    expect(await acknowledgeAlertEvents(undefined)).toBe(7);
    expect(bodies).toEqual([{}, {}]);
  });

  it("a list is sent as the selector", async () => {
    const bodies = captureBody();
    expect(await acknowledgeAlertEvents(["e1", "e2"])).toBe(2);
    expect(bodies).toEqual([{ ids: ["e1", "e2"] }]);
  });

  it("an empty list is sent as it is, never widened to everything matching — the server refuses it", async () => {
    const bodies = captureBody();
    await expect(acknowledgeAlertEvents([])).rejects.toBeTruthy();
    expect(bodies).toEqual([{ ids: [] }]);
  });
});
