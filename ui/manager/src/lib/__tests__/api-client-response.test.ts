import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { api } from "../api-client";

const PATH = "/probe";
const URL = `${window.location.origin}${PATH}`;

/**
 * The `*WithResponse` family, and why it has to exist.
 *
 * `ApiClient` throws on a non-2xx and returns only the body otherwise, so every
 * call site could read "it did not throw" as "it worked". EDDI has since taught
 * several endpoints to answer partially inside the 2xx range — 207 for an
 * erasure whose cascade did not finish, 200 vs 201 vs 207 for an upgrade, an
 * `X-Cascade-Skipped` header for a delete that declined to touch something —
 * and none of that survived the old signature.
 *
 * These tests pin the property the partial-result work depends on: the status
 * reaches the caller. They go through real MSW handlers rather than a stubbed
 * fetch, because the thing under test IS the fetch handling.
 */
describe("api.getWithResponse", () => {
  it("hands back a 207 as data plus status, rather than throwing or flattening it", async () => {
    // The case the whole family exists for. 207 is `response.ok`, so the old
    // signature returned the body and the caller reported a clean success.
    server.use(
      http.get(URL, () =>
        HttpResponse.json({ complete: false }, { status: 207 }),
      ),
    );

    const response = await api.getWithResponse<{ complete: boolean }>(PATH);

    expect(response.status).toBe(207);
    expect(response.data).toEqual({ complete: false });
  });

  it("still reports 200 as 200, so a caller can tell the two apart", async () => {
    // Half of the pin above: a test that only asserted 207 would pass against a
    // client that hardcoded it.
    server.use(
      http.get(URL, () => HttpResponse.json({ complete: true }, { status: 200 })),
    );

    const response = await api.getWithResponse<{ complete: boolean }>(PATH);

    expect(response.status).toBe(200);
    expect(response.data).toEqual({ complete: true });
  });

  it("throws on a non-2xx exactly as the plain verb does", async () => {
    // The envelope is about widening what a SUCCESS can say. It must not turn
    // failures into values a caller has to remember to check.
    server.use(
      http.get(URL, () =>
        HttpResponse.json({ message: "nope" }, { status: 409 }),
      ),
    );

    await expect(api.getWithResponse(PATH)).rejects.toMatchObject({
      status: 409,
      message: "nope",
    });
  });
});

describe("error message length", () => {
  it("keeps a strict-parser rejection intact past the old 400-character cap", async () => {
    // EDDI names the offending field, then lists every field the model
    // declares, then explains why it refused rather than dropping the key. The
    // field list is the actionable half and it sits in the middle, so a cap
    // that truncated the message cut exactly the part that says what to type.
    const knownFields = Array.from({ length: 40 }, (_, i) => `field${i}`).join(", ");
    const message =
      `Unknown field 'setProperties' in OutputConfiguration. Known fields: [${knownFields}]. ` +
      "The field was rejected instead of being silently discarded — a dropped key looks " +
      "like a successful save but changes nothing.";
    expect(message.length).toBeGreaterThan(400);
    expect(message.length).toBeLessThan(800);

    server.use(
      http.put(URL, () => HttpResponse.json({ message }, { status: 400 })),
    );

    await expect(api.put(PATH, {})).rejects.toMatchObject({ message });
  });

  it("still truncates a stack trace", async () => {
    // The cap was raised, not removed. A thousand frames of Java in a toast
    // tells the user nothing.
    const trace = "at ai.labs.eddi.Foo.bar(Foo.java:1)\n".repeat(200);
    server.use(http.put(URL, () => HttpResponse.text(trace, { status: 500 })));

    await expect(api.put(PATH, {})).rejects.toMatchObject({
      message: expect.stringMatching(/…$/) as unknown as string,
    });
  });
});
